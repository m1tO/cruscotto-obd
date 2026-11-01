package com.mithole.cruscotto.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.History
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Metric
import com.mithole.cruscotto.obd.PathPoint
import com.mithole.cruscotto.obd.Recorder
import com.mithole.cruscotto.obd.Recording
import com.mithole.cruscotto.obd.Sample
import com.mithole.cruscotto.obd.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import com.mithole.cruscotto.obd.t
import com.mithole.cruscotto.obd.L

private val ORDER = listOf(
    Metric.RPM, Metric.SPEED, Metric.BOOST, Metric.MAP, Metric.COOLANT, Metric.IAT, Metric.VOLT, Metric.LOAD,
    Metric.PEDAL, Metric.THROTTLE, Metric.TIMING, Metric.MAF, Metric.FUEL_RATE, Metric.STFT, Metric.LTFT,
    Metric.OIL, Metric.FUEL_LEVEL, Metric.AMBIENT,
)

private fun colorOf(m: Metric) = when (m) {
    Metric.RPM -> Pal.text
    Metric.SPEED -> Pal.cyan
    Metric.BOOST -> Pal.orange
    Metric.COOLANT, Metric.OIL -> Pal.green
    Metric.VOLT -> Pal.amber
    Metric.STFT, Metric.LTFT -> Pal.violet
    else -> Pal.cyan
}

private fun minSpan(m: Metric) = when (m) {
    Metric.BOOST -> 0.2f
    Metric.VOLT -> 0.4f
    else -> 2f
}

/** Stato e comandi delle registrazioni, passati dall'esterno così le schermate si provano senza file veri. */
class Recordings(
    val since: Long? = null,
    val list: List<Recording> = emptyList(),
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val delete: (Recording) -> Unit = {},
)

private fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun size(bytes: Long) = if (bytes < 1_000_000) "${fmt(bytes / 1000.0)} kB" else "${fmt(bytes / 1_000_000.0, 1)} MB"

@Composable
fun ChartsScreen(
    live: Live, history: History, version: Long, rec: Recordings = Recordings(), modifier: Modifier = Modifier,
    startSaved: Boolean = false,
) {
    var saved by rememberSaveable { mutableStateOf(startSaved) }
    var open by remember { mutableStateOf<Recording?>(null) }
    Column(modifier.fillMaxSize()) {
        val current = open
        if (current != null) {
            TrackView(current, onBack = { open = null })
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Segment(t("In diretta", "Live"), !saved) { saved = false }
            Spacer(Modifier.width(8.dp))
            Segment(t("Registrazioni (${rec.list.size})", "Recordings (${rec.list.size})"), saved) { saved = true }
            Spacer(Modifier.weight(1f))
            RecordButton(rec)
        }
        if (saved) RecordingList(rec, onOpen = { open = it }) else LiveCharts(live, history, version)
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Pal.bg else Pal.text, fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) Pal.cyan else Pal.surface2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecordButton(rec: Recordings) {
    val since = rec.since
    if (since == null) {
        Button(onClick = rec.start, colors = ButtonDefaults.buttonColors(containerColor = Pal.surface2, contentColor = Pal.text)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Pal.red))
            Spacer(Modifier.width(8.dp))
            Text(t("Registra", "Record"))
        }
    } else {
        val elapsed by produceState(0.0, since) {
            while (true) {
                value = (System.currentTimeMillis() - since) / 1000.0
                delay(1000)
            }
        }
        Button(onClick = rec.stop, colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White)) {
            Box(Modifier.size(12.dp).background(Color.White))
            Spacer(Modifier.width(8.dp))
            Text(t("Ferma · ${clock(elapsed)}", "Stop · ${clock(elapsed)}"), style = Digits)
        }
    }
}

@Composable
private fun LiveCharts(live: Live, history: History, version: Long) {
    val metrics = ORDER.filter { it in live.available }
    if (metrics.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(t("Nessun dato ancora.", "No data yet."), color = Pal.muted, fontSize = 16.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(400.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(metrics, key = { it.name }) { m ->
            val samples = remember(version, m) { history.snapshot(m) }
            ChartCard(m, samples, live.values[m], null, History.WINDOW_MS, t("ultimi 3 minuti", "last 3 minutes"))
        }
    }
}

@Composable
private fun RecordingList(rec: Recordings, onOpen: (Recording) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<Recording?>(null) }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } } }
            }
        }
    }
    if (rec.list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                t("Nessuna registrazione. Premi Registra prima di una tirata o di un viaggio: ogni lettura finisce in un file ", "No recordings. Press Record before a pull or a trip: every reading goes into a file ") +
                    t("che puoi rivedere qui, oppure esportare e aprire con Excel.", "you can review here, or export and open in Excel."),
                color = Pal.muted, fontSize = 16.sp,
            )
        }
        return
    }
    val dates = remember(L.lang) { SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", L.locale) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(420.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(rec.list, key = { it.file.name }) { r ->
            val recording = rec.since != null && r == rec.list.first()
            Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
                Text(dates.format(Date(r.startedAt)).replaceFirstChar { it.uppercase() }, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (recording) t("in registrazione…", "recording…") else "${clock(r.seconds)} · ${size(r.bytes)}",
                    color = if (recording) Pal.red else Pal.muted, fontSize = 13.sp, style = Digits,
                )
                if (!recording) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpen(r) }) { Text(t("Rivedi", "View")) }
                        OutlinedButton(onClick = { exporting = r; export.launch(r.file.name) }) { Text(t("Esporta", "Export")) }
                        if (confirmDelete == r) {
                            Button(
                                onClick = { confirmDelete = null; rec.delete(r) },
                                colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White),
                            ) { Text(t("Conferma", "Confirm")) }
                        } else {
                            TextButton(onClick = { confirmDelete = r }) { Text(t("Elimina", "Delete"), color = Pal.muted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackView(r: Recording, onBack: () -> Unit) {
    var track by remember(r) { mutableStateOf<Track?>(null) }
    LaunchedEffect(r) { track = withContext(Dispatchers.IO) { runCatching { Recorder.read(r.file) }.getOrNull() } }
    val dates = remember(L.lang) { SimpleDateFormat("d MMMM yyyy, HH:mm", L.locale) }
    val t = track
    LazyVerticalGrid(
        columns = GridCells.Adaptive(400.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(t("‹ Registrazioni", "‹ Recordings"), color = Pal.text) }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${dates.format(Date(r.startedAt))} · ${clock(t?.seconds ?: r.seconds)}",
                    color = Pal.muted, fontSize = 14.sp, style = Digits,
                )
            }
        }
        if (t == null) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(t("Carico…", "Loading…"), color = Pal.muted, fontSize = 16.sp) }
        } else {
            if (t.path.size >= 2) item(span = { GridItemSpan(maxLineSpan) }) { PathCard(t.path) }
            val window = max(1_000L, (t.seconds * 1000).toLong())
            items(ORDER.filter { (t.series[it]?.size ?: 0) >= 2 }, key = { it.name }) { m ->
                val samples = t.series.getValue(m)
                ChartCard(m, samples, samples.maxOf { it.v }.toDouble(), "max", window, t("su ${clock(t.seconds)}", "over ${clock(t.seconds)}"))
            }
        }
    }
}

@Composable
private fun ChartCard(m: Metric, samples: List<Sample>, headline: Double?, headlineLabel: String?, windowMs: Long, span: String) {
    val color = colorOf(m)
    val lo = samples.minOfOrNull { it.v }
    val hi = samples.maxOfOrNull { it.v }
    val signed = m == Metric.BOOST
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.label, color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (headlineLabel != null) {
                Text(headlineLabel, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp, bottom = 3.dp))
            }
            Text(fmt(headline, m.decimals, signed), color = color, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, style = Digits)
            Spacer(Modifier.padding(start = 4.dp))
            Text(m.unit, color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(
            t("min ${fmt(lo?.toDouble(), m.decimals, signed)} · max ${fmt(hi?.toDouble(), m.decimals, signed)} · $span", "min ${fmt(lo?.toDouble(), m.decimals, signed)} · max ${fmt(hi?.toDouble(), m.decimals, signed)} · $span"),
            color = Pal.muted, fontSize = 12.sp, style = Digits,
        )
        Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 10.dp)) {
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(Pal.track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            if (samples.size < 2 || lo == null || hi == null) return@Canvas
            val pad = max((hi - lo) * 0.12f, minSpan(m) / 2)
            val bottom = lo - pad
            val top = hi + pad
            fun x(t: Long) = size.width * (1f - (samples.last().t - t).toFloat() / windowMs)
            fun y(v: Float) = size.height * (1f - (v - bottom) / (top - bottom))
            if (bottom < 0 && top > 0) {
                drawLine(Pal.muted.copy(alpha = 0.5f), Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1.dp.toPx())
            }
            val line = Path()
            samples.forEachIndexed { i, s -> if (i == 0) line.moveTo(x(s.t), y(s.v)) else line.lineTo(x(s.t), y(s.v)) }
            val fill = Path().apply {
                addPath(line)
                lineTo(x(samples.last().t), size.height)
                lineTo(x(samples.first().t), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(line, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            drawCircle(color, 3.5.dp.toPx(), Offset(x(samples.last().t), y(samples.last().v)))
        }
    }
}

/** Il percorso dal GPS, senza mappa sotto: la forma del giro colorata dalla velocità (azzurro piano, rosso forte). */
@Composable
private fun PathCard(path: List<PathPoint>) {
    val top = path.maxOf { it.kmh }.coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Percorso", "Route"), color = Pal.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Pal.green))
            Text(t(" partenza   ", " start   "), color = Pal.muted, fontSize = 12.sp)
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Pal.text))
            Text(t(" arrivo", " finish"), color = Pal.muted, fontSize = 12.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(280.dp).padding(top = 10.dp)) {
            val k = cos(Math.toRadians(path.map { it.lat }.average()))
            val xs = path.map { it.lon * k }
            val ys = path.map { it.lat }
            val w = (xs.max() - xs.min()).coerceAtLeast(1e-6)
            val h = (ys.max() - ys.min()).coerceAtLeast(1e-6)
            val scale = min(size.width / w, size.height / h).toFloat()
            val ox = (size.width - w.toFloat() * scale) / 2
            val oy = (size.height - h.toFloat() * scale) / 2
            fun at(i: Int) = Offset(ox + ((xs[i] - xs.min()) * scale).toFloat(), size.height - oy - ((ys[i] - ys.min()) * scale).toFloat())
            for (i in 1 until path.size) {
                val f = path[i].kmh / top
                val c = if (f < 0.5f) lerpC(Pal.cyan, Pal.amber, f * 2) else lerpC(Pal.amber, Pal.red, (f - 0.5f) * 2)
                drawLine(c, at(i - 1), at(i), strokeWidth = 3.dp.toPx())
            }
            drawCircle(Pal.green, 6.dp.toPx(), at(0))
            drawCircle(Pal.text, 6.dp.toPx(), at(path.lastIndex))
        }
    }
}

private fun lerpC(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)
