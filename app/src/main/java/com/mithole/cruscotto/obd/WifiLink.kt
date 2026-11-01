package com.mithole.cruscotto.obd

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/** Porta quasi universale dei dongle ELM327 Wi-Fi. */
const val WIFI_PORT = 35000

/** Gli indirizzi più comuni dei dongle Wi-Fi, provati dopo quello del router della rete. */
private val COMMON_HOSTS = listOf("192.168.0.10", "192.168.0.123", "192.168.4.1")

/** "192.168.0.10:35000" o "192.168.0.10" → host e porta; vuoto o malformato → null. */
fun parseHostPort(text: String?): Pair<String, Int>? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    val host = t.substringBefore(':').trim()
    if (host.isEmpty()) return null
    val port = if (':' in t) t.substringAfter(':').trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null else WIFI_PORT
    return host to port
}

/**
 * Dongle ELM327 Wi-Fi: il tablet sta sulla rete che crea il dongle e ci si parla in TCP.
 * Quella rete non ha internet, e Android tende a ignorarla: il socket va legato esplicitamente a lei,
 * altrimenti con un'altra rete disponibile il collegamento uscirebbe dalla parte sbagliata.
 *
 * Si prova prima [manual] (scritto a mano o ricordato dall'ultima volta), poi il router della rete
 * (quasi sempre è il dongle stesso) e infine gli indirizzi più comuni. [onConnected] riceve "host:porta" riuscito.
 */
class WifiLink(
    private val cm: ConnectivityManager,
    private val manual: String?,
    private val onConnected: (String) -> Unit = {},
) : StreamLink() {
    @Volatile private var socket: Socket? = null

    override suspend fun open() {
        val network = findWifi()
            ?: throw IOException("Il tablet non è collegato a nessun Wi-Fi: collegalo alla rete del dongle dalle impostazioni Wi-Fi.")
        val targets = candidates(network)
        withContext(Dispatchers.IO) {
            var last: Exception? = null
            for ((host, port) in targets) {
                val s = Socket()
                try {
                    network.bindSocket(s)
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 2_500)
                    socket = s
                    input = s.getInputStream()
                    output = s.getOutputStream()
                    onConnected("$host:$port")
                    return@withContext
                } catch (e: IOException) {
                    last = e
                    runCatching { s.close() }
                }
            }
            val tried = targets.joinToString(", ") { "${it.first}:${it.second}" }
            throw IOException("Nessun dongle risponde ($tried). Il tablet è sulla rete Wi-Fi del dongle, non su quella di casa?", last)
        }
    }

    /** La rete Wi-Fi a cui il tablet è collegato, anche se senza internet; null se non ce n'è nessuna entro 3 s. */
    private suspend fun findWifi(): Network? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            return withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) cont.resume(network)
                        }
                    }
                    callback = cb
                    cm.registerNetworkCallback(request, cb)
                }
            }
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    private fun candidates(network: Network): List<Pair<String, Int>> {
        val lp = cm.getLinkProperties(network)
        val gateways = lp?.routes.orEmpty().filter { it.hasGateway() }.mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }
        val dhcp = (lp?.dhcpServerAddress as? Inet4Address)?.hostAddress
        // Prima l'indirizzo scritto o ricordato, poi gli altri: se il dongle è cambiato si trova lo stesso.
        return (listOfNotNull(parseHostPort(manual)) + (gateways + listOfNotNull(dhcp) + COMMON_HOSTS).map { it to WIFI_PORT }).distinct()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        dropStreams()
    }
}
