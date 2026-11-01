package com.mithole.cruscotto.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GearsRecorderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Giri per km/h di un cambio a sei marce qualsiasi.
    private val box = listOf(128.0, 73.0, 50.0, 38.0, 31.0, 25.5)

    /** Una partenza da fermo che passa per tutte le marce, tenendo ognuna qualche secondo; letture ogni 300 ms. */
    private fun drive(g: Gears) {
        g.feed(0.0, 850.0, 0.3)
        for ((i, k) in box.withIndex()) {
            // Ogni marcia da 2000 a 4500 giri, con una frizione che slitta al cambio.
            var rpm = 2000.0
            while (rpm < 4500) {
                g.feed(rpm / k, rpm, 0.3)
                rpm += 120
            }
            if (i < box.lastIndex) g.feed(4500 / k, 2600.0, 0.3)
        }
    }

    @Test
    fun learnsGearsAndCountsFromFirst() {
        val g = Gears()
        drive(g)
        assertTrue("con una sola partenza la prima non è ancora sicura", g.ratios().isEmpty())
        drive(g)
        val r = g.ratios()
        assertEquals(box.size, r.size)
        box.zip(r).forEach { (want, got) -> assertEquals(want, got, want * 0.02) }
        assertEquals(1, g.gear(15.0, 15 * 128.0, r))
        assertEquals(4, g.gear(90.0, 90 * 38.0, r))
        assertNull("frizione premuta", g.gear(90.0, 1000.0, r))
    }

    @Test
    fun savesAndLoads() {
        val g = Gears()
        drive(g)
        drive(g)
        val copy = Gears().apply { load(g.save()) }
        assertEquals(g.ratios().size, copy.ratios().size)
        assertEquals(g.ratios()[2], copy.ratios()[2], 0.01)
    }

    @Test
    fun recordsAndReadsBack() {
        val rec = Recorder(tmp.root)
        rec.start(1_000_000)
        rec.write(1_000_000, mapOf(Metric.SPEED to 0.0, Metric.RPM to 850.0), null)
        rec.write(1_000_500, mapOf(Metric.SPEED to 12.5, Metric.RPM to 1900.0, Metric.BOOST to 0.42), 1)
        rec.write(1_001_000, mapOf(Metric.SPEED to 25.0, Metric.RPM to 3150.0), 1)
        rec.stop()
        val list = rec.list()
        assertEquals(1, list.size)
        assertEquals(1.0, list[0].seconds, 0.001)
        val track = Recorder.read(list[0].file)
        assertEquals(3, track.series.getValue(Metric.RPM).size)
        assertEquals(12.5f, track.series.getValue(Metric.SPEED)[1].v, 0.01f)
        assertEquals(500L, track.series.getValue(Metric.SPEED)[1].t)
        assertEquals(1, track.series.getValue(Metric.BOOST).size)
        // CSV per Excel italiano: punto e virgola e virgola decimale.
        assertTrue(list[0].file.readLines()[2].startsWith("0,50;"))
    }
}
