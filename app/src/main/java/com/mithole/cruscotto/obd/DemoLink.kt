package com.mithole.cruscotto.obd

import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Un ELM327 finto collegato a un'auto finta: risponde agli stessi comandi del dongle vero,
 * così la modalità demo passa dallo stesso codice di lettura e decodifica.
 */
class DemoLink : ElmLink {
    private val t0 = System.currentTimeMillis()
    private val stored = mutableListOf("P0299", "P0133")
    private val pending = mutableListOf("P0300")
    private var frozenDtc: String? = "P0299"
    private var cleared = false

    private val supported = setOf(
        0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x20,
        0x33, 0x40, 0x46, 0x49,
    )

    private class Sim(
        val rpm: Double, val speed: Double, val map: Double, val pedal: Double, val coolant: Double,
        val iat: Double, val timing: Double, val maf: Double, val load: Double, val stft: Double,
    )

    // Le condizioni "congelate" quando è comparso l'errore del turbo.
    private val frozen = Sim(3150.0, 74.0, 148.0, 0.82, 88.0, 34.0, 9.5, 34.6, 67.0, 3.1)

    override suspend fun open() = delay(300)

    override fun close() = Unit

    override suspend fun exchange(cmd: String, timeoutMs: Long): String {
        delay(22)
        return respond(cmd.trim().uppercase()) + "\r\r"
    }

    private fun seconds() = (System.currentTimeMillis() - t0) / 1000.0

    private fun sim(): Sim {
        val t = seconds()
        val p = t % 40.0
        val (speed, pedal) = when {
            p < 4 -> 0.0 to 0.0
            p < 16 -> { val k = (p - 4) / 12; 128 * (1 - (1 - k) * (1 - k)) to 0.85 }
            p < 28 -> (118 + 6 * sin(p * 1.3)) to (0.22 + 0.06 * sin(p))
            else -> 118 * (1 - (p - 28) / 12) to 0.0
        }
        val gear = when {
            speed < 1 -> 0
            speed < 20 -> 1
            speed < 38 -> 2
            speed < 58 -> 3
            speed < 80 -> 4
            speed < 104 -> 5
            else -> 6
        }
        val ratio = doubleArrayOf(0.0, 130.0, 72.0, 50.0, 39.0, 31.0, 26.0)
        val rpm = max(820 + 15 * sin(t * 7), speed * ratio[gear]).coerceAtMost(6400.0)
        val boost = if (pedal > 0.5 && rpm > 1700) 118 * pedal * min(1.0, (rpm - 1700) / 1300) else 0.0
        val map = 101 * (0.33 + 0.67 * pedal) + boost + 1.5 * sin(t * 5)
        return Sim(
            rpm = rpm,
            speed = speed,
            map = map,
            pedal = pedal,
            coolant = min(90.0, 45 + t * 0.4) + 0.5 * sin(t / 3),
            iat = 31 + 4 * pedal + sin(t / 7),
            timing = if (pedal > 0.5) 6 + 4 * sin(t) else 28 - 10 * pedal,
            maf = 0.0075 * rpm * map / 101,
            load = (map / 220 * 100).coerceIn(15.0, 100.0),
            stft = 2 * sin(t * 2),
        )
    }

    // Stato dell'ascolto della rete: header, filtro e maschera come li imposta l'app.
    private var headers = false
    private var canFilter = 0
    private var canMask = 0
    private val nextDue = HashMap<Int, Long>()

    /** I messaggi che le centraline finte si scambiano: indirizzo e ogni quanti millisecondi. */
    private val broadcast = listOf(0x0A0 to 20L, 0x0FA to 25L, 0x101 to 100L, 0x2FA to 50L, 0x3E8 to 1000L, 0x4C0 to 500L, 0x6F1 to 200L)
    private var counter = 0

    override suspend fun stream(cmd: String, durationMs: Long, onLine: (String) -> Unit): Boolean {
        if (cmd.trim().uppercase() != "ATMA") {
            onLine("?")
            return false
        }
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            // Un dongle vero manda circa un messaggio ogni 8 ms: di più non passa dal Bluetooth.
            delay(8)
            val now = System.currentTimeMillis()
            val (id, period) = broadcast
                .filter { (id, _) -> (id and canMask) == (canFilter and canMask) }
                .minByOrNull { (id, _) -> nextDue[id] ?: 0L } ?: continue
            if ((nextDue[id] ?: 0L) > now) continue
            nextDue[id] = now + period
            val data = frame(id, sim())
            onLine((if (headers) "%03X ".format(id) else "") + data.joinToString(" ") { "%02X".format(it) })
        }
        return true
    }

    private fun frame(id: Int, s: Sim): List<Int> {
        counter = (counter + 1) and 0x0F
        fun hi(v: Int) = (v shr 8) and 0xFF
        fun lo(v: Int) = v and 0xFF
        val rpm = s.rpm.roundToInt()
        val boostMbar = ((s.map - 101) * 10).roundToInt() + 1000
        val speed = (s.speed * 100).roundToInt()
        return when (id) {
            0x0A0 -> listOf(hi(rpm), lo(rpm), (s.pedal * 250).roundToInt(), (s.load * 2.5).roundToInt().coerceAtMost(255), 0x00, 0x00, 0x10, counter)
            0x0FA -> listOf(hi(boostMbar), lo(boostMbar), (s.timing + 64).roundToInt(), 0x00, counter shl 4)
            0x101 -> listOf((s.coolant + 40).roundToInt(), (s.coolant - 6 + 40).roundToInt(), (s.iat + 40).roundToInt(), 0x00, 0x00, 0x3C, 0x00, 0x00)
            0x2FA -> listOf(hi(speed), lo(speed), hi(speed), lo(speed), 0x00, 0x00, counter)
            0x3E8 -> listOf(0x01, 0x86, 0xA0, 0x2C, 0x00, 0x00)
            0x4C0 -> listOf(0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00)
            else -> listOf(0x02, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        }
    }

    private fun respond(c: String): String {
        if (c.startsWith("AT")) {
            when {
                c == "ATH1" -> headers = true
                c == "ATH0" -> headers = false
                c == "ATCRA" -> { canFilter = 0; canMask = 0 }
                c.startsWith("ATCF") -> canFilter = c.substring(4).toIntOrNull(16) ?: 0
                c.startsWith("ATCM") -> canMask = c.substring(4).toIntOrNull(16) ?: 0
            }
            return when (c) {
                "ATZ" -> "\r\rELM327 v1.5"
                "ATI" -> "ELM327 v1.5"
                "ATDPN" -> "A6"
                "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
                "ATRV" -> String.format(Locale.US, "%.1fV", 14.1 + 0.1 * sin(seconds() / 3))
                else -> "OK"
            }
        }
        return when {
            c.startsWith("01") && c.length >= 4 -> {
                val pid = c.substring(2, 4).toInt(16)
                mode01(pid, sim())?.let { hex(listOf(0x41, pid) + it) } ?: "NO DATA"
            }
            c.startsWith("02") && c.length >= 6 -> {
                val pid = c.substring(2, 4).toInt(16)
                val data = if (pid == 0x02) frozenDtc?.let(DtcCodec::encode) ?: listOf(0, 0) else mode01(pid, frozen)
                data?.let { hex(listOf(0x42, pid, 0x00) + it) } ?: "NO DATA"
            }
            c == "03" -> isotp(listOf(0x43, stored.size) + stored.flatMap(DtcCodec::encode))
            c == "07" -> isotp(listOf(0x47, pending.size) + pending.flatMap(DtcCodec::encode))
            c == "04" -> {
                stored.clear(); pending.clear(); frozenDtc = null; cleared = true
                "44"
            }
            c == "0902" -> isotp(listOf(0x49, 0x02, 0x01) + VIN.map { it.code })
            else -> "NO DATA"
        }
    }

    private fun mode01(pid: Int, s: Sim): List<Int>? {
        fun pct(v: Double) = (v * 255 / 100).roundToInt().coerceIn(0, 255)
        fun temp(v: Double) = (v + 40).roundToInt().coerceIn(0, 255)
        return when (pid) {
            0x00, 0x20, 0x40 -> bitmap(pid)
            0x01 -> {
                val a = (if (stored.isNotEmpty()) 0x80 else 0) or stored.size
                val c = 0x65 // catalizzatore, evaporativo, sonda lambda, riscaldatore sonda
                listOf(a, if (cleared) 0x77 else 0x07, c, if (cleared) c else 0x04)
            }
            0x04 -> listOf(pct(s.load))
            0x05 -> listOf(temp(s.coolant))
            0x06 -> listOf((128 + s.stft * 128 / 100).roundToInt())
            0x07 -> listOf((128 + 3.1 * 128 / 100).roundToInt())
            0x0B -> listOf(s.map.roundToInt().coerceIn(0, 255))
            0x0C -> (s.rpm * 4).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x0D -> listOf(s.speed.roundToInt())
            0x0E -> listOf(((s.timing + 64) * 2).roundToInt())
            0x0F -> listOf(temp(s.iat))
            0x10 -> (s.maf * 100).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x11 -> listOf(pct(100.0))
            0x33 -> listOf(101)
            0x46 -> listOf(temp(19.0))
            0x49 -> listOf(pct(s.pedal * 100))
            else -> null
        }
    }

    private fun bitmap(base: Int): List<Int> {
        val bytes = IntArray(4)
        for (i in 0 until 32) if (base + i + 1 in supported) bytes[i / 8] = bytes[i / 8] or (0x80 shr (i % 8))
        return bytes.toList()
    }

    private fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02X".format(it) }

    /** Formatta come fa l'ELM con gli header spenti: riga singola, oppure lunghezza + righe numerate. */
    private fun isotp(payload: List<Int>): String {
        if (payload.size <= 7) return hex(payload)
        val out = StringBuilder("%03X".format(payload.size))
        out.append("\r0:").append(hex(payload.take(6)))
        var idx = 1
        var pos = 6
        while (pos < payload.size) {
            val chunk = payload.drop(pos).take(7)
            out.append("\r").append("%X".format(idx and 0xF)).append(":").append(hex(chunk + List(7 - chunk.size) { 0 }))
            pos += 7
            idx++
        }
        return out.toString()
    }

    companion object {
        const val VIN = "DEMOCRUSCOTTO0001"
    }
}
