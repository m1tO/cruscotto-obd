package com.mithole.cruscotto.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.CarInfo
import com.mithole.cruscotto.obd.Conn
import com.mithole.cruscotto.obd.Fuel
import com.mithole.cruscotto.obd.GpsStatus
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Metric
import com.mithole.cruscotto.obd.Obd
import com.mithole.cruscotto.obd.Pids
import com.mithole.cruscotto.obd.Profile
import com.mithole.cruscotto.obd.Settings
import com.mithole.cruscotto.obd.Setup
import com.mithole.cruscotto.obd.SniffState
import com.mithole.cruscotto.obd.Wheel
import com.mithole.cruscotto.obd.Wmi

class Actions(
    val readDtcs: () -> Unit = {},
    val clearDtcs: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val forget: () -> Unit = {},
    val resetTrip: () -> Unit = {},
    val resetPeaks: () -> Unit = {},
    val resetRecords: () -> Unit = {},
    val resetGears: () -> Unit = {},
    val setSettings: (Settings) -> Unit = {},
    val assignTire: (Wheel, String?) -> Unit = { _, _ -> },
    val requestSensors: () -> Unit = {},
    val setSetup: (Setup) -> Unit = {},
    val startSniff: () -> Unit = {},
    val stopSniff: () -> Unit = {},
)

fun connLabel(conn: Conn): String = when (conn) {
    Conn.Idle -> "Scollegato"
    is Conn.Connecting -> conn.label
    is Conn.Connected -> conn.label
    is Conn.Retrying -> conn.label
}

fun infoText(conn: Conn, info: CarInfo, live: Live): String = buildString {
    appendLine("Cruscotto OBD")
    appendLine("Dongle: ${connLabel(conn)}")
    appendLine("Versione: ${info.elm ?: "N/D"}")
    appendLine("Protocollo: ${info.protocol ?: "N/D"}")
    appendLine("VIN: ${info.vin ?: "N/D"}")
    appendLine("Marca: ${Wmi.brand(info.vin) ?: "N/D"}")
    appendLine("Carburante: ${info.profile.fuel.label}${if (info.profile.setup.fuel == null) " (automatico)" else ""}")
    appendLine("Turbo: ${if (info.profile.turbo) "sì" else "no"}${if (info.profile.setup.turbo == null) " (automatico)" else ""}")
    appendLine("Letture al secondo: ${fmt(live.hz, 1)}")
    appendLine("PID supportati: ${info.pids.sorted().joinToString(" ") { "%02X".format(it) }}")
}

@Composable
fun InfoScreen(
    conn: Conn,
    info: CarInfo,
    live: Live,
    actions: Actions,
    modifier: Modifier = Modifier,
    settings: Settings = Settings(),
    perms: SensorPermissions = SensorPermissions(location = false, scan = false),
    sniff: SniffState = SniffState(),
) {
    val ctx = LocalContext.current
    val metrics = info.pids.mapNotNull { Pids.byPid[it]?.metric }.sortedBy { it.ordinal }
    val profile = info.profile
    val fuelNote = when {
        0x5E in info.pids -> "Il consumo è quello comunicato dalla centralina."
        Metric.FUEL_RATE in live.available ->
            "Il consumo è stimato dal flusso d'aria: in rilascio e a pieno carico è meno preciso."
        profile.fuel == Fuel.DIESEL ->
            "Sui diesel il consumo si vede solo se lo comunica la centralina, e questa non lo fa."
        profile.fuel == Fuel.CNG -> "Col metano il consumo si misura a kg: l'app non lo stima."
        else -> "Quest'auto non comunica né il consumo né il flusso d'aria: il consumo non si può calcolare."
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card("Collegamento") {
            InfoRow("Dongle", connLabel(conn))
            InfoRow("Versione", info.elm ?: "N/D")
            InfoRow("Protocollo", info.protocol ?: "N/D")
            InfoRow("Letture al secondo", fmt(live.hz, 1))
        }
        Card("Auto") {
            InfoRow("Telaio (VIN)", info.vin ?: "N/D")
            InfoRow("Marca", Wmi.brand(info.vin) ?: "N/D")
            InfoRow("Valori standard disponibili", "${metrics.size}")
            VSpace(6)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { m ->
                    Text(
                        m.label, color = Pal.text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.surface2)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Card("Motore") { ProfileChoices(info.vin, profile, actions.setSetup) }
        Card("Impostazioni") {
            NumberRow("Peso con te a bordo", "${settings.massKg} kg", { actions.setSettings(settings.copy(massKg = (settings.massKg - 10).coerceAtLeast(500))) }) {
                actions.setSettings(settings.copy(massKg = settings.massKg + 10))
            }
            NumberRow("Prezzo carburante", "${fmt(settings.fuelPrice, 3)} €/L", { actions.setSettings(settings.copy(fuelPrice = Math.round((settings.fuelPrice - 0.01) * 1000) / 1000.0)) }) {
                actions.setSettings(settings.copy(fuelPrice = Math.round((settings.fuelPrice + 0.01) * 1000) / 1000.0))
            }
            ChoiceRow(
                "Avvisi a voce",
                listOf(
                    Choice("Sì", settings.voice) { actions.setSettings(settings.copy(voice = true)) },
                    Choice("No", !settings.voice) { actions.setSettings(settings.copy(voice = false)) },
                ),
            )
            // Lo stato lo scrive chi accende il GPS; la scheda si ridisegna a ogni lettura dell'auto, quindi resta aggiornato.
            InfoRow(
                "GPS",
                when {
                    !perms.location -> "manca il permesso"
                    Obd.gpsStatus == GpsStatus.NO_HARDWARE -> "il tablet non ha il GPS"
                    Obd.gpsStatus == GpsStatus.ON -> "acceso"
                    else -> "permesso concesso"
                },
            )
            InfoRow("Sensori gomme", if (perms.scan) "permesso concesso" else "manca il permesso")
            if (!perms.all) {
                OutlinedButton(onClick = actions.requestSensors, modifier = Modifier.padding(top = 6.dp)) { Text("Concedi posizione e dispositivi vicini") }
            }
            Text(
                "Il peso serve al banco prova; il prezzo al costo dei viaggi. La voce avvisa di acqua troppo calda, batteria che non carica, " +
                    "nuovi errori, motore freddo tirato, turbo raffreddato e gomme sgonfie.",
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Card("Marce") {
            if (live.gearRatios.isEmpty()) {
                InfoRow("Rapporti imparati", "nessuno ancora")
            } else {
                live.gearRatios.forEachIndexed { i, k -> InfoRow("${i + 1}ª", "${fmt(k, 1)} giri per km/h · ${fmt(1000 / k)} km/h a 1000 giri") }
            }
            Text(
                "La marcia si riconosce dal rapporto tra giri e velocità, che a frizione innestata resta fisso. " +
                    "L'app lo impara da sola guidando: la prima è quella con cui parti da fermo, le altre arrivano " +
                    "tenendo ognuna qualche secondo. Se cambi misura delle gomme, fai riapprendere.",
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = actions.resetGears, modifier = Modifier.padding(top = 8.dp)) { Text("Riapprendi le marce") }
        }
        Card("Viaggio") {
            InfoRow("Distanza", "${fmt(live.tripKm, 1)} km")
            InfoRow("Carburante stimato", "${fmt(live.tripFuelL, 2)} L")
            InfoRow("Media", "${fmt(live.tripL100, 1)} L/100 km")
            InfoRow("Velocità massima", "${fmt(live.peaks[Metric.SPEED])} km/h")
            if (profile.turbo) InfoRow("Turbo massimo", "${fmt(live.peaks[Metric.BOOST], 2, signed = true)} bar")
            InfoRow("Giri massimi", "${fmt(live.peaks[Metric.RPM])} giri/min")
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = actions.resetTrip) { Text("Azzera viaggio") }
                OutlinedButton(onClick = actions.resetPeaks) { Text("Azzera massimi") }
            }
            Text(
                fuelNote,
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        SniffCard(sniff, conn is Conn.Connected, actions.startSniff, actions.stopSniff)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Cruscotto", infoText(conn, info, live)))
            }) {
                Icon(Ic.copy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copia informazioni")
            }
            OutlinedButton(onClick = actions.forget) { Text("Dimentica il dongle") }
            Button(
                onClick = actions.disconnect,
                colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White),
            ) { Text("Disconnetti") }
        }
    }
}

/**
 * Ascolto della rete CAN: il riassunto si copia e incolla in chat; il file completo (ogni messaggio col suo
 * istante) si manda con la condivisione di Android, per esempio come allegato.
 */
@Composable
fun SniffCard(s: SniffState, connected: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    Card("Ascolto della rete") {
        Text(
            "Le centraline si scambiano messaggi di continuo, anche senza che nessuno chieda niente. Per circa 40 secondi " +
                "l'app smette di leggere i valori e ascolta quel traffico, un gruppo di indirizzi alla volta. Fallo col motore " +
                "acceso e l'auto ferma; mentre ascolta dai qualche colpo di acceleratore, così si vede quali dati seguono giri e turbo.",
            color = Pal.muted, fontSize = 13.sp,
        )
        VSpace(10)
        when {
            s.running -> {
                LinearProgressIndicator(
                    progress = { if (s.windows > 0) (s.window - 1).coerceAtLeast(0) / s.windows.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(), color = Pal.cyan, trackColor = Pal.surface2,
                )
                VSpace(8)
                InfoRow("Gruppo", "${s.window} di ${s.windows}")
                InfoRow("Messaggi sentiti", "${s.frames}")
                InfoRow("Indirizzi diversi", "${s.ids}")
                if (s.overflows > 0) InfoRow("Dongle saturo", "${s.overflows} volte")
                OutlinedButton(onClick = onStop, modifier = Modifier.padding(top = 8.dp)) { Text("Ferma") }
            }
            else -> {
                if (s.summary != null) {
                    InfoRow("Messaggi sentiti", "${s.frames}")
                    InfoRow("Indirizzi diversi", "${s.ids}")
                    InfoRow("Dongle saturo", if (s.overflows == 0) "mai" else "${s.overflows} volte")
                    VSpace(4)
                }
                s.message?.let { Text(it, color = Pal.amber, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.summary != null) {
                        OutlinedButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Ascolto Cruscotto", s.summary))
                        }) {
                            Icon(Ic.copy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copia riassunto")
                        }
                    }
                    s.file?.let { f ->
                        OutlinedButton(onClick = {
                            runCatching {
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_SUBJECT, f.name)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(Intent.createChooser(send, "Manda il file dell'ascolto"))
                            }
                        }) { Text("Condividi file") }
                    }
                    Button(onClick = onStart, enabled = connected) {
                        Text(if (s.summary != null || s.message != null) "Ascolta di nuovo" else "Ascolta la rete")
                    }
                }
            }
        }
    }
}

private class Choice(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** Carburante, turbo e contagiri: "Automatico" segue la centralina, il resto forza la scelta per quest'auto. */
@Composable
private fun ProfileChoices(vin: String?, p: Profile, onSetup: (Setup) -> Unit) {
    val s = p.setup
    ChoiceRow(
        "Carburante",
        listOf(Choice("Automatico · ${(p.detectedFuel ?: Fuel.PETROL).label}", s.fuel == null) { onSetup(s.copy(fuel = null)) }) +
            Fuel.entries.map { f -> Choice(f.label, s.fuel == f) { onSetup(s.copy(fuel = f)) } },
    )
    ChoiceRow(
        "Turbo",
        listOf(
            Choice("Automatico · ${if (p.autoTurbo) "sì" else "non ancora visto"}", s.turbo == null) { onSetup(s.copy(turbo = null)) },
            Choice("Sì", s.turbo == true) { onSetup(s.copy(turbo = true)) },
            Choice("No", s.turbo == false) { onSetup(s.copy(turbo = false)) },
        ),
    )
    ChoiceRow(
        "Fondo scala contagiri",
        listOf(Choice("Automatico · ${p.autoRpmMax}", s.rpmMax == null) { onSetup(s.copy(rpmMax = null)) }) +
            Profile.RPM_CHOICES.map { r -> Choice("$r", s.rpmMax == r) { onSetup(s.copy(rpmMax = r)) } },
    )
    Text(
        "Le scelte valgono solo per quest'auto (telaio ${vin ?: "non letto"}). In automatico il carburante lo dice " +
            "la centralina, e il turbo si riconosce la prima volta che la pressione nel collettore supera quella esterna.",
        color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun ChoiceRow(label: String, choices: List<Choice>) {
    Text(label, color = Pal.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { c ->
            Text(
                c.label, color = if (c.selected) Pal.bg else Pal.text, fontSize = 14.sp,
                fontWeight = if (c.selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (c.selected) Pal.cyan else Pal.surface2)
                    .clickable(onClick = c.onClick).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(18.dp)) {
        Text(title, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        VSpace(8)
        content()
    }
}

@Composable
private fun NumberRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, color = Pal.text, fontSize = 16.sp, style = Digits, modifier = Modifier.padding(horizontal = 12.dp))
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Pal.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Pal.text, fontSize = 15.sp, style = Digits)
    }
}
