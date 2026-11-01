package com.mithole.cruscotto.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI

class ExtrasTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun dynoRecoversKnownTorque() {
        // Un motore da 200 Nm costanti, in 3ª (38 giri per km/h), 1250 kg: simulo la tirata con la stessa fisica e la leggo a 3 Hz.
        val mass = 1250
        val k = 38.0
        val gear = 3
        var rpm = 1800.0
        var t = 0.0
        val dyno = Dyno()
        dyno.feed(0, 1700.0, gear, k, 10.0, mass) // acceleratore ancora su
        var result: DynoResult? = null
        var next = 0.0
        while (rpm < 6200) {
            val v = rpm / k / 3.6
            val engineW = 200.0 * 2 * PI * rpm / 60
            val wheelW = engineW * Dyno.DRIVETRAIN
            val resist = 0.5 * 1.2 * Dyno.CDA * v * v * v + Dyno.CRR * mass * 9.81 * v
            val a = (wheelW - resist) / (mass * Dyno.inertia(gear) * v)
            rpm += a * 3.6 * k * 0.001
            t += 0.001
            if (t >= next) {
                dyno.feed((t * 1000).toLong(), rpm, gear, k, 100.0, mass)
                next += 0.333
            }
        }
        result = dyno.feed((t * 1000).toLong() + 333, rpm - 800, gear, k, 20.0, mass) ?: result
        assertNotNull(result)
        val mid = result!!.points.filter { it.rpm in 2500.0..5500.0 }
        assertTrue(mid.isNotEmpty())
        mid.forEach { assertEquals(200.0, it.nm, 12.0) }
        // 200 Nm a 5500 giri sono circa 157 CV.
        assertEquals(157.0, result.points.first { it.rpm == 5500.0 }.cv, 10.0)
        assertEquals(result.points.size, DynoResult.load(result.save())!!.points.size)
    }

    @Test
    fun shortPullIsRejected() {
        val d = Dyno()
        for (i in 0..5) d.feed(i * 333L, 2000.0 + i * 100, 3, 38.0, 100.0, 1250)
        assertNull(d.feed(2000, 2400.0, 3, 38.0, 10.0, 1250))
        assertNotNull(d.state.message)
    }

    @Test
    fun tpmsCapFormat() {
        // Indirizzo, 230000 Pa = 2,30 bar, 2450 = 24,50 °C, batteria 87 %, nessun allarme.
        val data = byteArrayOf(
            0x80.toByte(), 0xEA.toByte(), 0xCA.toByte(), 0x10, 0x12, 0x34,
            0xB0.toByte(), 0x82.toByte(), 0x03, 0x00,
            0x92.toByte(), 0x09, 0x00, 0x00,
            87, 0,
        )
        val r = TpmsDecoder.decode(data, 1000)!!
        assertEquals(2.30, r.bar, 0.001)
        assertEquals(24.5, r.tempC!!, 0.01)
        assertEquals(87, r.batteryPct)
        // Dati di un altro dispositivo qualsiasi: non devono sembrare una gomma.
        assertNull(TpmsDecoder.decode(ByteArray(16) { 0x7F }, 1000))
    }

    @Test
    fun slowPunctureIsSpotted() {
        val now = 100_000L
        val all = mapOf(Wheel.FL to TireReading(2.05, 20.0, 90, now), Wheel.FR to TireReading(2.3, 20.0, 90, now))
        val fl = Tpms.tireState(Wheel.FL, "a", all[Wheel.FL], 2.3, now, all)
        assertEquals(TireLevel.LOW, fl.level)
        assertEquals(TireLevel.OK, Tpms.tireState(Wheel.FR, "b", all[Wheel.FR], 2.3, now, all).level)
        assertEquals(TireLevel.FLAT, Tpms.tireState(Wheel.FL, "a", TireReading(1.7, null, null, now), 2.3, now, all).level)
        assertEquals(TireLevel.NONE, Tpms.tireState(Wheel.RL, null, null, 2.1, now, all).level)
    }

    @Test
    fun turboCooldownCountsDown() {
        val a = Advisor()
        val turbo = Profile(detectedFuel = Fuel.PETROL, boostSeen = true)
        val warm = mapOf(Metric.COOLANT to 90.0)
        repeat(30) { a.feed(1.0, warm + mapOf(Metric.RPM to 5000.0, Metric.SPEED to 120.0, Metric.BOOST to 1.0), turbo) }
        val first = a.feed(1.0, warm + mapOf(Metric.RPM to 850.0, Metric.SPEED to 0.0), turbo)
        assertEquals("Turbo caldo", first?.title)
        var done = false
        repeat(120) { if (!done) { a.feed(1.0, warm + mapOf(Metric.RPM to 850.0, Metric.SPEED to 0.0), turbo); done = a.cooledDown } }
        assertTrue(done)
    }

    @Test
    fun coldEngineWarnsHarderWhenRevved() {
        val a = Advisor()
        val p = Profile(detectedFuel = Fuel.PETROL)
        assertEquals(Level.WARN, a.feed(1.0, mapOf(Metric.RPM to 2000.0, Metric.COOLANT to 40.0), p)?.level)
        assertEquals(Level.BAD, a.feed(1.0, mapOf(Metric.RPM to 4200.0, Metric.COOLANT to 40.0), p)?.level)
        assertNull(a.feed(1.0, mapOf(Metric.RPM to 4200.0, Metric.COOLANT to 88.0), p))
    }

    @Test
    fun alerterSpeaksOncePerProblem() {
        val al = Alerter()
        val hot = mapOf(Metric.COOLANT to 107.0, Metric.RPM to 900.0)
        assertEquals(1, al.feed(0, 1.0, hot, null, null, false, emptyList()).size)
        assertEquals(0, al.feed(1000, 1.0, hot, null, null, false, emptyList()).size)
        val normal = mapOf(Metric.COOLANT to 90.0)
        al.feed(2000, 1.0, normal, Status(mil = false, dtcCount = 0, monitors = emptyList()), null, false, emptyList())
        val said = al.feed(3000, 1.0, normal, Status(mil = true, dtcCount = 1, monitors = emptyList()), null, false, emptyList())
        assertTrue(said.single().contains("errore"))
    }

    @Test
    fun tripsEndWhenEngineStopsAndAreLogged() {
        val tr = TripTracker()
        var t = 0L
        var ended: Trip? = null
        repeat(600) { // 10 minuti a 60 km/h, 5 L/h
            tr.feed(t, 1.0, 60.0, 2000.0, 5.0, 1.9)
            t += 1000
        }
        repeat(70) {
            tr.feed(t, 1.0, 0.0, 0.0, null, 1.9)?.let { ended = it }
            t += 1000
        }
        val trip = ended!!
        assertEquals(10.0, trip.km, 0.05)
        assertEquals(0.833, trip.liters!!, 0.01)
        assertEquals(8.33, trip.l100!!, 0.05)
        assertEquals(1.58, trip.cost!!, 0.01)
        val log = TripLog(tmp.newFile("viaggi.txt"))
        log.append(trip)
        assertEquals(trip.km, log.list().single().km, 0.001)
    }

    @Test
    fun recordingKeepsGpsPath() {
        val rec = Recorder(tmp.root)
        rec.start(0)
        rec.write(0, mapOf(Metric.SPEED to 50.0), 3, GpsFix(38.9101, 16.5873, 49.0, 4f, 0))
        rec.write(1000, mapOf(Metric.SPEED to 52.0), 3, GpsFix(38.9102, 16.5875, 51.5, 4f, 1000))
        rec.stop()
        val track = Recorder.read(rec.list().single().file)
        assertEquals(2, track.path.size)
        assertEquals(16.5875, track.path[1].lon, 1e-6)
        assertEquals(52f, track.path[1].kmh, 0.01f)
    }
}
