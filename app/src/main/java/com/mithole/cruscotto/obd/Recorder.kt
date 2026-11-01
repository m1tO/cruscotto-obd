package com.mithole.cruscotto.obd

import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Il GPS del tablet: manca il permesso, manca proprio il ricevitore (molti tablet solo Wi-Fi), oppure c'è. */
enum class GpsStatus { NO_PERMISSION, NO_HARDWARE, ON }

/** Una posizione del GPS del tablet. [kmh] manca quando il GPS non stima la velocità. */
data class GpsFix(val lat: Double, val lon: Double, val kmh: Double?, val accuracy: Float, val t: Long)

data class PathPoint(val lat: Double, val lon: Double, val kmh: Float)

/** Una registrazione salvata. */
data class Recording(val file: File, val startedAt: Long, val seconds: Double, val bytes: Long)

/** Una registrazione riletta dal file: i valori nel tempo, con t in millisecondi dall'inizio. */
data class Track(val seconds: Double, val series: Map<Metric, List<Sample>>, val path: List<PathPoint> = emptyList())

/**
 * Registra ogni giro di letture in un CSV che si apre direttamente con Excel: in italiano con punto e virgola
 * e virgola decimale, in inglese con virgola e punto decimale. Il formato si sceglie all'inizio di ogni file
 * e la rilettura lo riconosce dall'intestazione. I file stanno nella memoria dell'app finché non si esportano.
 */
class Recorder(private val dir: File) {
    private var writer: BufferedWriter? = null
    private var t0 = 0L
    private var lastFlush = 0L
    private var sep = ';'
    private var comma = true

    val active: Boolean @Synchronized get() = writer != null

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()) {
        if (writer != null) return
        dir.mkdirs()
        val name = "giro_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ITALY).format(Date(now)) + ".csv"
        val w = File(dir, name).bufferedWriter(Charsets.UTF_8)
        // BOM: senza, Excel legge male le lettere accentate.
        w.write(0xFEFF)
        comma = !L.en
        sep = if (comma) ';' else ','
        val head = listOf(t("Secondi", "Seconds")) + Metric.entries.map { "${it.label} (${it.unit})" } +
            listOf(t("Marcia", "Gear"), t("Latitudine", "Latitude"), t("Longitudine", "Longitude"), t("Velocità GPS (km/h)", "GPS speed (km/h)"))
        w.write(head.joinToString(sep.toString()))
        w.newLine()
        writer = w
        t0 = now
        lastFlush = now
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.close() }
        writer = null
    }

    @Synchronized
    fun write(now: Long, values: Map<Metric, Double>, gear: Int?, gps: GpsFix? = null) {
        val w = writer ?: return
        val row = ArrayList<String>(Metric.entries.size + 5)
        row += num((now - t0) / 1000.0, 2)
        Metric.entries.forEach { m -> row += values[m]?.let { num(it, m.decimals + 1) } ?: "" }
        row += gear?.toString() ?: ""
        row += gps?.let { num(it.lat, 6) } ?: ""
        row += gps?.let { num(it.lon, 6) } ?: ""
        row += gps?.kmh?.let { num(it, 1) } ?: ""
        runCatching {
            w.write(row.joinToString(sep.toString()))
            w.newLine()
            if (now - lastFlush > 5_000) {
                w.flush()
                lastFlush = now
            }
        }.onFailure { stop() }
    }

    fun list(): List<Recording> =
        (dir.listFiles { f -> f.name.endsWith(".csv") } ?: emptyArray())
            .map { f -> Recording(f, startOf(f), lastSeconds(f), f.length()) }
            .sortedByDescending { it.startedAt }

    private fun num(v: Double, decimals: Int): String {
        val s = String.format(Locale.US, "%.${decimals}f", v)
        return if (comma) s.replace('.', ',') else s
    }

    companion object {
        /** Prima colonna di una riga come numero, in tutti e due i formati. */
        private fun firstCell(line: String): Double? =
            if (';' in line) line.substringBefore(';').replace(',', '.').toDoubleOrNull() else line.substringBefore(',').toDoubleOrNull()

        private fun startOf(f: File): Long =
            runCatching { SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ITALY).parse(f.name.removePrefix("giro_").removeSuffix(".csv"))!!.time }
                .getOrDefault(f.lastModified())

        /** Durata dalla prima colonna dell'ultima riga, senza leggere tutto il file. */
        private fun lastSeconds(f: File): Double = runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val from = maxOf(0L, len - 2048)
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                val last = String(buf, Charsets.UTF_8).trimEnd().substringAfterLast('\n')
                firstCell(last) ?: 0.0
            }
        }.getOrDefault(0.0)

        fun read(f: File): Track {
            val series = HashMap<Metric, ArrayList<Sample>>()
            val path = ArrayList<PathPoint>()
            val base = Metric.entries.size + 2
            var secs = 0.0
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var sep = ';'
                lines.forEachIndexed { n, line ->
                    // L'intestazione dice il formato: col punto e virgola è quello italiano.
                    if (n == 0) {
                        sep = if (';' in line) ';' else ','
                        return@forEachIndexed
                    }
                    val cells = line.split(sep)
                    val t = cells.getOrNull(0)?.replace(',', '.')?.toDoubleOrNull() ?: return@forEachIndexed
                    secs = t
                    val ms = (t * 1000).toLong()
                    Metric.entries.forEachIndexed { i, m ->
                        cells.getOrNull(i + 1)?.replace(',', '.')?.toFloatOrNull()?.let { v ->
                            series.getOrPut(m) { ArrayList() } += Sample(ms, v)
                        }
                    }
                    val lat = cells.getOrNull(base)?.replace(',', '.')?.toDoubleOrNull()
                    val lon = cells.getOrNull(base + 1)?.replace(',', '.')?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        val kmh = cells.getOrNull(Metric.entries.indexOf(Metric.SPEED) + 1)?.replace(',', '.')?.toFloatOrNull() ?: 0f
                        path += PathPoint(lat, lon, kmh)
                    }
                }
            }
            return Track(secs, series, path)
        }
    }
}
