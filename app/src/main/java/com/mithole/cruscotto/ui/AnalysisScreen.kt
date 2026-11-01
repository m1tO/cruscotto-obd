package com.mithole.cruscotto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.Analysis
import com.mithole.cruscotto.obd.Check
import com.mithole.cruscotto.obd.DynoPoint
import com.mithole.cruscotto.obd.DynoResult
import com.mithole.cruscotto.obd.DynoState
import com.mithole.cruscotto.obd.Trip
import com.mithole.cruscotto.obd.Level
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Run
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.mithole.cruscotto.obd.t
import com.mithole.cruscotto.obd.L

private fun Level.color(): Color = when (this) {
    Level.OK -> Pal.green
    Level.WARN -> Pal.amber
    Level.BAD -> Pal.red
    Level.WAIT -> Pal.cyan
    Level.NA -> Pal.muted
}

private fun Level.word(): String = when (this) {
    Level.OK -> t("A posto", "OK")
    Level.WARN -> t("Da controllare", "Check")
    Level.BAD -> t("Problema", "Problem")
    Level.WAIT -> t("In misura", "Measuring")
    Level.NA -> t("N/D", "N/A")
}

@Composable
fun AnalysisScreen(
    analysis: Analysis,
    live: Live,
    onResetRecords: () -> Unit,
    modifier: Modifier = Modifier,
    dyno: DynoState = DynoState(),
    trips: List<Trip> = emptyList(),
    massKg: Int = 1250,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Card(t("Prestazioni", "Performance")) { Performance(analysis, live, onResetRecords) }
                        Card(t("Banco prova", "Dyno")) { DynoView(dyno, live, massKg) }
                    }
                    Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Card(t("Check-up motore", "Engine check-up")) { Checkup(analysis.checks) }
                        Card(t("Diario viaggi", "Trip log")) { TripsView(trips) }
                    }
                }
            } else {
                Card(t("Prestazioni", "Performance")) { Performance(analysis, live, onResetRecords) }
                Card(t("Banco prova", "Dyno")) { DynoView(dyno, live, massKg) }
                Card(t("Check-up motore", "Engine check-up")) { Checkup(analysis.checks) }
                Card(t("Diario viaggi", "Trip log")) { TripsView(trips) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Performance(a: Analysis, live: Live, onReset: () -> Unit) {
    Run.entries.forEach { r ->
        val running = a.running[r]
        val shown = running ?: a.last[r]
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.label, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (running != null) t("in corso…", "running…") else if (a.last[r] != null) t("ultima prova", "last run") else t("parte da sola", "starts by itself"),
                    color = if (running != null) Pal.amber else Pal.muted, fontSize = 13.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        fmt(shown, 1), color = if (running != null) Pal.amber else Pal.text,
                        fontSize = 34.sp, fontWeight = FontWeight.SemiBold, style = Digits,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("s", color = Pal.muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(t("record ${fmt(a.best[r], 1)} s", "best ${fmt(a.best[r], 1)} s"), color = if (a.best[r] != null) Pal.amber else Pal.muted, fontSize = 13.sp, style = Digits)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Pal.track))
    StatRow(t("Potenza stimata", "Estimated power"), fmt(a.powerCv), t("CV", "hp"), a.powerRpm)
    StatRow(t("Coppia stimata", "Estimated torque"), fmt(a.torqueNm), "Nm", a.torqueRpm)
    // Ogni giro di letture chiede giri, velocità e turbo più un valore a rotazione: circa 4 domande.
    val step = if (live.hz > 0.5) 4 / live.hz else null
    Text(
        t("I tempi partono da soli: da fermo per 0–50 e 0–100, accelerando da sotto gli 80 per 80–120. ", "Timing starts by itself: from standstill for 0–50 and 0–100, accelerating from below 80 for 80–120. ") +
            (if (step != null) t("La velocità arriva circa ${fmt(1 / step, 0)} volte al secondo, quindi la precisione è di ±${fmt(step / 2, 1)} s. ", "Speed arrives about ${fmt(1 / step, 0)} times a second, so the accuracy is ±${fmt(step / 2, 1)} s. ") else "") +
            t("Potenza e coppia sono stime dal carburante bruciato, con un margine del 15 % circa.", "Power and torque are estimated from the fuel burned, with a margin of about 15 %."),
        color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
    )
    OutlinedButton(onClick = onReset, modifier = Modifier.padding(top = 10.dp)) { Text(t("Azzera record", "Reset records")) }
}

@Composable
private fun StatRow(label: String, value: String, unit: String, rpm: Double?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, color = Pal.muted, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(bottom = 4.dp))
        Text(value, color = Pal.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = Digits)
        Spacer(Modifier.width(4.dp))
        Text(
            if (rpm != null) t("$unit a ${fmt(rpm)} giri", "$unit at ${fmt(rpm)} rpm") else unit,
            color = Pal.muted, fontSize = 14.sp, style = Digits, modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun ColumnScope.Checkup(checks: List<Check>) {
    checks.forEachIndexed { i, c ->
        if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(Pal.track))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(c.level.color()))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        c.level.word(), color = c.level.color(), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.level.color().copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                if (c.value != c.level.word()) {
                    Text(c.value, color = Pal.text, fontSize = 15.sp, style = Digits, modifier = Modifier.padding(top = 2.dp))
                }
                Text(c.detail, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Text(
        t("Il check-up si costruisce mentre guidi e ricomincia a ogni collegamento. Non sostituisce l'officina: ti dice dove guardare.", "The check-up builds up while you drive and starts over at every connection. It doesn't replace a mechanic: it tells you where to look."),
        color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun ColumnScope.DynoView(d: DynoState, live: Live, massKg: Int) {
    val status = when {
        d.running -> t("Tirata in corso in ${d.gear}ª da ${fmt(d.fromRpm)} giri…", "Pull in progress in gear ${d.gear} from ${fmt(d.fromRpm)} rpm…")
        live.gearRatios.isEmpty() -> t("Prima l'app deve imparare le marce: guida normalmente qualche minuto.", "First the app has to learn the gears: drive normally for a few minutes.")
        d.message != null -> d.message
        else -> t("Strada dritta, piana e libera: in 3ª da 2000 giri, acceleratore a fondo fino a quasi il limitatore. Parte e si ferma da solo.", "Straight, flat and empty road: in 3rd gear from 2000 rpm, full throttle until almost the rev limiter. It starts and stops by itself.")
    }
    Text(status, color = if (d.running) Pal.amber else Pal.muted, fontSize = 14.sp)
    val r = d.last
    if (r != null && r.points.size >= 2) {
        val dates = remember(L.lang) { SimpleDateFormat("d/M HH:mm", L.locale) }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(fmt(r.peakPower?.cv), color = Pal.orange, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = Digits)
            Text(t(" CV a ${fmt(r.peakPower?.rpm)}", " hp at ${fmt(r.peakPower?.rpm)}"), color = Pal.muted, fontSize = 14.sp, style = Digits, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.width(20.dp))
            Text(fmt(r.peakTorque?.nm), color = Pal.cyan, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = Digits)
            Text(t(" Nm a ${fmt(r.peakTorque?.rpm)}", " Nm at ${fmt(r.peakTorque?.rpm)}"), color = Pal.muted, fontSize = 14.sp, style = Digits, modifier = Modifier.padding(bottom = 5.dp))
        }
        DynoChart(r)
        Text(
            t("In ${r.gear}ª, ${dates.format(Date(r.at))}. Calcolata dall'accelerazione con $massKg kg a bordo (si cambia nella scheda Impostazioni): ", "In gear ${r.gear}, ${dates.format(Date(r.at))}. Computed from acceleration with $massKg kg on board (change it in Settings): ") +
                t("salite, vento e passeggeri spostano il risultato.", "hills, wind and passengers shift the result."),
            color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Curve di potenza (arancio) e coppia (azzurro) sui giri, ognuna con la sua scala. */
@Composable
private fun DynoChart(r: DynoResult) {
    val pts = r.points
    Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 10.dp)) {
        val lo = pts.first().rpm
        val hi = pts.last().rpm
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(Pal.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        fun x(rpm: Double) = (size.width * (rpm - lo) / (hi - lo)).toFloat()
        fun curve(v: (DynoPoint) -> Double, color: Color) {
            val top = pts.maxOf(v) * 1.1
            val path = Path()
            pts.forEachIndexed { i, p ->
                val y = (size.height * (1 - v(p) / top)).toFloat()
                if (i == 0) path.moveTo(x(p.rpm), y) else path.lineTo(x(p.rpm), y)
            }
            drawPath(path, color, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        }
        curve({ it.nm }, Pal.cyan)
        curve({ it.cv }, Pal.orange)
    }
    Row(Modifier.fillMaxWidth()) {
        Text(t("${fmt(pts.first().rpm)} giri", "${fmt(pts.first().rpm)} rpm"), color = Pal.muted, fontSize = 12.sp, style = Digits, modifier = Modifier.weight(1f))
        Text(t("${fmt(pts.last().rpm)} giri", "${fmt(pts.last().rpm)} rpm"), color = Pal.muted, fontSize = 12.sp, style = Digits)
    }
}

@Composable
private fun ColumnScope.TripsView(trips: List<Trip>) {
    if (trips.isEmpty()) {
        Text(
            t("I viaggi si salvano da soli, dall'accensione a quando spegni il motore: km, tempo, consumo e quanto hai speso.", "Trips are saved by themselves, from ignition to engine off: km, time, fuel and how much you spent."),
            color = Pal.muted, fontSize = 14.sp,
        )
        return
    }
    val month = remember(trips) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        trips.filter { it.start >= cal.timeInMillis }
    }
    val km = month.sumOf { it.km }
    val liters = month.mapNotNull { it.liters }.sum()
    val cost = month.mapNotNull { it.cost }.sum()
    Text(
        t("Questo mese: ${month.size} viaggi · ${fmt(km)} km · ${fmt(liters, 1)} L · ${fmt(cost, 2)} €", "This month: ${month.size} trips · ${fmt(km)} km · ${fmt(liters, 1)} L · ${fmt(cost, 2)} €"),
        color = Pal.text, fontSize = 15.sp, style = Digits, modifier = Modifier.padding(bottom = 6.dp),
    )
    val dates = remember(L.lang) { SimpleDateFormat("EEE d/M HH:mm", L.locale) }
    trips.take(12).forEach { t ->
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(Pal.track))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dates.format(Date(t.start)).replaceFirstChar { it.uppercase() }, color = Pal.text, fontSize = 15.sp)
                val mins = ((t.end - t.start) / 60_000).toInt()
                Text(
                    t("${fmt(t.km, 1)} km · ${mins / 60}:${"%02d".format(mins % 60)} · media ${fmt(t.avgKmh)} km/h · max ${fmt(t.maxKmh)}", "${fmt(t.km, 1)} km · ${mins / 60}:${"%02d".format(mins % 60)} · avg ${fmt(t.avgKmh)} km/h · max ${fmt(t.maxKmh)}"),
                    color = Pal.muted, fontSize = 13.sp, style = Digits,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${fmt(t.cost, 2)} €", color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = Digits)
                Text("${fmt(t.l100, 1)} L/100", color = Pal.muted, fontSize = 13.sp, style = Digits)
            }
        }
    }
}
