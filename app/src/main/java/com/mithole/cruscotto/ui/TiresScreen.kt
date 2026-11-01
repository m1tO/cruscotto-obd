package com.mithole.cruscotto.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.BleSeen
import com.mithole.cruscotto.obd.Settings
import com.mithole.cruscotto.obd.TireLevel
import com.mithole.cruscotto.obd.TireState
import com.mithole.cruscotto.obd.Wheel

fun TireLevel.color(): Color = when (this) {
    TireLevel.OK -> Pal.green
    TireLevel.LOW, TireLevel.HIGH -> Pal.amber
    TireLevel.FLAT -> Pal.red
    TireLevel.STALE, TireLevel.NONE -> Pal.muted
}

@Composable
fun TiresScreen(
    states: List<TireState>,
    devices: List<BleSeen>,
    settings: Settings,
    perms: SensorPermissions,
    onPerms: () -> Unit,
    onSettings: (Settings) -> Unit,
    onAssign: (Wheel, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!perms.scan || !perms.location) {
            Card("Permessi") {
                Text(
                    "Per sentire i sensori delle gomme servono i permessi \"Dispositivi nelle vicinanze\" e \"Posizione\". " +
                        "La posizione serve anche al GPS, per la velocità vera e il percorso delle registrazioni.",
                    color = Pal.muted, fontSize = 14.sp,
                )
                Button(onClick = onPerms, modifier = Modifier.padding(top = 10.dp)) { Text("Concedi i permessi") }
            }
        }
        Card("Pressioni") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TireBox(states.first { it.wheel == Wheel.FL })
                    TireBox(states.first { it.wheel == Wheel.RL })
                }
                CarTop(states, Modifier.width(120.dp).height(230.dp).padding(horizontal = 16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TireBox(states.first { it.wheel == Wheel.FR })
                    TireBox(states.first { it.wheel == Wheel.RR })
                }
            }
            Spacer(Modifier.height(10.dp))
            Stepper("Consigliata davanti", settings.tireFront) { onSettings(settings.copy(tireFront = it)) }
            Stepper("Consigliata dietro", settings.tireRear) { onSettings(settings.copy(tireRear = it)) }
            Text(
                "Le pressioni consigliate sono sul montante della portiera del guidatore. Si confrontano a gomme fredde: " +
                    "dopo un viaggio salgono di 0,2–0,3 bar ed è normale.",
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Card("Sensori nelle vicinanze") {
            Text(
                "Avvicina un sensore al tablet e assegnalo alla sua ruota. I tappini Bluetooth più diffusi si leggono già; " +
                    "per gli altri compaiono i dati grezzi: copiali e si aggiunge il loro formato.",
                color = Pal.muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            val shown = devices.sortedWith(compareByDescending<BleSeen> { it.reading != null }.thenByDescending { it.rssi }).take(30)
            if (shown.isEmpty()) {
                Text(if (perms.scan) "Non sento ancora niente." else "Ricerca spenta: mancano i permessi.", color = Pal.muted, fontSize = 15.sp)
            }
            shown.forEach { d -> DeviceRow(d, settings, onAssign) }
        }
    }
}

@Composable
private fun TireBox(s: TireState) {
    val c = s.level.color()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.surface2).padding(14.dp)) {
        Text(s.wheel.label.uppercase(), color = Pal.muted, fontSize = 11.sp, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(s.reading?.bar, 2), color = if (s.reading != null) c else Pal.muted, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, style = Digits)
            Spacer(Modifier.width(4.dp))
            Text("bar", color = Pal.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            listOfNotNull(
                s.reading?.tempC?.let { "${fmt(it)} °C" },
                s.reading?.batteryPct?.let { "batteria $it %" },
            ).joinToString(" · ").ifEmpty { "consigliata ${fmt(s.target, 1)} bar" },
            color = Pal.muted, fontSize = 13.sp, style = Digits,
        )
        Text(s.note, color = c, fontSize = 13.sp)
    }
}

/** L'auto vista dall'alto, con le gomme colorate come il loro stato. */
@Composable
private fun CarTop(states: List<TireState>, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Size(w * 0.72f, h)
        val left = (w - body.width) / 2
        drawRoundRect(Pal.surface2, Offset(left, 0f), body, CornerRadius(w * 0.3f))
        drawRoundRect(Pal.track, Offset(left, 0f), body, CornerRadius(w * 0.3f), style = Stroke(2.dp.toPx()))
        // Parabrezza e lunotto.
        drawRoundRect(Pal.track, Offset(left + body.width * 0.12f, h * 0.24f), Size(body.width * 0.76f, h * 0.12f), CornerRadius(8f))
        drawRoundRect(Pal.track, Offset(left + body.width * 0.15f, h * 0.74f), Size(body.width * 0.7f, h * 0.08f), CornerRadius(8f))
        val tw = w * 0.16f
        val th = h * 0.17f
        for (s in states) {
            val x = if (s.wheel == Wheel.FL || s.wheel == Wheel.RL) left - tw * 0.55f else left + body.width - tw * 0.45f
            val y = if (s.wheel.front) h * 0.12f else h * 0.68f
            drawRoundRect(s.level.color(), Offset(x, y), Size(tw, th), CornerRadius(tw * 0.35f))
        }
    }
}

@Composable
private fun Stepper(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(Math.round((value - 0.1) * 10) / 10.0) }) { Text("−") }
        Text("${fmt(value, 1)} bar", color = Pal.text, fontSize = 17.sp, style = Digits, modifier = Modifier.padding(horizontal = 12.dp))
        OutlinedButton(onClick = { onChange(Math.round((value + 0.1) * 10) / 10.0) }) { Text("+") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(d: BleSeen, s: Settings, onAssign: (Wheel, String?) -> Unit) {
    val mounted = s.tires.entries.firstOrNull { it.value == d.address }?.key
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Pal.surface2).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.name ?: "Senza nome", color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${d.rssi} dBm", color = Pal.muted, fontSize = 13.sp, style = Digits)
        }
        Text(d.address, color = Pal.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        d.reading?.let { r ->
            Text(
                "${fmt(r.bar, 2)} bar · ${fmt(r.tempC)} °C · batteria ${r.batteryPct ?: "N/D"} %",
                color = Pal.green, fontSize = 14.sp, style = Digits, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (d.raw.isNotEmpty()) {
            Text(d.raw, color = Pal.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 2.dp))
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Wheel.entries.forEach { w ->
                val sel = mounted == w
                Text(
                    w.short, color = if (sel) Pal.bg else Pal.text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) Pal.cyan else Pal.surface)
                        .clickable { onAssign(w, if (sel) null else d.address) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Riquadro gomme del cruscotto: le quattro pressioni a colpo d'occhio. */
@Composable
fun TireCard(states: List<TireState>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val none = states.all { it.level == TireLevel.NONE }
    Column(
        modifier.glass(20.dp).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("Gomme", color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (none) {
            Text("nessun sensore", color = Pal.muted, fontSize = 13.sp)
        } else {
            listOf(Wheel.FL to Wheel.FR, Wheel.RL to Wheel.RR).forEach { (l, r) ->
                Row {
                    listOf(l, r).forEach { w ->
                        val s = states.first { it.wheel == w }
                        Text(
                            fmt(s.reading?.bar, 1), color = if (s.reading != null) s.level.color() else Pal.muted,
                            fontSize = 14.sp, style = Digits, modifier = Modifier.width(44.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun rememberPermissionLauncher(onResult: () -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
