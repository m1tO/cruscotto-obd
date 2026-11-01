package com.mithole.cruscotto.obd

import java.util.Locale
import kotlin.math.abs

/**
 * Marce imparate guidando. A frizione innestata ogni marcia ha un rapporto fisso tra giri e velocità:
 * i rapporti che restano stabili per qualche secondo diventano marce, e la prima è quella con cui si parte da fermo.
 */
class Gears {
    private class Cluster(var k: Double, var seconds: Double, var launches: Int)

    private val clusters = ArrayList<Cluster>()
    private var prevK: Double? = null
    private var fromStop = false
    private var launchCounted = false
    private var dirty = false

    @Synchronized
    fun load(s: String?) {
        clusters.clear()
        s?.split(';')?.forEach { part ->
            val f = part.split(':')
            if (f.size == 3) {
                val k = f[0].toDoubleOrNull()
                val sec = f[1].toDoubleOrNull()
                val l = f[2].toIntOrNull()
                if (k != null && sec != null && l != null) clusters += Cluster(k, sec, l)
            }
        }
    }

    @Synchronized
    fun save(): String = clusters.joinToString(";") { String.format(Locale.US, "%.3f:%.1f:%d", it.k, it.seconds, it.launches) }

    @Synchronized
    fun clear() {
        clusters.clear()
        prevK = null
        dirty = true
    }

    /** Vero se da un salvataggio all'altro le marce imparate sono cambiate. */
    @Synchronized
    fun takeDirty(): Boolean = dirty.also { dirty = false }

    /** Giri per km/h di ogni marcia, dalla prima in su; vuoto finché la prima non è riconosciuta. */
    @Synchronized
    fun ratios(): List<Double> {
        val ok = clusters.filter { it.seconds >= CONFIRM_S }.sortedByDescending { it.k }
        val first = ok.indexOfFirst { it.launches >= 2 }
        return if (first < 0) emptyList() else ok.drop(first).map { it.k }
    }

    @Synchronized
    fun feed(speed: Double?, rpm: Double?, dt: Double) {
        if (speed == null || rpm == null) {
            prevK = null
            return
        }
        if (speed < 1) {
            fromStop = true
            launchCounted = false
            prevK = null
            return
        }
        if (speed > 40) fromStop = false
        if (speed < 8 || rpm < 900) {
            prevK = null
            return
        }
        val k = rpm / speed
        val stable = prevK?.let { abs(k - it) / k < 0.025 } ?: false
        prevK = k
        if (!stable || dt <= 0) return

        var c = clusters.minByOrNull { abs(it.k - k) / it.k }?.takeIf { abs(it.k - k) / it.k < 0.04 }
        if (c == null) {
            c = Cluster(k, 0.0, 0)
            clusters += c
        }
        val wasConfirmed = c.seconds >= CONFIRM_S
        c.k += (k - c.k) * 0.05
        c.seconds += dt
        if (!wasConfirmed && c.seconds >= CONFIRM_S) dirty = true
        if (fromStop && !launchCounted && speed < 30) {
            c.launches++
            launchCounted = true
            dirty = true
        }
        // Le frizioni che slittano e le scalate lasciano rapporti spuri: si tengono solo i più frequentati.
        if (clusters.size > 14) clusters.remove(clusters.minBy { it.seconds })
    }

    /** Marcia inserita (1 = prima), o null se non si riconosce: folle, frizione premuta o rapporto non ancora imparato. */
    fun gear(speed: Double?, rpm: Double?, ratios: List<Double>): Int? {
        if (speed == null || rpm == null || speed < 5 || ratios.isEmpty()) return null
        val k = rpm / speed
        val i = ratios.indexOfFirst { abs(k - it) / it < 0.06 }
        return if (i < 0) null else i + 1
    }

    companion object {
        const val CONFIRM_S = 6.0
    }
}
