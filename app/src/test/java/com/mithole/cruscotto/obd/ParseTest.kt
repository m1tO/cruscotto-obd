package com.mithole.cruscotto.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    @Test
    fun rpmWithSpacesAndEcho() {
        val lines = ObdParse.lines("010C\r41 0C 1A F8 \r\r", "010C")
        val (m, v) = Pids.decode(0x0C, ObdParse.mode01(lines, 0x0C)!!)!!
        assertEquals(Metric.RPM, m)
        assertEquals(1726.0, v, 0.01)
    }

    @Test
    fun searchingAndNoData() {
        assertNull(ObdParse.mode01(ObdParse.lines("SEARCHING...\rNO DATA\r", "0105"), 0x05))
        assertEquals(listOf(0x7B), ObdParse.mode01(ObdParse.lines("SEARCHING...\r41057B\r", "0105"), 0x05))
    }

    @Test
    fun canMultiFrameDtcs() {
        val lines = ObdParse.lines("00A\r0: 43 04 01 33 03 00\r1: 03 01 04 20 00 00 00\r", "03")
        val codes = ObdParse.dtcs(ObdParse.messages(lines), 0x43, isCan = true)
        assertEquals(listOf("P0133", "P0300", "P0301", "P0420"), codes)
    }

    @Test
    fun canSingleFrameNoCodes() {
        assertEquals(emptyList<String>(), ObdParse.dtcs(ObdParse.messages(listOf("4300")), 0x43, isCan = true))
    }

    @Test
    fun kLineDtcs() {
        val codes = ObdParse.dtcs(ObdParse.messages(listOf("43 01 33 03 00 00 00")), 0x43, isCan = false)
        assertEquals(listOf("P0133", "P0300"), codes)
    }

    @Test
    fun dtcLetters() {
        assertEquals("U0100", DtcCodec.decode(0xC1, 0x00))
        assertEquals("C1234", DtcCodec.decode(0x52, 0x34))
        assertEquals(listOf(0x02, 0x99), DtcCodec.encode("P0299"))
    }

    @Test
    fun supportedBitmap() {
        val pids = ObdParse.bitmap(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(0x01 in pids && 0x0C in pids && 0x0D in pids && 0x20 in pids)
        assertFalse(0x02 in pids)
    }

    @Test
    fun statusMilAndMonitors() {
        val s = ObdParse.status(listOf(0x82, 0x07, 0x65, 0x04))!!
        assertTrue(s.mil)
        assertEquals(2, s.dtcCount)
        assertFalse(s.monitors.first { it.name == "Sistema evaporativo" }.complete)
        assertTrue(s.monitors.first { it.name == "Catalizzatore" }.complete)
    }

    @Test
    fun statusDieselBit() {
        assertFalse(ObdParse.status(listOf(0x00, 0x07, 0x65, 0x00))!!.diesel)
        assertTrue(ObdParse.status(listOf(0x00, 0x0F, 0xE9, 0x00))!!.diesel)
    }

    @Test
    fun fuelAndProfile() {
        assertEquals(Fuel.PETROL, Fuel.fromPid51(0x01))
        assertEquals(Fuel.LPG, Fuel.fromPid51(0x0C))
        assertEquals(Fuel.DIESEL, Fuel.fromPid51(0x04))
        assertNull(Fuel.fromPid51(0x08))

        val petrol = Profile(detectedFuel = Fuel.PETROL)
        assertFalse(petrol.turbo)
        assertEquals(0.0, petrol.boostMax, 0.0)
        assertEquals(7000, petrol.rpmMax)
        assertTrue(petrol.copy(boostSeen = true).turbo)

        val diesel = Profile(detectedFuel = Fuel.DIESEL)
        assertTrue(diesel.turbo)
        assertEquals(6000 to 4500, diesel.rpmMax to diesel.rpmRed)
        assertFalse(diesel.copy(setup = Setup(turbo = false)).turbo)
        assertEquals(8000 to 7250, Profile(setup = Setup(rpmMax = 8000)).let { it.rpmMax to it.rpmRed })
    }

    @Test
    fun brandFromVin() {
        assertEquals("Alfa Romeo", Wmi.brand("ZAR95500001234567"))
        assertEquals("Toyota", Wmi.brand("JTDKB20U403123456"))
        assertNull(Wmi.brand(DemoLink.VIN))
    }

    @Test
    fun descriptions() {
        assertEquals("Sovralimentazione insufficiente del turbo (underboost)", Dtc("P0299").description)
        assertEquals("Mancata accensione cilindro 3", Dtc("P0303").description)
        assertTrue(Dtc("P1A23").description.startsWith("Codice specifico"))
    }

    @Test
    fun demoEndToEnd() = runBlocking {
        val e = Elm327(DemoLink().also { it.open() })
        e.init { }
        assertTrue(e.isCan)
        assertEquals("ISO 15765-4 (CAN 11/500)", e.protocol)
        val pids = e.supportedPids()
        assertTrue(0x0C in pids && 0x0B in pids && 0x49 in pids && 0x33 in pids)
        val rpm = Pids.decode(0x0C, e.mode01(0x0C)!!)!!.second
        assertTrue(rpm > 500)
        assertEquals(14.0, e.voltage()!!, 0.5)
        assertEquals(listOf("P0299", "P0133"), e.dtcs(0x03)!!.map { it.code })
        assertEquals(listOf("P0300"), e.dtcs(0x07)!!.map { it.code })
        assertNull(e.dtcs(0x0A))
        assertEquals(DemoLink.VIN, e.vin())
        val ff = e.freezeFrame()!!
        assertEquals("P0299", ff.dtc.code)
        assertEquals(3150.0, ff.values[Metric.RPM]!!, 1.0)
        assertEquals("Errori cancellati.", e.clearDtcs())
        assertEquals(emptyList<String>(), e.dtcs(0x03)!!.map { it.code })
        assertNull(e.freezeFrame())
        assertFalse(ObdParse.status(e.mode01(0x01)!!)!!.mil)
    }
}
