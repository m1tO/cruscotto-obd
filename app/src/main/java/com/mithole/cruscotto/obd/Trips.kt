package com.mithole.cruscotto.obd

import java.io.File
import java.util.Locale

/** Un viaggio finito: dall'accensione allo spegnimento. */
data class Trip(
    val start: Long,
    val end: Long,
    val km: Double,
    val liters: Double?,
    val maxKmh: Double,
    val movingS: Double,
    val pricePerL: Double,
) {
    val l100: Double? get() = liters?.takeIf { km > 0.5 }?.let { it / km * 100 }
    val cost: Double? get() = liters?.let { it * pricePerL }
    val avgKmh: Double? get() = if (movingS > 30) km / (movingS / 3600) else null

    fun line(): String = String.format(
        Locale.US, "%d;%d;%.3f;%s;%.0f;%.0f;%.3f", start, end, km, liters?.let { String.format(Locale.US, "%.3f", it) } ?: "", maxKmh, movingS, pricePerL,
    )

    companion object {
        fun parse(line: String): Trip? = runCatching {
            val c = line.split(';')
            Trip(c[0].toLong(), c[1].toLong(), c[2].toDouble(), c[3].toDoubleOrNull(), c[4].toDouble(), c[5].toDouble(), c[6].toDouble())
        }.getOrNull()
    }
}

/** Il viaggio in corso: si accumula a ogni giro di letture e si salva di tanto in tanto, così un'app chiusa non lo perde. */
class TripTracker {
    var start = 0L
        private set
    var last = 0L
        private set
    private var km = 0.0
    private var liters = 0.0
    private var fuelSeen = false
    private var maxKmh = 0.0
    private var movingS = 0.0
    private var engineOffS = 0.0
    val active: Boolean get() = start != 0L

    /** Restituisce un viaggio se con questa lettura se n'è concluso uno (motore spento da un minuto o lunga pausa). */
    fun feed(t: Long, dt: Double, speed: Double?, rpm: Double?, fuelLh: Double?, price: Double): Trip? {
        var done: Trip? = null
        if (active && t - last > 10 * 60_000) done = finish(price)
        val running = (rpm ?: 0.0) > 400
        if (!active) {
            if (!running) return done
            reset(t)
        }
        last = t
        if (dt in 0.0..5.0) {
            if (speed != null) {
                km += speed * dt / 3600
                if (speed > 2) movingS += dt
                maxKmh = maxOf(maxKmh, speed)
            }
            if (fuelLh != null) {
                liters += fuelLh * dt / 3600
                fuelSeen = true
            }
            engineOffS = if (running) 0.0 else engineOffS + dt
        }
        if (engineOffS >= 60) done = finish(price) ?: done
        return done
    }

    private fun reset(t: Long) {
        start = t
        last = t
        km = 0.0
        liters = 0.0
        fuelSeen = false
        maxKmh = 0.0
        movingS = 0.0
        engineOffS = 0.0
    }

    /** Chiude il viaggio in corso; i tragitti di poche centinaia di metri (manovre, prove) non contano. */
    fun finish(price: Double): Trip? {
        if (!active) return null
        val trip = Trip(start, last, km, if (fuelSeen) liters else null, maxKmh, movingS, price)
        start = 0L
        return trip.takeIf { it.km >= 0.3 }
    }

    fun snapshot(price: Double): Trip? = if (active) Trip(start, last, km, if (fuelSeen) liters else null, maxKmh, movingS, price) else null
}

/** Diario dei viaggi: una riga per viaggio in un file di testo. */
class TripLog(private val file: File) {
    @Synchronized
    fun append(t: Trip) {
        file.parentFile?.mkdirs()
        file.appendText(t.line() + "\n")
    }

    @Synchronized
    fun list(): List<Trip> = if (file.exists()) file.readLines().mapNotNull(Trip::parse).sortedByDescending { it.start } else emptyList()
}
