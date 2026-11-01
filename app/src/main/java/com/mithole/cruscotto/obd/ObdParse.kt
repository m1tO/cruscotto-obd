package com.mithole.cruscotto.obd

data class Monitor(val name: String, val complete: Boolean)

/** Risposta al PID 01 01: spia motore, numero di errori e test di autodiagnosi. */
data class Status(val mil: Boolean, val dtcCount: Int, val monitors: List<Monitor>, val diesel: Boolean = false)

/** Funzioni pure che trasformano il testo dell'ELM327 in byte e valori. */
object ObdParse {
    private val frameLine = Regex("^([0-9A-Fa-f]):\\s*(.*)$")

    fun lines(raw: String, cmd: String): List<String> =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot {
                it.equals(cmd, ignoreCase = true) ||
                    it.startsWith("SEARCHING", ignoreCase = true) ||
                    (it.startsWith("BUS INIT", ignoreCase = true) && !it.contains("ERROR", ignoreCase = true))
            }

    fun isError(line: String): Boolean {
        val u = line.uppercase()
        return u == "?" || u == "STOPPED" || "NO DATA" in u || "UNABLE" in u || "ERROR" in u || "BUFFER FULL" in u
    }

    fun hexBytes(s: String): List<Int>? {
        val h = s.replace(" ", "")
        if (h.isEmpty() || h.length % 2 != 0 || !h.all { it.isHex() }) return null
        return List(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    /**
     * Ricompone i messaggi: una riga per i frame singoli; per i messaggi lunghi su CAN
     * (header spenti) l'ELM manda prima la lunghezza ("00A") e poi le righe "0:", "1:", ...
     */
    fun messages(lines: List<String>): List<List<Int>> {
        class Msg(val expected: Int) { val data = mutableListOf<Int>() }
        val out = mutableListOf<Msg>()
        var multi: Msg? = null
        for (line in lines) {
            if (isError(line)) continue
            val compact = line.replace(" ", "")
            val frame = frameLine.matchEntire(line.trim())
            when {
                frame != null -> {
                    val idx = frame.groupValues[1].toInt(16)
                    val current = multi
                    val target = if (current == null || (idx == 0 && current.data.isNotEmpty())) {
                        Msg(-1).also { out += it; multi = it }
                    } else current
                    hexBytes(frame.groupValues[2])?.let { target.data.addAll(it) }
                }
                compact.length == 3 && compact.all { it.isHex() } -> {
                    multi = Msg(compact.toInt(16)).also { out += it }
                }
                else -> {
                    hexBytes(compact)?.let { bytes -> out += Msg(-1).also { it.data.addAll(bytes) } }
                    multi = null
                }
            }
        }
        return out.map { m -> if (m.expected in 1 until m.data.size) m.data.take(m.expected) else m.data.toList() }
            .filter { it.isNotEmpty() }
    }

    fun mode01(lines: List<String>, pid: Int): List<Int>? =
        messages(lines).firstOrNull { it.size >= 2 && it[0] == 0x41 && it[1] == pid }?.drop(2)

    /** PID supportati a partire dalla bitmap restituita da 01 00 / 01 20 / 01 40 ... */
    fun bitmap(base: Int, data: List<Int>): Set<Int> = buildSet {
        for (i in 0 until 32) {
            val byte = data.getOrNull(i / 8) ?: break
            if ((byte shr (7 - i % 8)) and 1 == 1) add(base + i + 1)
        }
    }

    /** Codici di errore dai modi 03 / 07 / 0A. Su CAN il primo byte dopo il modo è il numero di codici. */
    fun dtcs(messages: List<List<Int>>, responseMode: Int, isCan: Boolean): List<String> {
        val codes = mutableListOf<String>()
        for (msg in messages) {
            if (msg.isEmpty() || msg[0] != responseMode) continue
            val body = if (isCan) msg.drop(2) else msg.drop(1)
            val pairs = if (isCan) minOf(msg.getOrElse(1) { 0 }, body.size / 2) else body.size / 2
            for (i in 0 until pairs) {
                val a = body[i * 2]
                val b = body[i * 2 + 1]
                if (a == 0 && b == 0) continue
                codes += DtcCodec.decode(a, b)
            }
        }
        return codes.distinct()
    }

    fun status(d: List<Int>): Status? {
        if (d.size < 4) return null
        val (a, b, c, dd) = d
        val diesel = (b and 0x08) != 0
        val monitors = mutableListOf<Monitor>()
        listOf(t("Mancate accensioni", "Misfire"), t("Sistema carburante", "Fuel system"), t("Componenti", "Components")).forEachIndexed { i, name ->
            if ((b shr i) and 1 == 1) monitors += Monitor(name, (b shr (i + 4)) and 1 == 0)
        }
        val names = if (diesel) {
            listOf(t("Catalizzatore NMHC", "NMHC catalyst"), t("Riduzione NOx", "NOx reduction"), null, t("Pressione turbo", "Boost pressure"), null,
                t("Sensore gas di scarico", "Exhaust gas sensor"), t("Filtro antiparticolato", "Particulate filter"), t("EGR / fasatura variabile", "EGR / variable timing"))
        } else {
            listOf(t("Catalizzatore", "Catalyst"), t("Catalizzatore riscaldato", "Heated catalyst"), t("Sistema evaporativo", "Evaporative system"), t("Aria secondaria", "Secondary air"),
                t("Gas del climatizzatore", "A/C refrigerant"), t("Sonda lambda", "O2 sensor"), t("Riscaldatore sonda lambda", "O2 sensor heater"), t("EGR / fasatura variabile", "EGR / variable timing"))
        }
        names.forEachIndexed { i, name ->
            if (name != null && (c shr i) and 1 == 1) monitors += Monitor(name, (dd shr i) and 1 == 0)
        }
        return Status(mil = (a and 0x80) != 0, dtcCount = a and 0x7F, monitors = monitors, diesel = diesel)
    }
}
