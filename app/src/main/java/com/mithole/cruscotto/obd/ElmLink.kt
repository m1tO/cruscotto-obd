package com.mithole.cruscotto.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Canale verso un ELM327: manda un comando e restituisce il testo ricevuto fino al prompt '>'. */
interface ElmLink {
    suspend fun open()
    suspend fun exchange(cmd: String, timeoutMs: Long): String

    /**
     * Manda un comando che fa parlare l'ELM di continuo (ATMA) e passa ogni riga a [onLine] appena arriva,
     * per [durationMs]; poi lo interrompe e aspetta il prompt. Restituisce false se il dongle si è fermato da solo
     * prima (di solito "BUFFER FULL"), così il chiamante può farlo ripartire.
     */
    suspend fun stream(cmd: String, durationMs: Long, onLine: (String) -> Unit): Boolean

    fun close()
}

private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Parte comune ai dongle veri: un flusso di byte in entrata e uno in uscita, che vengano dal Bluetooth o dal Wi-Fi.
 * Le sottoclassi aprono il canale e impostano [input] e [output].
 */
abstract class StreamLink : ElmLink {
    @Volatile protected var input: InputStream? = null
    @Volatile protected var output: OutputStream? = null

    override suspend fun exchange(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: throw IOException("Dongle non collegato")
        val out = output ?: throw IOException("Dongle non collegato")
        while (inp.available() > 0) inp.read()
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        loop@ while (true) {
            val n = inp.available()
            if (n > 0) {
                val r = inp.read(buf, 0, minOf(n, buf.size))
                if (r < 0) throw IOException("Il dongle ha chiuso il collegamento")
                for (i in 0 until r) {
                    val ch = (buf[i].toInt() and 0xFF).toChar()
                    if (ch == '>') break@loop
                    sb.append(ch)
                }
            } else {
                if (System.currentTimeMillis() > deadline) break@loop
                delay(4)
            }
        }
        sb.toString()
    }

    override suspend fun stream(cmd: String, durationMs: Long, onLine: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val inp = input ?: throw IOException("Dongle non collegato")
        val out = output ?: throw IOException("Dongle non collegato")
        while (inp.available() > 0) inp.read()
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val line = StringBuilder()
        val buf = ByteArray(1024)
        val end = System.currentTimeMillis() + durationMs
        var stopSent = false
        var stopDeadline = 0L
        var prompt = false
        while (!prompt) {
            val now = System.currentTimeMillis()
            if (!stopSent && now >= end) {
                // Qualsiasi carattere interrompe l'ascolto; l'ELM finisce la riga in corso e manda il prompt.
                out.write("\r".toByteArray(Charsets.US_ASCII))
                out.flush()
                stopSent = true
                stopDeadline = now + 2_000
            }
            if (stopSent && now > stopDeadline) throw IOException("Il dongle non smette di ascoltare")
            val n = inp.available()
            if (n == 0) {
                delay(4)
                continue
            }
            val r = inp.read(buf, 0, minOf(n, buf.size))
            if (r < 0) throw IOException("Il dongle ha chiuso il collegamento")
            for (i in 0 until r) {
                val ch = (buf[i].toInt() and 0xFF).toChar()
                if (ch == '>' || ch == '\r' || ch == '\n') {
                    if (line.isNotEmpty()) onLine(line.toString())
                    line.setLength(0)
                    if (ch == '>') {
                        prompt = true
                        break
                    }
                } else {
                    line.append(ch)
                }
            }
        }
        stopSent
    }

    protected fun dropStreams() {
        input = null
        output = null
    }
}

class BluetoothLink(private val adapter: BluetoothAdapter, private val address: String) : StreamLink() {
    @Volatile private var socket: BluetoothSocket? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val device = adapter.getRemoteDevice(address)
        // Niente cancelDiscovery(): l'app non fa scansioni, e da Android 12 quella chiamata vuole
        // BLUETOOTH_SCAN, che non chiediamo. Senza permesso lanciava SecurityException e open() falliva sempre.
        // I cloni ELM327 non accettano tutti lo stesso tipo di socket: si provano tre strade.
        val makers = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (make in makers) {
            val s = try { make() } catch (e: Exception) { last = e; continue }
            try {
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return@withContext
            } catch (e: IOException) {
                last = e
                runCatching { s.close() }
            }
        }
        throw IOException("Non riesco a collegarmi al dongle: è nella presa e il quadro è acceso?", last)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        dropStreams()
    }
}
