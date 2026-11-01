package com.mithole.cruscotto.obd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class LangTest {
    @After fun italianAgain() {
        L.lang = Lang.IT
    }

    @Test
    fun defaults() {
        assertEquals(Lang.IT, Lang.forDevice(Locale.ITALY))
        assertEquals(Lang.EN, Lang.forDevice(Locale.US))
        assertEquals(Lang.EN, Lang.forDevice(Locale.GERMANY))
        assertEquals(Lang.EN, Lang.fromTag("en"))
        assertEquals(null, Lang.fromTag("fr"))
    }

    @Test
    fun switchesEverywhere() {
        assertEquals("Benzina", Fuel.PETROL.label)
        assertEquals("giri/min", Metric.RPM.unit)
        assertEquals("Sensore di battito: circuito", Dtc("P0325").description)
        L.lang = Lang.EN
        assertEquals("Petrol", Fuel.PETROL.label)
        assertEquals("rpm", Metric.RPM.unit)
        assertEquals("Front left", Wheel.FL.label)
        assertEquals("Knock sensor: circuit", Dtc("P0325").description)
        assertEquals("Misfire, cylinder 3", Dtc("P0303").description)
        assertEquals("Ignition · manufacturer-specific", Dtc("P1300").system)
    }

    /** In inglese il CSV usa virgola e punto; la rilettura riconosce tutti e due i formati. */
    @Test
    fun csvBothFormats() {
        for (lang in Lang.entries) {
            L.lang = lang
            val dir = Files.createTempDirectory("rec").toFile()
            val r = Recorder(dir)
            r.start(1_000_000)
            r.write(1_000_500, mapOf(Metric.RPM to 3420.5, Metric.BOOST to 0.85), 3, GpsFix(38.9, 16.6, 87.0, 5f, 0))
            r.write(1_002_250, mapOf(Metric.RPM to 3600.0), 3)
            r.stop()
            val f = dir.listFiles()!!.single()
            val head = f.readLines().first()
            if (lang == Lang.EN) assertTrue(head, head.contains("Seconds,")) else assertTrue(head, head.contains("Secondi;"))
            val track = Recorder.read(f)
            assertEquals(2.25, track.seconds, 0.001)
            assertEquals(3420.5f, track.series[Metric.RPM]!!.first().v, 0.01f)
            assertEquals(0.85f, track.series[Metric.BOOST]!!.first().v, 0.001f)
            assertEquals(38.9, track.path.single().lat, 1e-6)
            assertEquals(2.25, r.list().single().seconds, 0.001)
            dir.deleteRecursively()
        }
    }
}
