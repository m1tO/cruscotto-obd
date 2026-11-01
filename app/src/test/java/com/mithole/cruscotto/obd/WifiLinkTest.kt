package com.mithole.cruscotto.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class WifiLinkTest {
    @Test
    fun hostPort() {
        assertEquals("192.168.0.10" to 35000, parseHostPort("192.168.0.10"))
        assertEquals("192.168.0.123" to 23, parseHostPort(" 192.168.0.123:23 "))
        assertNull(parseHostPort(""))
        assertNull(parseHostPort(null))
        assertNull(parseHostPort("192.168.0.10:abc"))
        assertNull(parseHostPort("192.168.0.10:70000"))
        assertNull(parseHostPort(":35000"))
    }

    /** Lo stesso lettore del Bluetooth, ma su un socket TCP vero verso un ELM finto: come un dongle Wi-Fi. */
    private class TcpLink(private val port: Int) : StreamLink() {
        private var s: Socket? = null
        override suspend fun open() {
            val sock = Socket(InetAddress.getLoopbackAddress(), port)
            s = sock
            input = sock.getInputStream()
            output = sock.getOutputStream()
        }
        override fun close() {
            runCatching { s?.close() }
            dropStreams()
        }
    }

    @Test
    fun tcpExchange() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val elm = thread {
            server.accept().use { c ->
                val inp = c.getInputStream().bufferedReader(Charsets.US_ASCII)
                val out = c.getOutputStream()
                val cmd = StringBuilder()
                while (true) {
                    val ch = inp.read()
                    if (ch < 0) break
                    if (ch.toChar() != '\r') { cmd.append(ch.toChar()); continue }
                    // Il dongle vero spezza le risposte in più pacchetti: le mandiamo a pezzi.
                    val reply = when (cmd.toString()) {
                        "ATZ" -> "\r\rELM327 v1.5\r\r>"
                        "010C" -> "41 0C 1A F8 \r\r>"
                        else -> "?\r\r>"
                    }
                    cmd.setLength(0)
                    reply.chunked(4).forEach { out.write(it.toByteArray(Charsets.US_ASCII)); out.flush(); Thread.sleep(5) }
                }
            }
        }
        val link = TcpLink(server.localPort)
        link.open()
        assertTrue(link.exchange("ATZ", 2_000).contains("ELM327 v1.5"))
        assertEquals("41 0C 1A F8", link.exchange("010C", 2_000).trim())
        link.close()
        elm.join(2_000)
        server.close()
    }
}
