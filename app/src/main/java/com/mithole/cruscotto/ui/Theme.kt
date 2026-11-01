package com.mithole.cruscotto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.mithole.cruscotto.obd.t
import com.mithole.cruscotto.obd.L

object Pal {
    val bg = Color(0xFF04060A)
    val surface = Color(0xFF0B1119)
    val surface2 = Color(0xFF131C28)
    val track = Color(0xFF1A2431)
    val text = Color(0xFFEAF3FF)
    val muted = Color(0xFF7A889C)
    val red = Color(0xFFFF2D55)
    val amber = Color(0xFFFFB020)
    val orange = Color(0xFFFF7A1A)
    val cyan = Color(0xFF22D3EE)
    val green = Color(0xFF2EE6A6)
    val violet = Color(0xFF8B5CF6)
    val magenta = Color(0xFFFF2D95)
}

/** Il condensato di sistema di Android: numeri stretti e alti, da strumentazione digitale, senza file di font da portarsi dietro. */
private val condensed = DeviceFontFamilyName("sans-serif-condensed")

val Tech = FontFamily(
    Font(condensed, FontWeight.Light),
    Font(condensed, FontWeight.Normal),
    Font(condensed, FontWeight.Medium),
    Font(condensed, FontWeight.SemiBold),
    Font(condensed, FontWeight.Bold),
)

/** Cifre a larghezza fissa: i numeri che cambiano non fanno ballare il testo. */
val Digits = TextStyle(fontFamily = Tech, fontFeatureSettings = "tnum")

private fun TextStyle.tech() = copy(fontFamily = Tech)

private val TechTypography = Typography().run {
    copy(
        displayLarge = displayLarge.tech(), displayMedium = displayMedium.tech(), displaySmall = displaySmall.tech(),
        headlineLarge = headlineLarge.tech(), headlineMedium = headlineMedium.tech(), headlineSmall = headlineSmall.tech(),
        titleLarge = titleLarge.tech(), titleMedium = titleMedium.tech(), titleSmall = titleSmall.tech(),
        bodyLarge = bodyLarge.tech(), bodyMedium = bodyMedium.tech(), bodySmall = bodySmall.tech(),
        labelLarge = labelLarge.tech(), labelMedium = labelMedium.tech(), labelSmall = labelSmall.tech(),
    )
}

/** Riquadro "di vetro": scuro, con un bordo che si accende in alto come se prendesse luce. */
fun Modifier.glass(radius: Dp = 20.dp, accent: Color = Pal.cyan): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF0F1823), Color(0xFF080D14))))
        .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.38f), Color.White.copy(alpha = 0.03f))), shape)
}

/** Sfondo dell'app: nero-blu, un alone in alto e una griglia appena visibile. */
fun Modifier.techBackground(): Modifier = drawBehind {
    drawRect(Pal.bg)
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF0B2236).copy(alpha = 0.85f), Color.Transparent),
            center = Offset(size.width * 0.5f, 0f), radius = size.maxDimension * 0.75f,
        ),
    )
    val step = 44.dp.toPx()
    val line = Color.White.copy(alpha = 0.022f)
    var x = 0f
    while (x < size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y))
        y += step
    }
}

@Composable
fun CruscottoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.red,
            onPrimary = Color.White,
            secondary = Pal.cyan,
            background = Pal.bg,
            onBackground = Pal.text,
            surface = Pal.surface,
            onSurface = Pal.text,
            surfaceVariant = Pal.surface2,
            onSurfaceVariant = Pal.muted,
            surfaceContainer = Pal.surface,
            surfaceContainerHigh = Pal.surface2,
            surfaceContainerHighest = Pal.surface2,
            outline = Pal.track,
            outlineVariant = Pal.track,
            error = Pal.red,
        ),
        typography = TechTypography,
        content = content,
    )
}

fun fmt(v: Double?, decimals: Int = 0, signed: Boolean = false): String {
    if (v == null || v.isNaN()) return t("N/D", "N/A")
    return String.format(L.locale, if (signed) "%+.${decimals}f" else "%.${decimals}f", v)
}

/** Icone Material monocrome: prendono il colore del testo intorno. */
object Ic {
    private fun icon(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val speed by lazy {
        icon("speed", "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z")
    }
    val chart by lazy {
        icon("chart", "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z")
    }
    val wrench by lazy {
        icon("wrench", "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z")
    }
    val car by lazy {
        icon("car", "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11L5,11z")
    }
    val warning by lazy {
        icon("warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    }
    val check by lazy {
        icon("check", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    }
    val refresh by lazy {
        icon("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    }
    val delete by lazy {
        icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }
    val bluetooth by lazy {
        icon("bluetooth", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z")
    }
    val wifi by lazy {
        icon("wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z")
    }
    val timer by lazy {
        icon("timer", "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    }
    // Gomma vista di lato: battistrada, fianco vuoto e mozzo. I due cerchi interni girano in verso opposto per bucare.
    val tire by lazy {
        icon("tire", "M12,2A10,10 0 1,0 12,22A10,10 0 1,0 12,2zM12,6A6,6 0 1,1 12,18A6,6 0 1,1 12,6zM12,9.5A2.5,2.5 0 1,0 12,14.5A2.5,2.5 0 1,0 12,9.5z")
    }
    val copy by lazy {
        icon("copy", "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z")
    }
}
