package com.mithole.cruscotto.obd

import java.util.Locale

/** Un consiglio da mostrare sul cruscotto, finché vale. */
data class Advice(val level: Level, val title: String, val text: String)

private fun f(v: Double, decimals: Int = 0) = String.format(Locale.ITALY, "%.${decimals}f", v)

/**
 * Motore freddo e turbo caldo. A freddo l'olio è denso e protegge poco: niente giri alti.
 * Dopo una guida tirata la turbina è rovente: se si spegne subito l'olio fermo nei cuscinetti cuoce.
 */
class Advisor {
    private var heat = 0.0
    private var cooldownLeft: Double? = null
    private var doneFor = 0.0
    var cooledDown = false
        private set

    fun feed(dt: Double, v: Map<Metric, Double>, p: Profile): Advice? {
        cooledDown = false
        val rpm = v[Metric.RPM] ?: return null
        val speed = v[Metric.SPEED] ?: 0.0
        val running = rpm > 400
        if (!running) {
            cooldownLeft = null
            return null
        }

        if (p.turbo) {
            val hard = (v[Metric.BOOST] ?: 0.0) > 0.6 || rpm > 4500
            heat = if (hard) minOf(120.0, heat + dt) else maxOf(0.0, heat - dt * 0.2)
            val idling = speed < 3 && rpm < 1300
            if (idling && (heat >= 8 || cooldownLeft != null)) {
                val left = (cooldownLeft ?: minOf(90.0, 30.0 + heat)) - dt
                if (left <= 0) {
                    cooldownLeft = null
                    heat = 0.0
                    doneFor = 8.0
                    cooledDown = true
                } else {
                    cooldownLeft = left
                    // Al minimo la turbina si raffredda: il calore accumulato scende con l'attesa.
                    heat = maxOf(0.0, heat - dt)
                    val s = left.toInt()
                    return Advice(Level.WARN, "Turbo caldo", "Lascia girare il motore ancora %d:%02d prima di spegnere.".format(s / 60, s % 60))
                }
            } else if (!idling) {
                cooldownLeft = null
            }
            if (doneFor > 0) {
                doneFor -= dt
                return Advice(Level.OK, "Turbo raffreddato", "Puoi spegnere il motore.")
            }
        }

        val oil = v[Metric.OIL]
        val coolant = v[Metric.COOLANT]
        val temp = oil ?: coolant ?: return null
        val cold = if (oil != null) oil < 70 else temp < 65
        if (!cold) return null
        val what = if (oil != null) "olio" else "acqua"
        return if (rpm > 3500) {
            Advice(Level.BAD, "Motore freddo", "Stai a ${f(rpm)} giri con l'$what a ${f(temp)} °C: tieni sotto i 3000 finché non si scalda.")
        } else {
            Advice(Level.WARN, "Motore freddo", "${what.replaceFirstChar { it.uppercase() }} a ${f(temp)} °C: guida tranquillo, niente tirate.")
        }
    }
}

/**
 * Avvisi a voce: poche frasi, solo quando qualcosa cambia, e mai la stessa a raffica.
 * Restituisce le frasi da dire; chi le pronuncia è l'interfaccia.
 */
class Alerter {
    private val lastSaid = HashMap<String, Long>()
    private var hotWater = false
    private var lowVoltS = 0.0
    private var dtcCount = -1
    private val lowTires = HashSet<Wheel>()

    private fun say(key: String, t: Long, minGapMs: Long, text: String, out: MutableList<String>) {
        if (t - (lastSaid[key] ?: Long.MIN_VALUE / 2) < minGapMs) return
        lastSaid[key] = t
        out += text
    }

    fun feed(t: Long, dt: Double, v: Map<Metric, Double>, status: Status?, advice: Advice?, cooledDown: Boolean, tires: List<TireState>): List<String> {
        val out = ArrayList<String>()
        val coolant = v[Metric.COOLANT]
        if (coolant != null) {
            if (coolant >= 105 && !hotWater) {
                hotWater = true
                say("acqua", t, 120_000, "Attenzione: acqua a ${f(coolant)} gradi.", out)
            } else if (coolant < 100) {
                hotWater = false
            }
        }
        val volt = v[Metric.VOLT]
        val running = (v[Metric.RPM] ?: 0.0) > 400
        lowVoltS = if (running && volt != null && volt < 12.8) lowVoltS + dt else 0.0
        if (lowVoltS >= 15 && volt != null) say("batteria", t, 600_000, "La batteria non si carica: ${f(volt, 1)} volt.", out)

        status?.let { s ->
            if (dtcCount >= 0 && s.dtcCount > dtcCount) say("errore", t, 0, "Nuovo errore motore. Guarda la scheda errori.", out)
            dtcCount = s.dtcCount
        }
        if (advice?.level == Level.BAD && advice.title == "Motore freddo") say("freddo", t, 120_000, "Motore freddo, piano coi giri.", out)
        if (cooledDown) say("turbo", t, 0, "Turbo raffreddato, puoi spegnere.", out)

        for (s in tires) {
            val low = s.level == TireLevel.LOW || s.level == TireLevel.FLAT
            if (low && s.wheel !in lowTires && s.reading != null) {
                say("gomma" + s.wheel, t, 600_000, "Gomma ${s.wheel.label.lowercase()} a ${f(s.reading.bar, 1)} bar.", out)
            }
            if (low) lowTires += s.wheel else lowTires -= s.wheel
        }
        return out
    }
}
