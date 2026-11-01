package com.mithole.cruscotto.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class SniffTest {
    @Test
    fun parsesElevenBitWithAndWithoutSpaces() {
        val a = CanParse.frame("1A0 01 02 FF", 5)!!
        assertEquals(0x1A0, a.id)
        assertFalse(a.ext)
        assertEquals(listOf(0x01, 0x02, 0xFF), a.data)
        val b = CanParse.frame("7E8064100BE3EA813", 0)!!
        assertEquals(0x7E8, b.id)
        assertEquals(listOf(0x06, 0x41, 0x00, 0xBE, 0x3E, 0xA8, 0x13), b.data)
    }

    @Test
    fun parsesTwentyNineBit() {
        val f = CanParse.frame("18 DA F1 10 03 41 0C 1A", 0)!!
        assertTrue(f.ext)
        assertEquals(0x18DAF110, f.id)
        assertEquals(listOf(0x03, 0x41, 0x0C, 0x1A), f.data)
    }

    @Test
    fun rejectsNoiseAndErrors() {
        assertNull(CanParse.frame("BUFFER FULL", 0))
        assertNull(CanParse.frame("<RX ERROR", 0))
        assertNull(CanParse.frame("?", 0))
        assertNull(CanParse.frame("1A0 01 02 03 04 05 06 07 08 09", 0)) // più di 8 byte: riga spezzata male
        assertNull(CanParse.frame("1A0 1 02", 0))
    }

    @Test
    fun statsTrackChangingBytesAndRates() {
        val log = StringWriter()
        val s = Sniffer(log)
        s.line("0A0 10 00 05", 0)
        s.line("0A0 12 00 05", 10)
        s.line("0A0 14 00 05", 20)
        s.line("3E8 01 02", 30)
        s.line("BUFFER FULL", 40)
        s.line("garbage!", 50)
        s.window(SniffWindow(1000))
        s.window(SniffWindow(1000, filter = 0x000, mask = 0x700))
        assertEquals(4, s.frames)
        assertEquals(1, s.overflows)
        assertEquals(1, s.garbled)
        val a0 = s.stats.values.first { it.id == 0x0A0 }
        assertTrue(a0.varies(0))
        assertFalse(a0.varies(1))
        assertEquals(2, a0.changes[0])
        // 0A0 passa in entrambe le finestre (2 s), 3E8 solo nella prima (1 s).
        assertEquals(1.5, s.rate(a0), 0.001)
        assertEquals(1.0, s.rate(s.stats.values.first { it.id == 0x3E8 }), 0.001)
        val text = s.summary(listOf("prova"))
        assertTrue(text.lines().any { it.startsWith("0A0  1.5/s  3B  14 00 05") })
        assertTrue(text.lines().any { it.startsWith("0A0") && "B0[10-14,2]" in it && "B2" !in it })
        assertTrue(text.lines().any { it.startsWith("3E8") && it.endsWith("fisso") })
        assertEquals("0 0A0 10 00 05", log.toString().lines().first())
    }

    @Test
    fun demoListensThroughTheSamePath() = runBlocking {
        val elm = Elm327(DemoLink())
        elm.init {}
        val s = Sniffer()
        val plan = listOf(SniffWindow(400), SniffWindow(400, filter = 0x000, mask = 0x700))
        elm.sniff(s, plan, System.currentTimeMillis()) {}
        elm.endSniff()
        assertTrue(s.frames > 10)
        // Nella seconda finestra passano solo gli indirizzi 0xx: si vede dal riassunto, che li conta tutti.
        assertTrue(s.stats.values.any { it.id == 0x0A0 })
        assertTrue(s.stats.values.all { it.id in setOf(0x0A0, 0x0FA, 0x101, 0x2FA, 0x3E8, 0x4C0, 0x6F1) })
        // Dopo l'ascolto le domande normali funzionano di nuovo.
        assertTrue(elm.mode01(0x0C) != null)
    }
}
