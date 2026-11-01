package com.mithole.cruscotto.obd

import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt

data class DynoPoint(val rpm: Double, val cv: Double, val nm: Double)

data class DynoResult(val gear: Int, val at: Long, val points: List<DynoPoint>) {
    val peakPower: DynoPoint? get() = points.maxByOrNull { it.cv }
    val peakTorque: DynoPoint? get() = points.maxByOrNull { it.nm }

    fun save(): String = "$gear|$at|" + points.joinToString(";") { String.format(Locale.US, "%.0f:%.1f:%.1f", it.rpm, it.cv, it.nm) }

    companion object {
        fun load(s: String?): DynoResult? = runCatching {
            val (g, at, pts) = s!!.split('|')
            DynoResult(g.toInt(), at.toLong(), pts.split(';').map { p -> p.split(':').let { DynoPoint(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) } })
        }.getOrNull()
    }
}

data class DynoState(val running: Boolean = false, val gear: Int? = null, val fromRpm: Double? = null, val last: DynoResult? = null, val message: String? = null)

/**
 * Banco a rulli virtuale: durante una tirata in una marcia, con l'acceleratore a fondo, la potenza alle ruote
 * è quella che serve ad accelerare l'auto più quella persa contro aria e rotolamento.
 * La velocità si ricava dai giri e dal rapporto della marcia, molto più fini del tachimetro.
 */
class Dyno {
    private val samples = ArrayList<Pair<Long, Double>>()
    private var gear: Int? = null
    private var ratio = 0.0
    var state = DynoState()
        private set

    fun restore(r: DynoResult?) {
        state = state.copy(last = r)
    }

    /** Un giro di letture. [k] = giri per km/h della marcia inserita. Restituisce il risultato quando una tirata valida finisce. */
    fun feed(t: Long, rpm: Double?, gear: Int?, k: Double?, pedal: Double?, massKg: Int): DynoResult? {
        val full = (pedal ?: 0.0) >= 85
        val running = this.gear != null
        if (running) {
            val lastRpm = samples.last().second
            val ok = full && gear == this.gear && rpm != null && rpm > lastRpm - 100
            if (ok) {
                samples += t to rpm
                state = state.copy(running = true, gear = gear, fromRpm = samples.first().second)
                return null
            }
            return finish(massKg)
        }
        if (full && gear != null && gear >= 2 && k != null && rpm != null && rpm > 1200) {
            this.gear = gear
            ratio = k
            samples.clear()
            samples += t to rpm
            state = state.copy(running = true, gear = gear, fromRpm = rpm, message = null)
        }
        return null
    }

    private fun finish(massKg: Int): DynoResult? {
        val g = gear!!
        gear = null
        val span = samples.last().second - samples.first().second
        val result = if (samples.size >= 8 && span >= 1500) compute(samples, ratio, g, massKg) else null
        state = if (result != null) {
            DynoState(last = result, message = null)
        } else {
            state.copy(running = false, gear = null, fromRpm = null, message = "Tirata troppo corta: serve almeno 1500 giri a fondo nella stessa marcia.")
        }
        return result
    }

    companion object {
        const val CDA = 0.65 // superficie frontale per coefficiente aerodinamico, m²
        const val CRR = 0.012 // rotolamento
        const val DRIVETRAIN = 0.88 // quanto arriva alle ruote di quello che dà il motore
        private const val RHO = 1.2

        /** Le ruote, il volano e gli ingranaggi da far girare pesano come un po' di massa in più, tanto più quanto è corta la marcia. */
        fun inertia(gear: Int) = when (gear) {
            1 -> 1.30
            2 -> 1.15
            3 -> 1.08
            4 -> 1.05
            else -> 1.04
        }

        fun compute(s: List<Pair<Long, Double>>, k: Double, gear: Int, massKg: Int): DynoResult {
            val m = massKg * inertia(gear)
            val raw = ArrayList<DynoPoint>()
            for (i in s.indices) {
                // Pendenza dei giri nel tempo con una retta sui punti vicini: smussa il rumore delle letture.
                val w = s.subList(maxOf(0, i - 2), minOf(s.size, i + 3))
                val tm = w.map { it.first / 1000.0 }.average()
                val rm = w.map { it.second }.average()
                var num = 0.0
                var den = 0.0
                for ((t, r) in w) {
                    num += (t / 1000.0 - tm) * (r - rm)
                    den += (t / 1000.0 - tm) * (t / 1000.0 - tm)
                }
                if (den <= 0) continue
                val rpmPerS = num / den
                val rpm = s[i].second
                val v = rpm / k / 3.6
                val a = rpmPerS / k / 3.6
                val wheelW = m * a * v + 0.5 * RHO * CDA * v * v * v + CRR * massKg * 9.81 * v
                val engineW = wheelW / DRIVETRAIN
                val nm = engineW / (2 * PI * rpm / 60)
                raw += DynoPoint(rpm, engineW / 735.5, nm)
            }
            // Un punto ogni 250 giri.
            val points = raw.groupBy { (it.rpm / 250).roundToInt() * 250.0 }.toSortedMap().map { (bin, ps) ->
                DynoPoint(bin, ps.map { it.cv }.average(), ps.map { it.nm }.average())
            }
            return DynoResult(gear, s.last().first, points)
        }
    }
}
