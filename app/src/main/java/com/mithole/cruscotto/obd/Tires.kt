package com.mithole.cruscotto.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class Wheel(private val labelIt: String, private val labelEn: String, private val shortIt: String, private val shortEn: String) {
    FL("Anteriore sinistra", "Front left", "Ant. sx", "Front L"),
    FR("Anteriore destra", "Front right", "Ant. dx", "Front R"),
    RL("Posteriore sinistra", "Rear left", "Post. sx", "Rear L"),
    RR("Posteriore destra", "Rear right", "Post. dx", "Rear R"),
    ;

    val label: String get() = t(labelIt, labelEn)
    val short: String get() = t(shortIt, shortEn)

    val front: Boolean get() = this == FL || this == FR
}

/** Impostazioni dell'app che non dipendono dalla centralina. */
data class Settings(
    /** Peso dell'auto con chi guida a bordo: serve al dyno. */
    val massKg: Int = 1250,
    val fuelPrice: Double = 1.85,
    val voice: Boolean = true,
    /** Pressioni consigliate a freddo, in bar. */
    val tireFront: Double = 2.3,
    val tireRear: Double = 2.1,
    /** Ruota → indirizzo Bluetooth del sensore montato lì. */
    val tires: Map<Wheel, String> = emptyMap(),
) {
    fun target(w: Wheel) = if (w.front) tireFront else tireRear
}

data class TireReading(val bar: Double, val tempC: Double?, val batteryPct: Int?, val at: Long)

/** Un dispositivo Bluetooth LE sentito nei dintorni, con i dati grezzi che trasmette. */
data class BleSeen(
    val address: String,
    val name: String?,
    val rssi: Int,
    val raw: String,
    val at: Long,
    val reading: TireReading?,
)

enum class TireLevel { NONE, STALE, OK, LOW, FLAT, HIGH }

data class TireState(val wheel: Wheel, val reading: TireReading?, val target: Double, val level: TireLevel, val note: String)

object TpmsDecoder {
    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    /**
     * Tappini Bluetooth più diffusi (si annunciano come "TPMS1_…" … "TPMS4_…"): nei dati del produttore, tolto
     * l'identificativo, ci sono 6 byte di indirizzo, la pressione in pascal e la temperatura in centesimi di grado, little-endian,
     * poi la batteria in percento. Il formato è quello documentato dagli appassionati e va confermato coi sensori veri:
     * per questo si accetta solo se tutti i numeri sono plausibili.
     */
    fun decode(data: ByteArray, now: Long): TireReading? {
        if (data.size < 15) return null
        val bar = u32(data, 6) / 100_000.0
        val temp = u32(data, 10).toInt() / 100.0
        val battery = data[14].toInt() and 0xFF
        if (bar !in 0.0..6.0 || temp !in -40.0..125.0 || battery > 100) return null
        return TireReading(bar, temp, battery, now)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}

/** Sensori delle gomme: cosa si sente intorno e l'ultima lettura di ciascuno. Lo alimenta la ricerca Bluetooth dell'interfaccia. */
object Tpms {
    private val seen = LinkedHashMap<String, BleSeen>()
    private val _devices = MutableStateFlow<List<BleSeen>>(emptyList())
    val devices: StateFlow<List<BleSeen>> = _devices.asStateFlow()
    private var publishedAt = 0L

    /** [data]: identificativo del produttore → dati che seguono, come li consegna Android. */
    @Synchronized
    fun onAdvert(address: String, name: String?, rssi: Int, data: List<Pair<Int, ByteArray>>, now: Long = System.currentTimeMillis()) {
        val reading = data.firstNotNullOfOrNull { TpmsDecoder.decode(it.second, now) } ?: seen[address]?.reading
        val raw = data.joinToString(" ") { (id, b) -> "%04X:%s".format(id, TpmsDecoder.hex(b)) }
        seen[address] = BleSeen(address, name ?: seen[address]?.name, rssi, raw, now, reading)
        // Intorno possono esserci decine di dispositivi: la lista si ripubblica al massimo due volte al secondo.
        if (now - publishedAt > 500) publish(now)
    }

    @Synchronized
    private fun publish(now: Long) {
        publishedAt = now
        seen.entries.removeAll { now - it.value.at > 120_000 && it.value.reading == null }
        _devices.value = seen.values.sortedByDescending { it.rssi }
    }

    @Synchronized
    fun reading(address: String?): TireReading? = address?.let { seen[it]?.reading }

    fun states(s: Settings, now: Long = System.currentTimeMillis()): List<TireState> {
        val readings = Wheel.entries.associateWith { reading(s.tires[it]) }
        return Wheel.entries.map { w -> tireState(w, s.tires[w], readings[w], s.target(w), now, readings) }
    }

    fun tireState(
        w: Wheel, address: String?, r: TireReading?, target: Double, now: Long, all: Map<Wheel, TireReading?>,
    ): TireState {
        if (address == null) return TireState(w, null, target, TireLevel.NONE, t("nessun sensore", "no sensor"))
        if (r == null) return TireState(w, null, target, TireLevel.STALE, t("in attesa del segnale", "waiting for signal"))
        if (now - r.at > 15 * 60_000) return TireState(w, r, target, TireLevel.STALE, t("segnale vecchio", "stale signal"))
        // Lo stesso asse deve avere la stessa pressione: una differenza vuol dire una gomma che perde.
        val twin = when (w) {
            Wheel.FL -> Wheel.FR
            Wheel.FR -> Wheel.FL
            Wheel.RL -> Wheel.RR
            Wheel.RR -> Wheel.RL
        }
        val other = all[twin]?.takeIf { now - it.at < 15 * 60_000 }
        return when {
            r.bar < target - 0.4 -> TireState(w, r, target, TireLevel.FLAT, t("molto sgonfia", "very low"))
            r.bar < target - 0.2 -> TireState(w, r, target, TireLevel.LOW, t("da gonfiare", "needs air"))
            other != null && other.bar - r.bar > 0.2 -> TireState(w, r, target, TireLevel.LOW, t("più bassa dell'altra: possibile foratura lenta", "lower than the other side: possible slow puncture"))
            r.bar > target + 0.5 -> TireState(w, r, target, TireLevel.HIGH, t("troppo gonfia", "overinflated"))
            else -> TireState(w, r, target, TireLevel.OK, if (abs(r.bar - target) < 0.1) "giusta" else "ok")
        }
    }
}
