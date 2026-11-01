package com.mithole.cruscotto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.Advice
import com.mithole.cruscotto.obd.Level
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Metric
import com.mithole.cruscotto.obd.Profile
import com.mithole.cruscotto.obd.Status
import com.mithole.cruscotto.obd.TireState
import com.mithole.cruscotto.obd.t

/** Un riquadro in basso; [level] da 0 a 1 riempie la barretta sotto il numero (null = niente barretta). */
data class TileData(val label: String, val value: String, val unit: String, val color: Color = Pal.text, val level: Float? = null)

/** Dove sta [v] tra [lo] e [hi], da 0 a 1. */
private fun level(v: Double?, lo: Double, hi: Double): Float? = v?.let { ((it - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) }

private fun coolantColor(v: Double?) = when {
    v == null -> Pal.text
    v < 60 -> Pal.cyan
    v > 108 -> Pal.red
    v > 102 -> Pal.amber
    else -> Pal.text
}

private fun voltColor(v: Double?, rpm: Double?): Color {
    if (v == null) return Pal.text
    val running = (rpm ?: 0.0) > 400
    return if (running) {
        when {
            v < 12.4 || v > 15.0 -> Pal.red
            v < 13.2 -> Pal.amber
            else -> Pal.green
        }
    } else {
        when {
            v < 12.0 -> Pal.red
            v < 12.4 -> Pal.amber
            else -> Pal.text
        }
    }
}

fun tilesFor(live: Live): List<TileData> {
    val v = live.values
    val speed = v[Metric.SPEED]
    val fuel = v[Metric.FUEL_RATE]
    val consumption = when {
        live.instantL100 != null -> TileData(t("Consumo", "Fuel use"), fmt(live.instantL100, 1), "L/100 km", level = level(live.instantL100, 0.0, 30.0))
        fuel != null && (speed ?: 0.0) < 5 -> TileData(t("Consumo", "Fuel use"), fmt(fuel, 1), "L/h", level = level(fuel, 0.0, 5.0))
        else -> TileData(t("Consumo", "Fuel use"), t("N/D", "N/A"), "L/100 km")
    }
    val iat = v[Metric.IAT]
    return listOf(
        TileData(t("Acqua", "Coolant"), fmt(v[Metric.COOLANT]), "°C", coolantColor(v[Metric.COOLANT]), level(v[Metric.COOLANT], 40.0, 120.0)),
        TileData(t("Aria aspirata", "Intake air"), fmt(iat), "°C", if ((iat ?: 0.0) > 55) Pal.amber else Pal.text, level(iat, 0.0, 80.0)),
        TileData(t("Batteria", "Battery"), fmt(v[Metric.VOLT], 1), "V", voltColor(v[Metric.VOLT], v[Metric.RPM]), level(v[Metric.VOLT], 11.0, 15.0)),
        TileData(t("Carico motore", "Engine load"), fmt(v[Metric.LOAD]), "%", level = level(v[Metric.LOAD], 0.0, 100.0)),
        consumption,
        TileData(t("Media viaggio", "Trip average"), fmt(live.tripL100, 1), "L/100 km", level = level(live.tripL100, 0.0, 20.0)),
        TileData(t("Anticipo", "Ignition timing"), fmt(v[Metric.TIMING], 1), "°", level = level(v[Metric.TIMING], -10.0, 40.0)),
        TileData(t("Acceleratore", "Accelerator"), fmt(v[Metric.PEDAL] ?: v[Metric.THROTTLE]), "%", level = level(v[Metric.PEDAL] ?: v[Metric.THROTTLE], 0.0, 100.0)),
    )
}

@Composable
fun DashboardScreen(
    live: Live,
    status: Status?,
    profile: Profile,
    onErrors: () -> Unit,
    modifier: Modifier = Modifier,
    tires: List<TireState> = emptyList(),
    onTires: () -> Unit = {},
    onHud: () -> Unit = {},
) {
    val v = live.values
    val tiles = tilesFor(live)
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp)) {
        if (maxWidth > maxHeight) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                live.advice?.let { AdviceStrip(it) }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Panel(Modifier.weight(1.2f).fillMaxHeight()) { RpmPanel(live, profile, onHud) }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Panel(Modifier.weight(1f).fillMaxWidth()) {
                            BoostGauge(v[Metric.BOOST], live.peaks[Metric.BOOST], profile.boostMax, profile.boostRed, Modifier.fillMaxSize())
                        }
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            MilCard(status, onErrors, Modifier.weight(1.3f).fillMaxHeight())
                            TireCard(tires, onTires, Modifier.weight(0.8f).fillMaxHeight())
                            TripCard(live, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                TileGrid(tiles, columns = 4)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                live.advice?.let { AdviceStrip(it) }
                Panel(Modifier.fillMaxWidth().aspectRatio(1.25f)) { RpmPanel(live, profile, onHud) }
                Panel(Modifier.fillMaxWidth().aspectRatio(2.1f)) {
                    BoostGauge(v[Metric.BOOST], live.peaks[Metric.BOOST], profile.boostMax, profile.boostRed, Modifier.fillMaxSize())
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MilCard(status, onErrors, Modifier.weight(1.3f).fillMaxHeight())
                    TireCard(tires, onTires, Modifier.weight(0.8f).fillMaxHeight())
                    TripCard(live, Modifier.weight(1f).fillMaxHeight())
                }
                TileGrid(tiles, columns = 2)
            }
        }
    }
}

/** Contagiri con la marcia inserita nell'angolo in alto a sinistra. */
@Composable
private fun RpmPanel(live: Live, profile: Profile, onHud: () -> Unit) {
    val v = live.values
    Box(Modifier.fillMaxSize()) {
        RpmGauge(v[Metric.RPM], v[Metric.SPEED], profile.rpmMax, profile.rpmRed, profile.shiftRpm, Modifier.fillMaxSize())
        GearBadge(live, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 12.dp))
        Text(
            "HUD", color = Pal.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).clip(RoundedCornerShape(12.dp))
                .background(Pal.surface2).clickable(onClick = onHud).padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** Consiglio del momento: motore freddo, turbo da raffreddare. */
@Composable
private fun AdviceStrip(a: Advice) {
    val c = when (a.level) {
        Level.BAD -> Pal.red
        Level.OK -> Pal.green
        else -> Pal.amber
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (a.level == Level.OK) Ic.check else Ic.warning, null, tint = c, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(a.title, color = c, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(a.text, color = Pal.text, fontSize = 15.sp, style = Digits)
    }
}

@Composable
private fun GearBadge(live: Live, modifier: Modifier = Modifier) {
    val learning = live.gearRatios.isEmpty()
    val moving = (live.values[Metric.SPEED] ?: 0.0) >= 5
    val text = live.gear?.toString() ?: if (!learning && moving) "N" else "–"
    Column(modifier) {
        Text(t("MARCIA", "GEAR"), color = Pal.muted, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(text, color = if (live.gear != null) Pal.text else Pal.muted, fontSize = 64.sp, fontWeight = FontWeight.SemiBold, style = Digits)
        if (learning) Text(t("sto imparando\ni rapporti", "learning\nthe ratios"), color = Pal.muted, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.glass(24.dp).padding(8.dp)) { content() }
}

@Composable
fun TileGrid(tiles: List<TileData>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { TileView(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun TileView(t: TileData, modifier: Modifier = Modifier) {
    Column(
        modifier.glass(20.dp).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(t.label.uppercase(), color = Pal.muted, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t.value, color = t.color, fontSize = 34.sp, fontWeight = FontWeight.Bold, style = Digits)
            Spacer(Modifier.width(6.dp))
            Text(t.unit, color = Pal.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        // Barretta di livello: il colore del valore, che sfuma dal ciano.
        val accent = if (t.color == Pal.text) Pal.cyan else t.color
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val h = size.height
            drawRoundRect(Pal.track, size = size, cornerRadius = CornerRadius(h / 2))
            val k = t.level ?: return@Canvas
            if (k <= 0f) return@Canvas
            val w = size.width * k
            drawRoundRect(accent.copy(alpha = 0.25f), topLeft = Offset(0f, -h), size = Size(w, h * 3), cornerRadius = CornerRadius(h * 1.5f))
            drawRoundRect(
                Brush.horizontalGradient(listOf(Pal.cyan.copy(alpha = 0.5f), accent), endX = w),
                size = Size(w, h), cornerRadius = CornerRadius(h / 2),
            )
        }
    }
}

@Composable
private fun MilCard(status: Status?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (icon, color, title, sub) = when {
        status == null -> Quad(Ic.warning, Pal.muted, t("Spia motore", "Check engine"), t("N/D", "N/A"))
        status.mil -> Quad(Ic.warning, Pal.amber, t("Spia motore", "Check engine"), t("accesa · ", "on · ") + errorsText(status.dtcCount))
        status.dtcCount > 0 -> Quad(Ic.warning, Pal.amber, t("Spia motore", "Check engine"), t("spenta · ", "off · ") + errorsText(status.dtcCount))
        else -> Quad(Ic.check, Pal.green, t("Spia motore", "Check engine"), t("spenta · nessun errore", "off · no faults"))
    }
    Row(
        modifier.glass(20.dp).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = color, fontSize = 13.sp)
        }
    }
}

private fun errorsText(n: Int) = if (n == 1) t("1 errore", "1 fault") else t("$n errori", "$n faults")

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun TripCard(live: Live, modifier: Modifier = Modifier) {
    Column(modifier.glass(20.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(t("Viaggio", "Trip"), color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("${fmt(live.tripKm, 1)} km", color = Pal.muted, fontSize = 13.sp, style = Digits)
        Text("max ${fmt(live.peaks[Metric.SPEED])} km/h", color = Pal.muted, fontSize = 13.sp, style = Digits)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
