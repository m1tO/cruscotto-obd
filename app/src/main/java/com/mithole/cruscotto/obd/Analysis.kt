package com.mithole.cruscotto.obd

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/** Le prove cronometrate: partono da sole quando si parte da fermo o si accelera da sotto gli 80. */
enum class Run(val label: String, val from: Double, val to: Double, val maxS: Double) {
    ZERO_50("0–50 km/h", 0.0, 50.0, 15.0),
    ZERO_100("0–100 km/h", 0.0, 100.0, 30.0),
    MID_80_120("80–120 km/h", 80.0, 120.0, 30.0),
}

enum class Level { WAIT, OK, WARN, BAD, NA }

data class Check(val title: String, val level: Level, val value: String, val detail: String)

data class Analysis(
    val last: Map<Run, Double> = emptyMap(),
    val best: Map<Run, Double> = emptyMap(),
    /** Prove in corso: secondi passati dalla partenza. */
    val running: Map<Run, Double> = emptyMap(),
    val powerCv: Double? = null,
    val powerRpm: Double? = null,
    val torqueNm: Double? = null,
    val torqueRpm: Double? = null,
    val checks: List<Check> = emptyList(),
)

private fun f(v: Double?, decimals: Int = 0, signed: Boolean = false): String =
    if (v == null) t("N/D", "N/A") else String.format(L.locale, if (signed) "%+.${decimals}f" else "%.${decimals}f", v)

/** Cronometro di una prova. Gli istanti di partenza e arrivo si interpolano tra un campione e l'altro. */
private class RunTimer(val run: Run) {
    private val samples = ArrayList<Pair<Long, Double>>() // dall'ultimo campione sotto la partenza
    private var armed = false
    private var top = 0.0

    private fun below(v: Double) = if (run.from == 0.0) v < 0.5 else v < run.from

    private fun reset() {
        armed = false
        samples.clear()
    }

    /** Da fermo si risale all'istante in cui l'auto si è mossa prolungando i primi due campioni in moto. */
    private fun startTime(): Double {
        val (tb, vb) = samples[0]
        val (t1, v1) = samples[1]
        if (run.from > 0) return tb + (t1 - tb) * (run.from - vb) / (v1 - vb)
        if (samples.size < 3) return tb.toDouble()
        val (t2, v2) = samples[2]
        if (v2 <= v1) return tb.toDouble()
        return (t1 - v1 * (t2 - t1) / (v2 - v1)).coerceIn(tb.toDouble(), t1.toDouble())
    }

    fun elapsed(t: Long): Double? = if (armed && samples.size >= 2) (t - startTime()) / 1000.0 else null

    /** Restituisce i secondi della prova se è finita con questo campione. */
    fun feed(t: Long, v: Double): Double? {
        if (below(v)) {
            samples.clear()
            samples += t to v
            armed = true
            top = v
            return null
        }
        if (!armed) return null
        if (v < top - 3) { // ha mollato o frenato
            reset()
            return null
        }
        top = maxOf(top, v)
        samples += t to v
        val start = startTime()
        if ((t - start) / 1000.0 > run.maxS) {
            reset()
            return null
        }
        if (v < run.to) return null
        val (t0, v0) = samples[samples.size - 2]
        val end = if (v0 < run.to) t0 + (t - t0) * (run.to - v0) / (v - v0) else t.toDouble()
        reset()
        return ((end - start) / 1000.0).takeIf { it > 1.0 }
    }
}

/** Media pesata sul tempo. */
private class Avg {
    var sum = 0.0
    var w = 0.0
    fun add(v: Double, dt: Double) {
        sum += v * dt
        w += dt
    }
    fun after(seconds: Double): Double? = if (w >= seconds) sum / w else null
}

/**
 * Prestazioni e check-up del motore, costruiti campione dopo campione mentre si guida.
 * Vive per tutta la sessione con un'auto, anche attraverso le riconnessioni.
 */
class Analyzer {
    private val timers = Run.entries.map(::RunTimer)
    private val last = HashMap<Run, Double>()
    private val best = HashMap<Run, Double>()

    private val recent = ArrayDeque<Double>()
    private var powerKw: Double? = null
    private var powerRpm: Double? = null
    private var torque: Double? = null
    private var torqueRpm: Double? = null

    private val idleTrim = Avg()
    private val loadTrim = Avg()
    private val runVolt = Avg()
    private var offVolt: Double? = null
    private var runningS = 0.0
    private var coolStart: Double? = null
    private var coolMax = Double.NEGATIVE_INFINITY
    private var warmAtS: Double? = null
    private var iatRef: Double? = null
    private var boostS = 0.0
    private var iatRise: Double? = null
    private var boostMax: Double? = null
    private var boostMaxRpm: Double? = null
    private val speedRatio = Avg()
    private var prevSpeed: Double? = null

    fun setBests(b: Map<Run, Double>) {
        best.clear()
        best.putAll(b)
    }

    fun clearRecords() {
        last.clear()
        best.clear()
        powerKw = null
        powerRpm = null
        torque = null
        torqueRpm = null
        boostMax = null
        boostMaxRpm = null
    }

    /** Un giro di letture. Restituisce le prove che hanno appena fatto un nuovo record. */
    fun feed(t: Long, dt: Double, v: Map<Metric, Double>, p: Profile, gpsKmh: Double? = null): Map<Run, Double> {
        val records = HashMap<Run, Double>()
        val speed = v[Metric.SPEED]
        if (speed != null) {
            for (timer in timers) {
                val s = timer.feed(t, speed) ?: continue
                last[timer.run] = s
                if (s < (best[timer.run] ?: Double.MAX_VALUE)) {
                    best[timer.run] = s
                    records[timer.run] = s
                }
            }
        }

        val rpm = v[Metric.RPM] ?: 0.0
        val running = rpm > 400
        val fuelRate = v[Metric.FUEL_RATE]
        if (fuelRate != null && rpm > 1500) {
            // Mediana degli ultimi tre: un picco isolato del debimetro non fa record.
            recent.addLast(powerFromFuel(fuelRate, p.fuel) ?: 0.0)
            while (recent.size > 3) recent.removeFirst()
            if (recent.size == 3) {
                val kw = recent.sorted()[1]
                if (kw > (powerKw ?: 0.0)) {
                    powerKw = kw
                    powerRpm = rpm
                }
                val nm = kw * 1000 / (2 * PI * rpm / 60)
                if (kw > 0 && nm > (torque ?: 0.0)) {
                    torque = nm
                    torqueRpm = rpm
                }
            }
        } else {
            recent.clear()
        }

        if (dt <= 0) return records
        if (running) runningS += dt

        // Correzioni della miscela: contano solo a motore caldo, quando la centralina usa la sonda lambda.
        val coolant = v[Metric.COOLANT]
        val trim = if (Metric.STFT in v || Metric.LTFT in v) (v[Metric.STFT] ?: 0.0) + (v[Metric.LTFT] ?: 0.0) else null
        if (trim != null && (coolant ?: 0.0) > 70 && runningS > 60) {
            if (rpm in 500.0..1100.0 && (speed ?: 0.0) < 3) idleTrim.add(trim, dt)
            if ((v[Metric.LOAD] ?: 0.0) >= 50 && (speed ?: 0.0) > 20) loadTrim.add(trim, dt)
        }

        v[Metric.VOLT]?.let { volt ->
            if (rpm == 0.0 && runningS == 0.0) offVolt = volt
            if (running && runningS > 20) runVolt.add(volt, dt)
        }

        if (coolant != null) {
            if (coolStart == null) coolStart = coolant
            coolMax = maxOf(coolMax, coolant)
            if (warmAtS == null && coolant >= 80 && runningS > 0) warmAtS = runningS
        }

        val iat = v[Metric.IAT]
        val boost = v[Metric.BOOST]
        if (iat != null) {
            val ambient = v[Metric.AMBIENT]
            if (ambient != null) {
                iatRef = ambient
            } else if ((speed ?: 0.0) > 40 && (boost ?: 0.0) < 0.2) {
                iatRef = minOf(iatRef ?: iat, iat)
            }
            val ref = iatRef
            if (boost != null && boost > 0.5 && ref != null) {
                boostS += dt
                iatRise = maxOf(iatRise ?: Double.NEGATIVE_INFINITY, iat - ref)
            }
        }
        // Velocità della centralina contro quella del GPS: solo in velocità costante, dove il GPS è preciso.
        if (speed != null && gpsKmh != null && speed > 30 && gpsKmh > 30) {
            val steady = prevSpeed?.let { kotlin.math.abs(it - speed) <= 1 } ?: false
            if (steady) speedRatio.add((speed / gpsKmh - 1) * 100, dt)
        }
        prevSpeed = speed
        if (boost != null && boost > (boostMax ?: Profile.BOOST_PROOF)) {
            boostMax = boost
            boostMaxRpm = rpm
        }
        return records
    }

    fun snapshot(t: Long, p: Profile, has: Set<Metric>, now: Map<Metric, Double>, gps: GpsStatus = GpsStatus.NO_PERMISSION) = Analysis(
        last = HashMap(last),
        best = HashMap(best),
        running = timers.mapNotNull { tm -> tm.elapsed(t)?.let { tm.run to it } }.toMap(),
        powerCv = powerKw?.let { it * 1.35962 },
        powerRpm = powerRpm,
        torqueNm = torque,
        torqueRpm = torqueRpm,
        checks = listOfNotNull(mixture(has), charging(), warmup(has, now), intercooler(p, has), turbo(p), speedometer(gps)),
    )

    private fun mixture(has: Set<Metric>): Check {
        val title = t("Miscela aria-benzina", "Air-fuel mixture")
        if (Metric.STFT !in has && Metric.LTFT !in has) {
            return Check(title, Level.NA, t("N/D", "N/A"), t("La centralina non comunica quanto corregge la miscela.", "The ECU doesn't report its fuel trims."))
        }
        val idle = idleTrim.after(20.0)
        val load = loadTrim.after(15.0)
        if (idle == null && load == null) {
            return Check(
                title, Level.WAIT, t("In misura", "Measuring"),
                t("Servono mezzo minuto al minimo a motore caldo e qualche tratto in accelerazione.", "Needs half a minute at idle with a warm engine and a few stretches under acceleration."),
            )
        }
        val worst = listOfNotNull(idle, load).maxBy { abs(it) }
        val level = when {
            abs(worst) > 20 -> Level.BAD
            abs(worst) > 10 -> Level.WARN
            else -> Level.OK
        }
        val detail = when {
            level == Level.OK -> t("La centralina corregge poco: aspirazione e iniezione sono a posto.", "The ECU barely corrects: intake and injection are fine.")
            idle != null && idle > 10 && (load == null || load < idle - 8) ->
                t("Magra al minimo ma non sotto carico: tipico di un'aria falsa, cioè un manicotto o un tubo del vuoto che perde.", "Lean at idle but not under load: typical of an air leak, i.e. a leaking hose or vacuum line.")
            idle != null && load != null && idle > 10 && load > 10 ->
                t("Magra sempre: pressione della benzina bassa, iniettori sporchi o debimetro che legge poco.", "Always lean: low fuel pressure, dirty injectors or a mass air flow sensor reading low.")
            load != null && load > 10 -> t("Magra sotto carico: pompa o filtro della benzina che non stanno dietro.", "Lean under load: the fuel pump or filter can't keep up.")
            else -> t("Ricca: un iniettore che gocciola, una sonda lambda pigra o il debimetro sporco.", "Rich: a dripping injector, a lazy O2 sensor or a dirty mass air flow sensor.")
        }
        return Check(title, level, t("minimo ${f(idle, 1, true)} % · in tiro ${f(load, 1, true)} %", "idle ${f(idle, 1, true)} % · under load ${f(load, 1, true)} %"), detail)
    }

    private fun charging(): Check {
        val title = t("Batteria e alternatore", "Battery and alternator")
        val run = runVolt.after(30.0)
        val off = offVolt
        if (run == null && off == null) {
            return Check(title, Level.WAIT, t("In misura", "Measuring"), t("Si misura a motore spento e poi dopo mezzo minuto in moto.", "Measured with the engine off and then after half a minute running."))
        }
        val value = listOfNotNull(run?.let { t("${f(it, 1)} V in moto", "${f(it, 1)} V running") }, off?.let { t("${f(it, 1)} V da spento", "${f(it, 1)} V engine off") }).joinToString(" · ")
        return when {
            run != null && run < 13.0 -> Check(title, Level.BAD, value, t("In moto la tensione è troppo bassa: l'alternatore non carica.", "Voltage too low with the engine running: the alternator isn't charging."))
            run != null && run > 15.0 -> Check(title, Level.BAD, value, t("Tensione troppo alta: il regolatore dell'alternatore non funziona e cuoce la batteria.", "Voltage too high: the alternator regulator has failed and is cooking the battery."))
            run != null && run < 13.4 -> Check(
                title, Level.WARN, value,
                t("Carica debole: alternatore stanco o cinghia che slitta. Su alcune auto recenti scende così di proposito.", "Weak charging: tired alternator or slipping belt. Some recent cars drop this low on purpose."),
            )
            off != null && off < 12.2 -> Check(title, Level.WARN, value, t("A motore spento la batteria è bassa: scarica o a fine vita.", "The battery is low with the engine off: flat or near the end of its life."))
            run == null -> Check(title, Level.WAIT, value, t("La batteria è carica; manca la misura con il motore in moto.", "The battery is charged; the reading with the engine running is still missing."))
            else -> Check(title, Level.OK, value, t("La batteria tiene e l'alternatore carica il giusto.", "The battery holds up and the alternator charges correctly."))
        }
    }

    private fun warmup(has: Set<Metric>, now: Map<Metric, Double>): Check {
        val title = t("Temperatura e termostato", "Temperature and thermostat")
        if (Metric.COOLANT !in has) return Check(title, Level.NA, t("N/D", "N/A"), t("La centralina non comunica la temperatura dell'acqua.", "The ECU doesn't report the coolant temperature."))
        val start = coolStart ?: return Check(title, Level.WAIT, t("In misura", "Measuring"), t("Aspetto la prima lettura.", "Waiting for the first reading."))
        val cur = now[Metric.COOLANT]
        val mins = runningS / 60
        val warm = warmAtS
        return when {
            coolMax > 108 -> Check(title, Level.BAD, t("max ${f(coolMax)} °C", "max ${f(coolMax)} °C"), t("Il motore si è scaldato troppo: livello del liquido, ventola o pompa dell'acqua.", "The engine got too hot: check coolant level, fan or water pump."))
            start >= 70 -> Check(title, Level.OK, "${f(cur)} °C", t("Era già calda all'avvio: il tempo di riscaldamento si misura partendo a motore freddo.", "It was already warm at start-up: warm-up time is measured from a cold engine."))
            warm != null && warm > 15 * 60 -> Check(
                title, Level.WARN, t("80 °C in ${f(warm / 60)} min", "80 °C in ${f(warm / 60)} min"),
                t("Ci mette tanto a scaldarsi: il termostato potrebbe restare aperto.", "It takes a long time to warm up: the thermostat may be stuck open."),
            )
            warm != null -> Check(title, Level.OK, t("80 °C in ${f(warm / 60, 1)} min", "80 °C in ${f(warm / 60, 1)} min"), t("Si scalda nei tempi giusti: termostato a posto.", "Warms up on time: thermostat OK."))
            mins > 15 && coolMax < 75 -> Check(
                title, Level.WARN, t("max ${f(coolMax)} °C dopo ${f(mins)} min", "max ${f(coolMax)} °C after ${f(mins)} min"),
                t("Dopo un quarto d'ora non arriva a 75 °C: termostato bloccato aperto.", "After a quarter of an hour it doesn't reach 75 °C: thermostat stuck open."),
            )
            else -> Check(title, Level.WAIT, t("${f(cur)} °C · ${f(mins, 1)} min", "${f(cur)} °C · ${f(mins, 1)} min"), t("Sto misurando quanto ci mette ad arrivare a 80 °C.", "Measuring how long it takes to reach 80 °C."))
        }
    }

    private fun intercooler(p: Profile, has: Set<Metric>): Check? {
        if (!p.turbo) return null
        val title = t("Intercooler", "Intercooler")
        if (Metric.IAT !in has) return Check(title, Level.NA, t("N/D", "N/A"), t("La centralina non comunica la temperatura dell'aria aspirata.", "The ECU doesn't report the intake air temperature."))
        val rise = iatRise
        if (boostS < 3 || rise == null) {
            return Check(title, Level.WAIT, t("In misura", "Measuring"), t("Serve qualche secondo col turbo sopra 0,5 bar, dopo un tratto in movimento.", "Needs a few seconds above 0.5 bar of boost, after driving for a while."))
        }
        val value = t("${f(rise, 0, true)} °C sull'aria esterna", "${f(rise, 0, true)} °C above outside air")
        return when {
            rise > 30 -> Check(title, Level.BAD, value, t("L'aria in pressione arriva molto calda: intercooler intasato o tubi che perdono. Perdi potenza.", "The charge air arrives very hot: clogged intercooler or leaking pipes. You're losing power."))
            rise > 15 -> Check(title, Level.WARN, value, t("L'aria si scalda parecchio sotto turbo: intercooler sporco, oppure motore cotto da una sosta al sole.", "The air heats up a lot under boost: dirty intercooler, or an engine heat-soaked from sitting in the sun."))
            else -> Check(title, Level.OK, value, t("L'aria in pressione resta fresca: l'intercooler lavora bene.", "The charge air stays cool: the intercooler is doing its job."))
        }
    }

    private fun turbo(p: Profile): Check? {
        if (!p.turbo) return null
        val title = t("Turbo", "Turbo")
        val b = boostMax ?: return Check(title, Level.WAIT, t("In misura", "Measuring"), t("Fai una tirata in terza: qui resta la pressione più alta raggiunta.", "Do a full pull in third gear: the highest boost reached is kept here."))
        return Check(
            title, Level.OK, t("max ${f(b, 2, true)} bar a ${f(boostMaxRpm)} giri", "max ${f(b, 2, true)} bar at ${f(boostMaxRpm)} rpm"),
            t("La spinta più alta di questa sessione. Se negli anni cala, o compare l'errore P0299, controlla manicotti e valvola wastegate.", "The highest boost of this session. If it drops over the years, or error P0299 shows up, check the hoses and the wastegate."),
        )
    }

    private fun speedometer(gps: GpsStatus): Check {
        val title = t("Velocità rispetto al GPS", "Speed vs GPS")
        if (gps == GpsStatus.NO_HARDWARE) return Check(title, Level.NA, t("N/D", "N/A"), t("Questo tablet non ha il GPS, quindi il confronto non si può fare.", "This tablet has no GPS, so the comparison isn't possible."))
        if (gps == GpsStatus.NO_PERMISSION) return Check(title, Level.NA, t("N/D", "N/A"), t("Serve il permesso di posizione: lo trovi nella scheda Impostazioni.", "Needs location permission: you'll find it in Settings."))
        val diff = speedRatio.after(60.0)
            ?: return Check(title, Level.WAIT, t("In misura", "Measuring"), t("Serve un minuto a velocità costante sopra i 30 km/h, all'aperto.", "Needs a minute at steady speed above 30 km/h, outdoors."))
        val value = t("${f(diff, 1, true)} % rispetto al GPS", "${f(diff, 1, true)} % vs GPS")
        val detail = when {
            kotlin.math.abs(diff) <= 2 -> t("La centralina misura la velocità giusta: distanze e consumi dell'app sono affidabili.", "The ECU measures speed correctly: the app's distances and fuel figures are reliable.")
            diff > 0 -> t("La centralina segna più della velocità vera: chilometri percorsi e tempi sono gonfiati di altrettanto. Succede con gomme più piccole o consumate.", "The ECU reads higher than your true speed: distance and times are inflated by the same amount. It happens with smaller or worn tyres.")
            else -> t("La centralina segna meno della velocità vera: tipico di gomme più grandi dell'originale.", "The ECU reads lower than your true speed: typical of tyres larger than the original ones.")
        }
        return Check(title, if (kotlin.math.abs(diff) <= 5) Level.OK else Level.WARN, value, detail)
    }

    companion object {
        /** Potenza dal carburante bruciato: potere calorifico per rendimento tipico del motore. Stima grezza, ±15 %. */
        fun powerFromFuel(litersPerHour: Double, fuel: Fuel): Double? {
            val density = fuel.density ?: return null
            val (kjPerGram, efficiency) = when (fuel) {
                Fuel.PETROL -> 43.4 to 0.30
                Fuel.DIESEL -> 42.8 to 0.38
                Fuel.LPG -> 46.0 to 0.30
                Fuel.CNG -> return null
            }
            return litersPerHour * density / 3600 * kjPerGram * efficiency
        }
    }
}
