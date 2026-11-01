package com.mithole.cruscotto.obd

import java.io.Writer
import java.util.Locale

/** Un messaggio sentito sulla rete CAN: chi lo manda ([id]) e fino a 8 byte di dati. [t] in ms dall'inizio dell'ascolto. */
data class CanFrame(val t: Long, val id: Int, val ext: Boolean, val data: List<Int>)

/**
 * Una finestra d'ascolto: per quanto è durata e quali indirizzi lasciava passare
 * (filtro e maschera dell'ELM327: passa chi ha `id and mask == filter and mask`; maschera 0 = tutti).
 */
data class SniffWindow(val ms: Long, val filter: Int = 0, val mask: Int = 0) {
    fun accepts(id: Int) = (id and mask) == (filter and mask)
}

/** Lettura delle righe che l'ELM327 manda in modalità ascolto (ATMA) con gli header accesi. */
object CanParse {
    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    /**
     * "1A0 01 02 03" (11 bit, con spazi), "18 DB 33 F1 02 01" (29 bit, con spazi),
     * oppure le stesse senza spazi, per i cloni che ignorano ATS1. Tutto il resto (errori, avvisi) è null.
     */
    fun frame(line: String, t: Long): CanFrame? {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || !tokens.all(::isHex)) return null
        val (id, ext, bytes) = when {
            tokens.size == 1 -> {
                val s = tokens[0]
                when {
                    s.length >= 3 && (s.length - 3) % 2 == 0 -> Triple(s.substring(0, 3).toInt(16), false, s.substring(3).chunked(2))
                    s.length >= 8 && s.length % 2 == 0 -> Triple(s.substring(0, 8).toLong(16).toInt(), true, s.substring(8).chunked(2))
                    else -> return null
                }
            }
            tokens[0].length == 3 -> Triple(tokens[0].toInt(16), false, tokens.drop(1))
            tokens.size >= 4 && tokens.take(4).all { it.length == 2 } ->
                Triple(tokens.take(4).joinToString("").toLong(16).toInt(), true, tokens.drop(4))
            else -> return null
        }
        if (bytes.size > 8 || bytes.any { it.length != 2 }) return null
        if (!ext && id > 0x7FF) return null
        return CanFrame(t, id, ext, bytes.map { it.toInt(16) })
    }

    fun idText(id: Int, ext: Boolean) = if (ext) "%08X".format(id) else "%03X".format(id)
}

/** Cosa si sa di un indirizzo dopo l'ascolto. */
class CanStat(val id: Int, val ext: Boolean) {
    var count = 0
        private set
    var dlc = 0
        private set
    var last: List<Int> = emptyList()
        private set
    val min = IntArray(8) { 0xFF }
    val max = IntArray(8)
    /** Quante volte ogni byte è cambiato rispetto al messaggio prima. */
    val changes = IntArray(8)

    fun add(f: CanFrame) {
        f.data.forEachIndexed { i, b ->
            if (b < min[i]) min[i] = b
            if (b > max[i]) max[i] = b
            if (count > 0 && i < last.size && last[i] != b) changes[i]++
        }
        dlc = maxOf(dlc, f.data.size)
        last = f.data
        count++
    }

    fun varies(i: Int) = i < dlc && min[i] != max[i]
}

/**
 * Raccoglie i messaggi sentiti: statistiche per indirizzo e, se c'è [log], ogni messaggio
 * su una riga ("ms indirizzo byte…") per l'analisi dopo.
 */
class Sniffer(private val log: Writer? = null) {
    val stats = sortedMapOf<Long, CanStat>()
    val windows = mutableListOf<SniffWindow>()
    var frames = 0
        private set
    /** Volte in cui il dongle non ha retto il traffico ("BUFFER FULL"): i messaggi di quel momento sono persi. */
    var overflows = 0
        private set
    /** Righe illeggibili: messaggi spezzati o errori di ricezione. */
    var garbled = 0
        private set

    fun line(text: String, t: Long) {
        val s = text.trim()
        if (s.isEmpty() || s == ">") return
        if (s.contains("BUFFER FULL", ignoreCase = true)) {
            overflows++
            return
        }
        val f = CanParse.frame(s, t)
        if (f == null) {
            garbled++
            return
        }
        frames++
        val key = (if (f.ext) 1L shl 32 else 0L) or (f.id.toLong() and 0xFFFFFFFFL)
        stats.getOrPut(key) { CanStat(f.id, f.ext) }.add(f)
        log?.write("${f.t} ${CanParse.idText(f.id, f.ext)} ${f.data.joinToString(" ") { "%02X".format(it) }}\n")
    }

    fun window(w: SniffWindow) {
        windows += w
    }

    /** Messaggi al secondo di un indirizzo, contando solo le finestre in cui poteva passare. Per difetto se il dongle ha perso dati. */
    fun rate(s: CanStat): Double {
        val ms = windows.filter { it.accepts(s.id) }.sumOf { it.ms }
        return if (ms > 0) s.count * 1000.0 / ms else 0.0
    }

    /** Il riassunto da copiare: un indirizzo per riga, con i byte che cambiano e tra quali valori. */
    fun summary(header: List<String>): String = buildString {
        header.forEach { appendLine(it) }
        appendLine("Messaggi: $frames · indirizzi: ${stats.size} · dongle saturo: $overflows volte · righe illeggibili: $garbled")
        appendLine("Formato: indirizzo · messaggi/s · byte · ultimo valore · byte che cambiano [min-max, cambi]")
        for (s in stats.values) {
            val last = s.last.joinToString(" ") { "%02X".format(it) }
            val vary = (0 until s.dlc).filter(s::varies).joinToString(" ") { i ->
                "B$i[%02X-%02X,%d]".format(s.min[i], s.max[i], s.changes[i])
            }
            append(CanParse.idText(s.id, s.ext)).append("  ")
            append(String.format(Locale.US, "%.1f/s", rate(s))).append("  ")
            append("${s.dlc}B  ").append(last)
            append("  ").append(vary.ifEmpty { "fisso" })
            appendLine()
        }
    }
}
