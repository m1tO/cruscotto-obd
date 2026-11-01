package com.mithole.cruscotto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.Level
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Metric
import com.mithole.cruscotto.obd.Profile
import com.mithole.cruscotto.obd.t

/**
 * Modalità HUD: col tablet appoggiato sul cruscotto, di notte, il parabrezza riflette i numeri.
 * Per questo è tutto specchiato, su nero puro, con pochi elementi enormi. Tocco: esce. Pressione lunga: specchia o no.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HudScreen(live: Live, profile: Profile, onExit: () -> Unit, startMirrored: Boolean = true) {
    var mirrored by rememberSaveable { mutableStateOf(startMirrored) }
    val v = live.values
    val rpm = v[Metric.RPM]
    val blink by rememberBlink()
    val advice = live.advice?.takeIf { it.level != Level.OK }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .combinedClickable(onClick = onExit, onLongClick = { mirrored = !mirrored }),
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                shiftLights(Offset(size.width / 2, size.height / 2), size.height * 1.15f, size.height * 0.36f, rpm?.toFloat(), profile.shiftRpm, blink)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmt(v[Metric.SPEED]), color = Color.White, fontSize = 230.sp, fontWeight = FontWeight.Bold,
                    style = Digits, lineHeight = 230.sp,
                )
                Spacer(Modifier.width(36.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(live.gear?.toString() ?: "–", color = Pal.cyan, fontSize = 110.sp, fontWeight = FontWeight.SemiBold, style = Digits)
                    Text("km/h", color = Pal.muted, fontSize = 28.sp)
                }
            }
            // Barra dei giri: piena al fondo scala, rossa nella zona rossa.
            Canvas(Modifier.fillMaxWidth(0.8f).height(22.dp)) {
                val k = ((rpm ?: 0.0) / profile.rpmMax).toFloat().coerceIn(0f, 1f)
                val red = profile.rpmRed.toFloat() / profile.rpmMax
                drawRoundRect(Pal.track, size = size, cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    if (k >= red) Pal.red else Color.White, size = Size(size.width * k, size.height), cornerRadius = CornerRadius(size.height / 2),
                )
                drawRect(Pal.red.copy(alpha = 0.5f), Offset(size.width * red, size.height - 4.dp.toPx()), Size(size.width * (1 - red), 4.dp.toPx()))
            }
            if (advice != null) {
                Text(
                    advice.text, color = if (advice.level == Level.BAD) Pal.red else Pal.amber, fontSize = 34.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        Text(
            t("Tocca per uscire · tieni premuto per ${if (mirrored) "non specchiare" else "specchiare"}", "Tap to exit · long-press to ${if (mirrored) "unmirror" else "mirror"}"),
            color = Pal.muted.copy(alpha = 0.6f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}
