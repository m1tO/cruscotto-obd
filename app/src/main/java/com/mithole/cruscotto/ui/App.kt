package com.mithole.cruscotto.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mithole.cruscotto.obd.Analysis
import com.mithole.cruscotto.obd.BleSeen
import com.mithole.cruscotto.obd.DynoState
import com.mithole.cruscotto.obd.Settings
import com.mithole.cruscotto.obd.SniffState
import com.mithole.cruscotto.obd.TireLevel
import com.mithole.cruscotto.obd.TireState
import com.mithole.cruscotto.obd.Tpms
import com.mithole.cruscotto.obd.Trip
import com.mithole.cruscotto.obd.Wheel
import com.mithole.cruscotto.obd.CarInfo
import com.mithole.cruscotto.obd.Conn
import com.mithole.cruscotto.obd.Diag
import com.mithole.cruscotto.obd.History
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Obd

enum class Tab(val label: String) { DASH("Cruscotto"), ANALYSIS("Analisi"), TIRES("Gomme"), ERRORS("Errori"), CHARTS("Grafici"), INFO("Auto") }

/** Quello che arriva dai sensori del tablet e dalle impostazioni, raccolto per non allungare troppo le firme. */
class Extras(
    val settings: Settings = Settings(),
    val dyno: DynoState = DynoState(),
    val trips: List<Trip> = emptyList(),
    val tires: List<TireState> = Wheel.entries.map { TireState(it, null, Settings().target(it), TireLevel.NONE, "nessun sensore") },
    val devices: List<BleSeen> = emptyList(),
    val perms: SensorPermissions = SensorPermissions(location = false, scan = false),
    val onHud: () -> Unit = {},
    val sniff: SniffState = SniffState(),
)

private fun Tab.icon(): ImageVector = when (this) {
    Tab.DASH -> Ic.speed
    Tab.ANALYSIS -> Ic.timer
    Tab.TIRES -> Ic.tire
    Tab.ERRORS -> Ic.wrench
    Tab.CHARTS -> Ic.chart
    Tab.INFO -> Ic.car
}

@Composable
fun App(crash: String? = null, onCrashSeen: () -> Unit = {}) {
    val conn by Obd.conn.collectAsStateWithLifecycle()
    val live by Obd.live.collectAsStateWithLifecycle()
    val diag by Obd.diag.collectAsStateWithLifecycle()
    val info by Obd.info.collectAsStateWithLifecycle()
    val analysis by Obd.analysis.collectAsStateWithLifecycle()
    val recordingSince by Obd.recordingSince.collectAsStateWithLifecycle()
    val recordings by Obd.recordings.collectAsStateWithLifecycle()
    val version by Obd.history.version.collectAsStateWithLifecycle()
    val settings by Obd.settings.collectAsStateWithLifecycle()
    val dyno by Obd.dynoState.collectAsStateWithLifecycle()
    val trips by Obd.trips.collectAsStateWithLifecycle()
    val devices by Tpms.devices.collectAsStateWithLifecycle()
    val sniff by Obd.sniff.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }
    var hud by rememberSaveable { mutableStateOf(false) }
    val idle = conn is Conn.Idle
    var permsAsked by remember { mutableIntStateOf(0) }
    val perms = rememberSensorPermissions(permsAsked)
    val askPerms = rememberPermissionLauncher { permsAsked++ }

    VoiceEffect()
    if (!idle) SensorsEffect(perms)

    // Schermo sempre acceso mentre si è collegati.
    val view = LocalView.current
    DisposableEffect(idle) {
        view.keepScreenOn = !idle
        onDispose { view.keepScreenOn = false }
    }

    val actions = remember {
        Actions(
            readDtcs = Obd::readDtcs,
            clearDtcs = Obd::clearDtcs,
            disconnect = Obd::disconnect,
            forget = Obd::forgetDevice,
            resetTrip = Obd::resetTrip,
            resetPeaks = Obd::resetPeaks,
            resetRecords = Obd::resetRecords,
            resetGears = Obd::resetGears,
            setSetup = Obd::setSetup,
            setSettings = Obd::setSettings,
            assignTire = Obd::assignTire,
            requestSensors = { askPerms.launch(SensorPermissions.REQUEST) },
            startSniff = Obd::startSniff,
            stopSniff = Obd::stopSniff,
        )
    }
    val tires = remember(devices, settings) { Tpms.states(settings) }
    Column(Modifier.fillMaxSize().techBackground()) {
        if (crash != null) CrashBanner(crash, onCrashSeen)
        Box(Modifier.weight(1f)) {
            if (idle) {
                ConnectScreen()
            } else if (hud) {
                HudScreen(live, info.profile, onExit = { hud = false })
            } else {
                AppContent(
                    conn, live, diag, info, analysis, Obd.history, version, tab, { tab = it }, actions,
                    Recordings(recordingSince, recordings, Obd::startRecording, Obd::stopRecording, Obd::deleteRecording),
                    Extras(settings, dyno, trips, tires, devices, perms, onHud = { hud = true }, sniff = sniff),
                )
            }
        }
    }
}

/** L'ultima volta l'app si è chiusa per un errore: il motivo si copia per mandarlo a chi la sistema. */
@Composable
private fun CrashBanner(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .background(Pal.red.copy(alpha = 0.16f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "L'ultima volta l'app si è chiusa per un errore. Copia il motivo e mandalo: serve a correggerlo.",
            color = Pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Errore Cruscotto", text))
        }) { Text("Copia", color = Pal.text) }
        TextButton(onClick = onDismiss) { Text("Chiudi", color = Pal.muted) }
    }
}

@Composable
fun AppContent(
    conn: Conn,
    live: Live,
    diag: Diag,
    info: CarInfo,
    analysis: Analysis,
    history: History,
    historyVersion: Long,
    tab: Tab,
    onTab: (Tab) -> Unit,
    actions: Actions,
    recordings: Recordings = Recordings(),
    extras: Extras = Extras(),
) {
    val badge = diag.status?.dtcCount?.takeIf { it > 0 }
    BoxWithConstraints(Modifier.fillMaxSize().techBackground().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        val body: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                StatusBanner(conn, actions.disconnect)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.DASH -> DashboardScreen(
                            live, diag.status, info.profile, onErrors = { onTab(Tab.ERRORS) },
                            tires = extras.tires, onTires = { onTab(Tab.TIRES) }, onHud = extras.onHud,
                        )
                        Tab.ANALYSIS -> AnalysisScreen(
                            analysis, live, actions.resetRecords, dyno = extras.dyno, trips = extras.trips, massKg = extras.settings.massKg,
                        )
                        Tab.TIRES -> TiresScreen(
                            extras.tires, extras.devices, extras.settings, extras.perms,
                            onPerms = actions.requestSensors, onSettings = actions.setSettings, onAssign = actions.assignTire,
                        )
                        Tab.ERRORS -> ErrorsScreen(diag, conn is Conn.Connected, actions.readDtcs, actions.clearDtcs)
                        Tab.CHARTS -> ChartsScreen(live, history, historyVersion, recordings)
                        Tab.INFO -> InfoScreen(conn, info, live, actions, settings = extras.settings, perms = extras.perms, sniff = extras.sniff)
                    }
                }
            }
        }
        if (landscape) {
            Row {
                Rail(tab, onTab, badge, conn)
                body(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column {
                body(Modifier.weight(1f).fillMaxWidth())
                BottomBar(tab, onTab, badge)
            }
        }
    }
}

@Composable
private fun TabIcon(t: Tab, badge: Int?) {
    if (t == Tab.ERRORS && badge != null) {
        BadgedBox(badge = { Badge(containerColor = Pal.amber) { Text("$badge") } }) { Icon(t.icon(), null) }
    } else {
        Icon(t.icon(), null)
    }
}

@Composable
private fun Rail(tab: Tab, onTab: (Tab) -> Unit, badge: Int?, conn: Conn) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = Pal.cyan, selectedTextColor = Pal.cyan, indicatorColor = Pal.cyan.copy(alpha = 0.14f),
        unselectedIconColor = Pal.muted, unselectedTextColor = Pal.muted,
    )
    NavigationRail(containerColor = Pal.bg.copy(alpha = 0.6f)) {
        Spacer(Modifier.size(12.dp))
        Tab.entries.forEach { t ->
            NavigationRailItem(
                selected = tab == t,
                onClick = { onTab(t) },
                icon = { TabIcon(t, badge) },
                label = { Text(t.label) },
                colors = colors,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        StatusDot(
            when (conn) {
                is Conn.Connected -> if (conn.demo) Pal.cyan else Pal.green
                is Conn.Connecting -> Pal.amber
                is Conn.Retrying -> Pal.red
                Conn.Idle -> Pal.muted
            },
            Modifier.padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, badge: Int?) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Pal.cyan, selectedTextColor = Pal.cyan, indicatorColor = Pal.cyan.copy(alpha = 0.14f),
        unselectedIconColor = Pal.muted, unselectedTextColor = Pal.muted,
    )
    NavigationBar(containerColor = Pal.bg.copy(alpha = 0.85f)) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = tab == t, onClick = { onTab(t) }, icon = { TabIcon(t, badge) },
                label = { Text(t.label) }, colors = colors,
            )
        }
    }
}

@Composable
private fun StatusBanner(conn: Conn, onCancel: () -> Unit) {
    val (text, color, spinner) = when (conn) {
        is Conn.Connecting -> Triple(conn.step, Pal.text, true)
        is Conn.Retrying -> Triple("Collegamento perso: ${conn.reason} Riprovo…", Pal.amber, true)
        is Conn.Connected -> if (conn.demo) Triple("Modalità demo: dati simulati", Pal.cyan, false) else return
        Conn.Idle -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .background(Pal.surface2, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(if (conn is Conn.Connected) "Esci dalla demo" else "Annulla", color = Pal.text) }
    }
}
