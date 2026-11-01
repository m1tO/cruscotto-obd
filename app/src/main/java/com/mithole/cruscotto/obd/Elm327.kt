package com.mithole.cruscotto.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class ObdException(message: String) : IOException(message)

data class FreezeFrame(val dtc: Dtc, val values: Map<Metric, Double>)

/** Dialogo con l'ELM327: un comando alla volta, anche se lo chiamano più coroutine insieme. */
class Elm327(private val link: ElmLink) {
    private val mutex = Mutex()
    private var countSuffix = false
    private var blanks = 0

    var isCan = true
        private set
    var version = "ELM327"
        private set
    var protocol = "?"
        private set
    /** Rete CAN con indirizzi a 11 bit (la più comune): lì l'ascolto si può dividere per gruppi di indirizzi. */
    var is11bit = false
        private set

    suspend fun raw(cmd: String, timeoutMs: Long = 1500): List<String> = mutex.withLock {
        val text = link.exchange(cmd, timeoutMs)
        if (text.isBlank()) {
            if (++blanks >= 6) throw ObdException(t("Il dongle non risponde più", "The dongle stopped responding"))
        } else {
            blanks = 0
        }
        ObdParse.lines(text, cmd)
    }

    suspend fun init(step: (String) -> Unit) {
        step(t("Preparo il dongle…", "Setting up the dongle…"))
        raw("ATZ", 4000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) raw(c)
        version = raw("ATI").firstOrNull { it.contains("ELM", ignoreCase = true) } ?: "ELM327"
        step(t("Cerco l'auto: il quadro deve essere acceso…", "Looking for the car: the ignition must be on…"))
        val first = raw("0100", 15000)
        if (ObdParse.mode01(first, 0x00) == null) {
            val txt = first.joinToString(" ")
            throw ObdException(
                when {
                    txt.contains("UNABLE", true) || txt.contains("NO DATA", true) ->
                        t("Il dongle risponde, l'auto no: accendi il quadro.", "The dongle answers, the car doesn't: switch the ignition on.")
                    txt.isBlank() -> t("Il dongle non risponde.", "The dongle isn't responding.")
                    else -> t("Risposta inattesa dall'auto: $txt", "Unexpected reply from the car: $txt")
                }
            )
        }
        protocol = raw("ATDP").firstOrNull()?.removePrefix("AUTO, ")?.removePrefix("AUTO,") ?: "?"
        val dpn = raw("ATDPN").firstOrNull().orEmpty().trim().removePrefix("A")
        isCan = dpn in setOf("6", "7", "8", "9")
        is11bit = dpn == "6" || dpn == "8"
        // "01001": il numero finale dice all'ELM di fermarsi alla prima risposta, ed è molto più veloce.
        // Alcuni cloni non lo capiscono: in quel caso si torna al comando normale.
        countSuffix = ObdParse.mode01(raw("01001"), 0x00) != null
    }

    suspend fun mode01(pid: Int): List<Int>? {
        val cmd = "01%02X".format(pid)
        return ObdParse.mode01(raw(if (countSuffix) cmd + "1" else cmd), pid)
    }

    suspend fun voltage(): Double? =
        raw("ATRV").firstOrNull()?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

    suspend fun supportedPids(): Set<Int> {
        val set = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val msgs = ObdParse.messages(raw("01%02X".format(base), 4000))
                .filter { it.size >= 6 && it[0] == 0x41 && it[1] == base }
            if (msgs.isEmpty()) break
            msgs.forEach { set += ObdParse.bitmap(base, it.drop(2)) }
            if ((base + 0x20) !in set) break
            base += 0x20
        }
        return set
    }

    /** Errori del modo indicato (03 memorizzati, 07 in attesa, 0A permanenti); null se l'auto non risponde. */
    suspend fun dtcs(mode: Int): List<Dtc>? {
        val msgs = ObdParse.messages(raw("%02X".format(mode), 6000))
        if (msgs.none { it.firstOrNull() == mode + 0x40 }) return null
        return ObdParse.dtcs(msgs, mode + 0x40, isCan).map(::Dtc)
    }

    suspend fun freezeFrame(): FreezeFrame? {
        val d = mode02(0x02) ?: return null
        if (d.size < 2 || (d[0] == 0 && d[1] == 0)) return null
        val values = linkedMapOf<Metric, Double>()
        for (pid in listOf(0x0C, 0x0D, 0x05, 0x04, 0x0B, 0x0F, 0x11, 0x0E, 0x06, 0x07)) {
            val data = mode02(pid) ?: continue
            Pids.decode(pid, data)?.let { (m, v) -> values[m] = v }
        }
        return FreezeFrame(Dtc(DtcCodec.decode(d[0], d[1])), values)
    }

    private suspend fun mode02(pid: Int): List<Int>? =
        ObdParse.messages(raw("02%02X00".format(pid), 3000))
            .firstOrNull { it.size >= 3 && it[0] == 0x42 && it[1] == pid }
            ?.drop(3)

    suspend fun vin(): String? {
        val msgs = ObdParse.messages(raw("0902", 6000)).filter { it.size > 3 && it[0] == 0x49 && it[1] == 0x02 }
        if (msgs.isEmpty()) return null
        val bytes = if (isCan) msgs.first().drop(3) else msgs.flatMap { it.drop(3) }
        val s = bytes.filter { it in 0x21..0x7E }.map { it.toChar() }.joinToString("")
        return s.takeLast(17).takeIf { it.length == 17 }
    }

    suspend fun clearDtcs(): String {
        val lines = raw("04", 8000)
        val msgs = ObdParse.messages(lines)
        return when {
            msgs.any { it.firstOrNull() == 0x44 } -> t("Errori cancellati.", "Faults cleared.")
            msgs.any { it.size >= 3 && it[0] == 0x7F && it[1] == 0x04 && it[2] == 0x22 } ->
                t("La centralina ha rifiutato: spegni il motore, lascia il quadro acceso e riprova.", "The ECU refused: switch the engine off, leave the ignition on and try again.")
            else -> t("La centralina non ha confermato la cancellazione (${lines.joinToString(" ")}).", "The ECU didn't confirm the clearing (${lines.joinToString(" ")}).")
        }
    }

    /**
     * Ascolta la rete senza fare domande: le centraline si scambiano messaggi di continuo e l'ELM li ripete.
     * Le finestre di [plan] si fanno in ordine, ognuna col suo filtro; se il dongle si satura ("BUFFER FULL")
     * l'ascolto riparte finché la finestra non è finita. [t0] è l'istante da cui contare i millisecondi dei messaggi.
     */
    suspend fun sniff(sniffer: Sniffer, plan: List<SniffWindow>, t0: Long, onWindow: (Int) -> Unit) {
        if (!isCan) throw ObdException(t("L'ascolto funziona solo con le auto su rete CAN; quest'auto usa $protocol.", "Listening only works on cars with a CAN bus; this car uses $protocol."))
        if (!accepted("ATH1")) throw ObdException(t("Il dongle non sa mostrare gli indirizzi dei messaggi (ATH1).", "The dongle can't show message IDs (ATH1)."))
        raw("ATS1")
        raw("ATCAF0")
        // I filtri per indirizzo non li hanno tutti i cloni: senza, si ascolta tutto in ogni finestra.
        val filters = is11bit && accepted("ATCF000") && accepted("ATCM000")
        plan.forEachIndexed { i, w ->
            onWindow(i)
            val real = if (w.mask != 0 && filters) {
                raw("ATCF%03X".format(w.filter))
                raw("ATCM%03X".format(w.mask))
                w
            } else {
                if (filters) raw("ATCM000")
                SniffWindow(w.ms)
            }
            val start = System.currentTimeMillis()
            var first = true
            while (true) {
                val left = w.ms - (System.currentTimeMillis() - start)
                if (left < 150) break
                var refused = false
                mutex.withLock {
                    link.stream("ATMA", left) { line ->
                        if (first && line.trim() == "?") refused = true
                        first = false
                        sniffer.line(line, System.currentTimeMillis() - t0)
                    }
                }
                if (refused && sniffer.frames == 0) throw ObdException(t("Il dongle non sa ascoltare la rete (comando ATMA rifiutato).", "The dongle can't listen to the bus (ATMA command refused)."))
            }
            sniffer.window(real.copy(ms = System.currentTimeMillis() - start))
            // Se il dongle si è saturato proprio mentre lo fermavamo, l'invio che doveva fermarlo gli fa ripetere ATMA:
            // un comando innocuo lo interrompe e rimette in pari domande e risposte.
            raw("ATI", 1000)
        }
    }

    /** Rimette il dongle come serve per le domande normali, dopo un ascolto (anche interrotto a metà). */
    suspend fun endSniff() {
        // Il primo comando può arrivare mentre l'ELM sta ancora ascoltando: lo interrompe e va perso.
        raw("ATI")
        raw("ATCRA")
        raw("ATCAF1")
        raw("ATS0")
        raw("ATH0")
        if (ObdParse.mode01(raw("0100", 5000), 0x00) != null) return
        // Il clone non ha capito ATCRA (o altro): si torna ai valori di fabbrica e si rifà la preparazione.
        raw("ATD", 3000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) raw(c)
        raw("0100", 15000)
    }

    private suspend fun accepted(cmd: String) = raw(cmd).none { it.trim() == "?" }

    fun close() = link.close()
}
