package com.mithole.cruscotto.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {
    private val petrolTurbo = Profile(detectedFuel = Fuel.PETROL, boostSeen = true)

    /** Accelerazione costante [kmhPerS] da fermo, letta ogni [stepMs]; la partenza cade tra due letture. */
    private fun launch(a: Analyzer, kmhPerS: Double, stepMs: Long, startAt: Long = 1_130): Map<Run, Double> {
        val records = HashMap<Run, Double>()
        var t = 0L
        while (t < 30_000) {
            val v = if (t < startAt) 0.0 else (t - startAt) / 1000.0 * kmhPerS
            records += a.feed(t, stepMs / 1000.0, mapOf(Metric.SPEED to v, Metric.RPM to 3000.0), petrolTurbo)
            if (v > 130) break
            t += stepMs
        }
        return records
    }

    @Test
    fun zeroToHundredFromSlowSamples() {
        // 12,5 km/h al secondo: 0-100 in 8 s, 0-50 in 4 s, letti solo 3 volte al secondo.
        val a = Analyzer()
        val r = launch(a, 12.5, 333)
        assertEquals(8.0, r.getValue(Run.ZERO_100), 0.05)
        assertEquals(4.0, r.getValue(Run.ZERO_50), 0.05)
    }

    @Test
    fun liftingOffCancelsTheRun() {
        val a = Analyzer()
        var t = 0L
        val speeds = listOf(0.0, 0.0, 10.0, 25.0, 40.0, 35.0, 50.0, 70.0, 90.0, 110.0)
        val done = HashMap<Run, Double>()
        for (v in speeds) {
            done += a.feed(t, 0.5, mapOf(Metric.SPEED to v), petrolTurbo)
            t += 500
        }
        assertTrue(done.isEmpty())
    }

    @Test
    fun midRangeFromBelowEighty() {
        // Da 70 a 130 a 8 km/h al secondo: 80-120 in 5 s.
        val a = Analyzer()
        var t = 0L
        var result: Double? = null
        while (t <= 8_000) {
            val v = 70 + t / 1000.0 * 8
            a.feed(t, 0.3, mapOf(Metric.SPEED to v), petrolTurbo)[Run.MID_80_120]?.let { result = it }
            t += 300
        }
        assertEquals(5.0, result!!, 0.05)
    }

    @Test
    fun onlyFasterRunsAreRecords() {
        val a = Analyzer()
        a.setBests(mapOf(Run.ZERO_100 to 7.0))
        val r = launch(a, 12.5, 250)
        assertNull(r[Run.ZERO_100])
        assertEquals(4.0, r.getValue(Run.ZERO_50), 0.05)
    }

    @Test
    fun leanAtIdleOnlyPointsToAVacuumLeak() {
        val a = Analyzer()
        val idle = mapOf(Metric.RPM to 850.0, Metric.SPEED to 0.0, Metric.COOLANT to 90.0, Metric.STFT to 6.0, Metric.LTFT to 8.0)
        val cruise = mapOf(Metric.RPM to 2800.0, Metric.SPEED to 80.0, Metric.COOLANT to 90.0, Metric.LOAD to 70.0, Metric.STFT to 1.0, Metric.LTFT to 1.0)
        var t = 0L
        repeat(150) { a.feed(t, 1.0, idle, petrolTurbo); t += 1000 }
        repeat(30) { a.feed(t, 1.0, cruise, petrolTurbo); t += 1000 }
        val has = setOf(Metric.STFT, Metric.LTFT, Metric.COOLANT)
        val mix = a.snapshot(t, petrolTurbo, has, cruise).checks.first { it.title.startsWith("Miscela") }
        assertEquals(Level.WARN, mix.level)
        assertTrue(mix.detail, mix.detail.contains("aria falsa"))
    }

    @Test
    fun weakChargingIsFlagged() {
        val a = Analyzer()
        var t = 0L
        a.feed(t, 1.0, mapOf(Metric.RPM to 0.0, Metric.VOLT to 12.6), petrolTurbo)
        repeat(90) { t += 1000; a.feed(t, 1.0, mapOf(Metric.RPM to 900.0, Metric.VOLT to 12.7), petrolTurbo) }
        val c = a.snapshot(t, petrolTurbo, setOf(Metric.VOLT), emptyMap()).checks.first { it.title.startsWith("Batteria") }
        assertEquals(Level.BAD, c.level)
    }

    @Test
    fun powerFromFuelIsPlausible() {
        // Una MiTo 1.4 T da 135 CV a pieno carico brucia sui 37 L/h: deve uscire intorno ai 99 kW.
        assertEquals(99.0, Analyzer.powerFromFuel(36.7, Fuel.PETROL)!!, 2.0)
    }
}
