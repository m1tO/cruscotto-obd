package com.mithole.cruscotto.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val RPM_START = 135f
private const val RPM_SWEEP = 270f

private const val B_MIN = -1.0f
private const val B_START = 150f
private const val B_SWEEP = 240f

private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val rad = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())
}

/** Testo centrato in [at]; con [glow] si accende di un alone del suo colore. */
private fun DrawScope.textAt(
    tm: TextMeasurer, text: String, at: Offset, px: Float, color: Color, weight: FontWeight = FontWeight.Normal,
    glow: Color? = null, spacing: Float = 0f,
) {
    val style = TextStyle(
        color = color, fontSize = px.toSp(), fontWeight = weight, fontFamily = Tech, fontFeatureSettings = "tnum",
        letterSpacing = (px * spacing).toSp(),
        shadow = glow?.let { Shadow(it, Offset.Zero, px * 0.3f) },
    )
    val l = tm.measure(text, style)
    drawText(l, topLeft = Offset(at.x - l.size.width / 2f, at.y - l.size.height / 2f))
}

private fun mix(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k)
}

/** Colore del contagiri lungo la scala: ciano, poi viola salendo, magenta verso la zona rossa, rosso dentro. */
private fun rpmColorAt(v: Float, red: Float): Color = when {
    v >= red -> Pal.red
    v >= red * 0.6f -> mix(Pal.violet, Pal.magenta, (v - red * 0.6f) / (red * 0.4f))
    else -> mix(Pal.cyan, Pal.violet, v / (red * 0.6f))
}

private fun rpmColor(rpm: Float, red: Float) = when {
    rpm >= red -> Pal.red
    rpm >= red - 1250f -> Pal.magenta
    else -> Pal.text
}

/**
 * Arco fatto di segmenti, come una barra a led. [lit] dice se il segmento al valore v è acceso, [color] il suo colore,
 * [off] il colore da spento. I segmenti accesi hanno un alone.
 */
private fun DrawScope.segments(
    c: Offset, r: Float, width: Float, start: Float, sweep: Float, n: Int,
    valueAt: (Float) -> Float, lit: (Float) -> Boolean, color: (Float) -> Color, off: (Float) -> Color,
) {
    val step = sweep / n
    val seg = step * 0.66f
    val box = Rect(c, r)
    val glow = Rect(c, r)
    for (i in 0 until n) {
        val v = valueAt((i + 0.5f) / n)
        val a0 = start + i * step + (step - seg) / 2
        if (lit(v)) {
            val col = color(v)
            drawArc(col.copy(alpha = 0.22f), a0 - step * 0.1f, seg + step * 0.2f, false, glow.topLeft, glow.size, style = Stroke(width * 1.9f))
            drawArc(col, a0, seg, false, box.topLeft, box.size, style = Stroke(width))
        } else {
            drawArc(off(v), a0, seg, false, box.topLeft, box.size, style = Stroke(width))
        }
    }
}

private fun boostColor(bar: Float, red: Float?) = when {
    bar < 0f -> Pal.cyan
    red == null || bar < red -> Pal.orange
    else -> Pal.red
}

private const val LEDS = 10

/** Colore del led [i] della shift light: verdi, poi gialli, poi rossi. */
private fun ledColor(i: Int) = when {
    i < 4 -> Pal.green
    i < 7 -> Pal.amber
    else -> Pal.red
}

/** Lampeggio della shift light oltre il punto di cambiata. */
@Composable
fun rememberBlink(): State<Float> = rememberInfiniteTransition(label = "shift").animateFloat(
    1f, 0.15f, infiniteRepeatable(tween(110, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
)

/** Fila di led centrata in [center]: si accendono negli ultimi 2500 giri prima di [shift], oltre lampeggiano tutti rossi. */
fun DrawScope.shiftLights(center: Offset, gap: Float, radius: Float, rpm: Float?, shift: Int, blink: Float) {
    val ledFrom = shift - 2500f
    val over = rpm != null && rpm >= shift
    for (i in 0 until LEDS) {
        val at = Offset(center.x + (i - (LEDS - 1) / 2f) * gap, center.y)
        val lit = rpm != null && rpm >= ledFrom + (shift - ledFrom) * i / LEDS
        val col = if (over) Pal.red.copy(alpha = blink) else if (lit) ledColor(i) else Pal.track
        if (lit || over) drawCircle(col.copy(alpha = col.alpha * 0.25f), radius * 1.6f, at)
        drawCircle(col, radius, at)
    }
}

/**
 * Contagiri ad arco con la velocità grande al centro; fondo scala e zona rossa dipendono dal motore.
 * Sopra la velocità c'è la shift light: i led si accendono negli ultimi 2500 giri prima di [shift] e lì lampeggiano tutti.
 */
@Composable
fun RpmGauge(rpm: Double?, speed: Double?, max: Int, red: Int, shift: Int, modifier: Modifier = Modifier) {
    val rpmMax = max.toFloat()
    val rpmRed = red.toFloat()
    val a by animateFloatAsState(
        (rpm ?: 0.0).toFloat().coerceIn(0f, rpmMax), tween(280, easing = LinearEasing), label = "rpm",
    )
    val blink by rememberBlink()
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val d = min(size.width, size.height)
        val stroke = d * 0.055f
        val r = d / 2f - stroke * 1.4f
        val c = Offset(size.width / 2f, size.height / 2f + d * 0.04f)
        fun ang(v: Float) = RPM_START + RPM_SWEEP * v / rpmMax
        val now = if (rpm != null) a else -1f

        // Fondo: un disco appena illuminato dal colore dei giri, più acceso quando si sale.
        val heat = if (rpm != null) rpmColorAt(a, rpmRed) else Pal.cyan
        drawCircle(Brush.radialGradient(listOf(heat.copy(alpha = 0.10f + 0.12f * (now / rpmMax).coerceAtLeast(0f)), Color.Transparent), c, r), r, c)
        // Anelli sottili, esterno e interno: danno profondità da strumento.
        val outer = Rect(c, r + stroke * 1.1f)
        drawArc(Pal.track, RPM_START, RPM_SWEEP, false, outer.topLeft, outer.size, style = Stroke(stroke * 0.07f))
        drawArc(Pal.red.copy(alpha = 0.8f), ang(rpmRed), RPM_START + RPM_SWEEP - ang(rpmRed), false, outer.topLeft, outer.size, style = Stroke(stroke * 0.16f))
        val inner = Rect(c, r - stroke * 3.3f)
        drawArc(Pal.cyan.copy(alpha = 0.18f), RPM_START, RPM_SWEEP, false, inner.topLeft, inner.size, style = Stroke(stroke * 0.05f))

        segments(
            c, r, stroke, RPM_START, RPM_SWEEP, 64, valueAt = { it * rpmMax }, lit = { it <= now },
            color = { rpmColorAt(it, rpmRed) }, off = { if (it >= rpmRed) Pal.red.copy(alpha = 0.22f) else Pal.track },
        )
        for (i in 0..max / 500) {
            val v = i * 500f
            val major = i % 2 == 0
            val col = if (v >= rpmRed) Pal.red else Pal.muted
            drawLine(
                col.copy(alpha = if (major) 0.9f else 0.5f),
                polar(c, r - stroke * 0.9f, ang(v)),
                polar(c, r - stroke * (if (major) 1.5f else 1.15f), ang(v)),
                strokeWidth = stroke * (if (major) 0.09f else 0.05f),
            )
            if (major) textAt(tm, "${i / 2}", polar(c, r - stroke * 2.3f, ang(v)), d * 0.055f, col, FontWeight.Medium)
        }
        // Lancetta: una tacca luminosa che attraversa l'arco nel punto dei giri.
        if (rpm != null) {
            val tip = rpmColorAt(a, rpmRed)
            drawLine(tip.copy(alpha = 0.3f), polar(c, r - stroke * 1.1f, ang(a)), polar(c, r + stroke * 1.25f, ang(a)), strokeWidth = stroke * 0.45f, cap = StrokeCap.Round)
            drawLine(Color.White, polar(c, r - stroke * 0.95f, ang(a)), polar(c, r + stroke * 1.1f, ang(a)), strokeWidth = stroke * 0.14f, cap = StrokeCap.Round)
        }
        // Shift light: si legge con la coda dell'occhio, senza guardare i numeri.
        shiftLights(Offset(c.x, c.y - d * 0.205f), d * 0.034f, d * 0.0105f, if (rpm != null) a else null, shift, blink)
        textAt(tm, fmt(speed), Offset(c.x, c.y - d * 0.025f), d * 0.27f, Pal.text, FontWeight.Bold, glow = Pal.cyan.copy(alpha = 0.55f))
        textAt(tm, "KM/H", Offset(c.x, c.y + d * 0.135f), d * 0.04f, Pal.cyan, FontWeight.Medium, spacing = 0.3f)
        textAt(
            tm, fmt(rpm), Offset(c.x, c.y + r * 0.74f), d * 0.075f,
            if (rpm != null) rpmColor(a, rpmRed) else Pal.muted, FontWeight.SemiBold,
        )
        textAt(tm, "GIRI/MIN", Offset(c.x, c.y + r * 0.74f + d * 0.06f), d * 0.03f, Pal.muted, spacing = 0.3f)
    }
}

/** Angolo della girante, che avanza a ogni fotogramma alla velocità chiesta (gradi al secondo). */
@Composable
private fun rememberSpin(degPerSecond: Float): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    val speed by rememberUpdatedState(degPerSecond)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) angle.floatValue = (angle.floatValue + speed * (now - last) / 1e9f) % 360f
                last = now
            }
        }
    }
    return angle
}

/** Una pala curva della girante, dal raggio [from] a [to] (in frazioni di [r]), che si piega di [bend] gradi. */
private fun blade(c: Offset, r: Float, base: Float, from: Float, to: Float, bend: Float): Path {
    val path = Path()
    for (i in 0..16) {
        val s = i / 16f
        val p = polar(c, r * (from + (to - from) * s), base + bend * s.pow(1.6f))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    return path
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * Turbina stilizzata vista di fronte: chiocciola fissa e girante con pale e semipale curve.
 * [glow] da 0 a 1 accende il colore man mano che il turbo spinge.
 */
private fun DrawScope.turbine(c: Offset, r: Float, spin: Float, color: Color, glow: Float) {
    val line = r * 0.045f
    val housing = Pal.muted.copy(alpha = 0.6f)

    // Chiocciola: una spirale che fa un giro allargandosi e sfocia in alto nel condotto d'uscita, verso destra.
    val startDeg = -90f
    val turn = 360f
    val volute = Path()
    for (i in 0..60) {
        val k = i / 60f
        val p = polar(c, r * (1.1f + 0.4f * k), startDeg + turn * k)
        if (i == 0) volute.moveTo(p.x, p.y) else volute.lineTo(p.x, p.y)
    }
    val tangent = Math.toRadians((startDeg + turn + 90f).toDouble())
    val dir = Offset(cos(tangent).toFloat(), sin(tangent).toFloat()) * (r * 0.65f)
    val endOuter = polar(c, r * 1.5f, startDeg + turn)
    val endInner = polar(c, r * 1.1f, startDeg)
    volute.lineTo(endOuter.x + dir.x, endOuter.y + dir.y)
    drawPath(volute, housing, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(housing, endInner, endInner + dir, line, StrokeCap.Round)
    drawLine(housing, endInner + dir * 1.08f, endOuter + dir * 1.08f, line * 1.6f, StrokeCap.Round) // flangia
    drawCircle(housing, r * 1.04f, c, style = Stroke(line * 0.8f))

    if (glow > 0f) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f * glow), Color.Transparent), c, r * 1.25f), r * 1.25f, c)
    }
    val bladeColor = if (glow > 0f) lerpColor(Pal.cyan.copy(alpha = 0.7f), color, min(1f, 0.35f + glow)) else Pal.cyan.copy(alpha = 0.55f)
    rotate(spin, c) {
        for (i in 0 until 7) {
            val base = i * 360f / 7
            drawPath(blade(c, r, base, 0.26f, 0.97f, 62f), bladeColor, style = Stroke(r * 0.085f, cap = StrokeCap.Round))
            drawPath(
                blade(c, r, base + 180f / 7, 0.55f, 0.97f, 34f), bladeColor.copy(alpha = 0.75f),
                style = Stroke(r * 0.06f, cap = StrokeCap.Round),
            )
        }
        drawCircle(Pal.bg, r * 0.3f, c)
        drawCircle(bladeColor, r * 0.3f, c, style = Stroke(line))
        // Dado della girante.
        val nut = Path()
        for (i in 0..5) {
            val p = polar(c, r * 0.14f, i * 60f + 30f)
            if (i == 0) nut.moveTo(p.x, p.y) else nut.lineTo(p.x, p.y)
        }
        nut.close()
        drawPath(nut, bladeColor)
    }
}

/**
 * Manometro del collettore in bar relativi: azzurro in depressione, arancio in pressione, rosso oltre [red].
 * Sui motori aspirati la scala si ferma a 0 ([max] = 0) e diventa un vacuometro.
 */
@Composable
fun BoostGauge(bar: Double?, peak: Double?, max: Double, red: Double?, modifier: Modifier = Modifier) {
    val bMax = max.toFloat()
    val bRed = red?.toFloat()
    val turbo = max > 0
    val a by animateFloatAsState(
        (bar ?: 0.0).toFloat().coerceIn(B_MIN, bMax), tween(280, easing = LinearEasing), label = "boost",
    )
    val tm = rememberTextMeasurer()
    // La girante gira piano al minimo e accelera con la pressione; ferma se non arrivano dati.
    val spin by rememberSpin(if (bar == null) 0f else 70f + 560f * (a.coerceAtLeast(0f) / bMax.coerceAtLeast(0.1f)).coerceAtMost(1f))
    Canvas(modifier) {
        val d = if (turbo) min(size.width, size.height * 1.1f) else min(size.width, size.height * 1.15f)
        val stroke = d * 0.06f
        val r = d / 2f - stroke * 1.3f
        val c = Offset(size.width / 2f, size.height / 2f + d * (if (turbo) 0.06f else 0.08f))
        fun ang(v: Float) = B_START + B_SWEEP * (v - B_MIN) / (bMax - B_MIN)
        val outer = Rect(c, r + stroke * 1.1f)
        drawArc(Pal.track, B_START, B_SWEEP, false, outer.topLeft, outer.size, style = Stroke(stroke * 0.07f))
        if (bRed != null) {
            drawArc(Pal.red.copy(alpha = 0.8f), ang(bRed), B_START + B_SWEEP - ang(bRed), false, outer.topLeft, outer.size, style = Stroke(stroke * 0.16f))
        }
        val now = if (bar != null) a else null
        segments(
            c, r, stroke, B_START, B_SWEEP, 52, valueAt = { B_MIN + it * (bMax - B_MIN) },
            // Acceso tra lo zero e il valore, da una parte o dall'altra.
            lit = { v -> now != null && (if (now >= 0) v in 0f..now else v in now..0f) },
            color = { v -> if (v < 0) Pal.cyan else if (bRed != null && v >= bRed) Pal.red else mix(Pal.amber, Pal.magenta, v / bMax) },
            off = { v -> if (bRed != null && v >= bRed) Pal.red.copy(alpha = 0.22f) else Pal.track },
        )
        var v = B_MIN
        while (v <= bMax + 0.001f) {
            val major = abs(v * 2 - Math.round(v * 2)) < 0.01f
            drawLine(
                Pal.muted.copy(alpha = if (major) 0.9f else 0.5f),
                polar(c, r - stroke * 0.9f, ang(v)),
                polar(c, r - stroke * (if (major) 1.5f else 1.15f), ang(v)),
                strokeWidth = stroke * (if (major) 0.09f else 0.05f),
            )
            if (major) {
                val label = if (abs(v) < 0.01f) "0" else fmt(v.toDouble(), if (abs(v % 1f) > 0.01f) 1 else 0, signed = true)
                textAt(tm, label, polar(c, r - stroke * 2.5f, ang(v)), d * 0.05f, Pal.muted, FontWeight.Medium)
            }
            v += 0.25f
        }
        if (bar != null) {
            val e = ang(a)
            drawLine(boostColor(a, bRed).copy(alpha = 0.3f), polar(c, r - stroke * 1.1f, e), polar(c, r + stroke * 1.25f, e), strokeWidth = stroke * 0.45f, cap = StrokeCap.Round)
            drawLine(Color.White, polar(c, r - stroke * 0.95f, e), polar(c, r + stroke * 1.1f, e), strokeWidth = stroke * 0.14f, cap = StrokeCap.Round)
        }
        if (turbo && peak != null && peak > 0.05) {
            val p = ang(peak.toFloat().coerceAtMost(bMax))
            drawLine(Pal.amber, polar(c, r - stroke * 0.7f, p), polar(c, r + stroke * 0.7f, p), strokeWidth = stroke * 0.18f, cap = StrokeCap.Round)
        }
        val value = if (bar == null) "N/D" else fmt(a.toDouble(), 2, signed = true)
        if (turbo) {
            // Turbina al centro, numero sotto, nel vuoto lasciato in basso dall'arco.
            val glow = if (bar == null) 0f else max(0f, a / bMax).coerceAtMost(1f)
            turbine(Offset(c.x, c.y - d * 0.02f), d * 0.125f, spin, boostColor(a, bRed), glow)
            textAt(tm, value, Offset(c.x, c.y + d * 0.245f), d * 0.11f, Pal.text, FontWeight.Bold, glow = boostColor(a, bRed).copy(alpha = 0.5f))
            val px = d * 0.04f
            val sub = if (peak != null) "BAR TURBO  ·  " else "BAR TURBO"
            if (peak != null) {
                val maxText = "MAX ${fmt(peak, 2, signed = true)}"
                val style = TextStyle(fontSize = px.toSp(), fontFamily = Tech, fontFeatureSettings = "tnum")
                val w1 = tm.measure(sub, style).size.width
                val w2 = tm.measure(maxText, style).size.width
                val left = c.x - (w1 + w2) / 2f
                textAt(tm, sub, Offset(left + w1 / 2f, c.y + d * 0.33f), px, Pal.muted)
                textAt(tm, maxText, Offset(left + w1 + w2 / 2f, c.y + d * 0.33f), px, Pal.amber)
            } else {
                textAt(tm, sub, Offset(c.x, c.y + d * 0.33f), px, Pal.muted)
            }
        } else {
            textAt(tm, value, Offset(c.x, c.y), d * 0.125f, Pal.text, FontWeight.Bold, glow = Pal.cyan.copy(alpha = 0.5f))
            textAt(tm, "BAR COLLETTORE", Offset(c.x, c.y + d * 0.1f), d * 0.038f, Pal.muted, spacing = 0.2f)
        }
    }
}
