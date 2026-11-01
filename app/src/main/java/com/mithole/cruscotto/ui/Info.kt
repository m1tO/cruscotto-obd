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
import com.mithole.cruscotto.obd.Lang
import com.mithole.cruscotto.obd.t
import com.mithole.cruscotto.obd.L

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
    val setLanguage: (Lang) -> Unit = {},
)

fun connLabel(conn: Conn): String = when (conn) {
    Conn.Idle -> t("Scollegato", "Disconnected")
    is Conn.Connecting -> conn.label
    is Conn.Connected -> conn.label
    is Conn.Retrying -> conn.label
}

fun infoText(conn: Conn, info: CarInfo, live: Live): String = buildString {
    appendLine("Cruscotto OBD")
    appendLine(t("Dongle: ${connLabel(conn)}", "Dongle: ${connLabel(conn)}"))
    appendLine(t("Versione: ${info.elm ?: "N/D"}", "Version: ${info.elm ?: "N/A"}"))
    appendLine(t("Protocollo: ${info.protocol ?: "N/D"}", "Protocol: ${info.protocol ?: "N/A"}"))
    appendLine(t("VIN: ${info.vin ?: "N/D"}", "VIN: ${info.vin ?: "N/A"}"))
    appendLine(t("Marca: ${Wmi.brand(info.vin) ?: "N/D"}", "Make: ${Wmi.brand(info.vin) ?: "N/A"}"))
    appendLine(t("Carburante: ${info.profile.fuel.label}${if (info.profile.setup.fuel == null) " (automatico)" else ""}", "Fuel: ${info.profile.fuel.label}${if (info.profile.setup.fuel == null) " (automatic)" else ""}"))
    appendLine(t("Turbo: ${if (info.profile.turbo) "sì" else "no"}${if (info.profile.setup.turbo == null) " (automatico)" else ""}", "Turbo: ${if (info.profile.turbo) "yes" else "no"}${if (info.profile.setup.turbo == null) " (automatic)" else ""}"))
    appendLine(t("Letture al secondo: ${fmt(live.hz, 1)}", "Readings per second: ${fmt(live.hz, 1)}"))
    appendLine(t("PID supportati: ${info.pids.sorted().joinToString(" ") { "%02X".format(it) }}", "Supported PIDs: ${info.pids.sorted().joinToString(" ") { "%02X".format(it) }}"))
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
        0x5E in info.pids -> t("Il consumo è quello comunicato dalla centralina.", "Fuel use is the figure reported by the ECU.")
        Metric.FUEL_RATE in live.available ->
            t("Il consumo è stimato dal flusso d'aria: in rilascio e a pieno carico è meno preciso.", "Fuel use is estimated from air flow: less accurate when coasting and at full load.")
        profile.fuel == Fuel.DIESEL ->
            t("Sui diesel il consumo si vede solo se lo comunica la centralina, e questa non lo fa.", "On diesels fuel use only shows if the ECU reports it, and this one doesn't.")
        profile.fuel == Fuel.CNG -> t("Col metano il consumo si misura a kg: l'app non lo stima.", "On CNG fuel is measured in kg: the app doesn't estimate it.")
        else -> t("Quest'auto non comunica né il consumo né il flusso d'aria: il consumo non si può calcolare.", "This car reports neither fuel use nor air flow: fuel use can't be calculated.")
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(t("Collegamento", "Connection")) {
            InfoRow(t("Dongle", "Dongle"), connLabel(conn))
            InfoRow(t("Versione", "Version"), info.elm ?: t("N/D", "N/A"))
            InfoRow(t("Protocollo", "Protocol"), info.protocol ?: t("N/D", "N/A"))
            InfoRow(t("Letture al secondo", "Readings per second"), fmt(live.hz, 1))
        }
        Card(t("Auto", "Car")) {
            InfoRow(t("Telaio (VIN)", "Chassis number (VIN)"), info.vin ?: t("N/D", "N/A"))
            InfoRow(t("Marca", "Make"), Wmi.brand(info.vin) ?: t("N/D", "N/A"))
            InfoRow(t("Valori standard disponibili", "Standard values available"), "${metrics.size}")
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
        Card(t("Motore", "Engine")) { ProfileChoices(info.vin, profile, actions.setSetup) }
        Card(t("Impostazioni", "Settings")) {
            // Ogni lingua scritta nella sua lingua: si ritrova anche senza capire quella attuale.
            ChoiceRow(
                t("Lingua", "Language"),
                Lang.entries.map { l -> Choice(l.native, L.lang == l) { actions.setLanguage(l) } },
            )
            NumberRow(t("Peso con te a bordo", "Weight with you on board"), "${settings.massKg} kg", { actions.setSettings(settings.copy(massKg = (settings.massKg - 10).coerceAtLeast(500))) }) {
                actions.setSettings(settings.copy(massKg = settings.massKg + 10))
            }
            NumberRow(t("Prezzo carburante", "Fuel price"), "${fmt(settings.fuelPrice, 3)} €/L", { actions.setSettings(settings.copy(fuelPrice = Math.round((settings.fuelPrice - 0.01) * 1000) / 1000.0)) }) {
                actions.setSettings(settings.copy(fuelPrice = Math.round((settings.fuelPrice + 0.01) * 1000) / 1000.0))
            }
            ChoiceRow(
                t("Avvisi a voce", "Voice alerts"),
                listOf(
                    Choice(t("Sì", "On"), settings.voice) { actions.setSettings(settings.copy(voice = true)) },
                    Choice(t("No", "Off"), !settings.voice) { actions.setSettings(settings.copy(voice = false)) },
                ),
            )
            // Lo stato lo scrive chi accende il GPS; la scheda si ridisegna a ogni lettura dell'auto, quindi resta aggiornato.
            InfoRow(
                "GPS",
                when {
                    !perms.location -> t("manca il permesso", "permission missing")
                    Obd.gpsStatus == GpsStatus.NO_HARDWARE -> t("il tablet non ha il GPS", "this tablet has no GPS")
                    Obd.gpsStatus == GpsStatus.ON -> t("acceso", "on")
                    else -> t("permesso concesso", "permission granted")
                },
            )
            InfoRow(t("Sensori gomme", "Tyre sensors"), if (perms.scan) t("permesso concesso", "permission granted") else t("manca il permesso", "permission missing"))
            if (!perms.all) {
                OutlinedButton(onClick = actions.requestSensors, modifier = Modifier.padding(top = 6.dp)) { Text(t("Concedi posizione e dispositivi vicini", "Allow location and nearby devices")) }
            }
            Text(
                t("Il peso serve al banco prova; il prezzo al costo dei viaggi. La voce avvisa di acqua troppo calda, batteria che non carica, ", "Weight is used by the dyno; price for trip costs. The voice warns about hot coolant, battery not charging, ") +
                    t("nuovi errori, motore freddo tirato, turbo raffreddato e gomme sgonfie.", "new faults, revving a cold engine, turbo cooled down and low tyres."),
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Card(t("Marce", "Gears")) {
            if (live.gearRatios.isEmpty()) {
                InfoRow(t("Rapporti imparati", "Learned ratios"), t("nessuno ancora", "none yet"))
            } else {
                live.gearRatios.forEachIndexed { i, k -> InfoRow("${i + 1}ª", t("${fmt(k, 1)} giri per km/h · ${fmt(1000 / k)} km/h a 1000 giri", "${fmt(k, 1)} rpm per km/h · ${fmt(1000 / k)} km/h at 1000 rpm")) }
            }
            Text(
                t("La marcia si riconosce dal rapporto tra giri e velocità, che a frizione innestata resta fisso. ", "The gear is recognised from the ratio between rpm and speed, which stays fixed with the clutch engaged. ") +
                    t("L'app lo impara da sola guidando: la prima è quella con cui parti da fermo, le altre arrivano ", "The app learns it by itself while you drive: first gear is the one you pull away in, the others come ") +
                    t("tenendo ognuna qualche secondo. Se cambi misura delle gomme, fai riapprendere.", "by holding each one for a few seconds. If you change tyre size, relearn."),
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = actions.resetGears, modifier = Modifier.padding(top = 8.dp)) { Text(t("Riapprendi le marce", "Relearn gears")) }
        }
        Card(t("Viaggio", "Trip")) {
            InfoRow(t("Distanza", "Distance"), "${fmt(live.tripKm, 1)} km")
            InfoRow(t("Carburante stimato", "Estimated fuel"), "${fmt(live.tripFuelL, 2)} L")
            InfoRow(t("Media", "Average"), "${fmt(live.tripL100, 1)} L/100 km")
            InfoRow(t("Velocità massima", "Top speed"), "${fmt(live.peaks[Metric.SPEED])} km/h")
            if (profile.turbo) InfoRow(t("Turbo massimo", "Max boost"), "${fmt(live.peaks[Metric.BOOST], 2, signed = true)} bar")
            InfoRow(t("Giri massimi", "Max rpm"), t("${fmt(live.peaks[Metric.RPM])} giri/min", "${fmt(live.peaks[Metric.RPM])} rpm"))
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = actions.resetTrip) { Text(t("Azzera viaggio", "Reset trip")) }
                OutlinedButton(onClick = actions.resetPeaks) { Text(t("Azzera massimi", "Reset peaks")) }
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
                Text(t("Copia informazioni", "Copy info"))
            }
            OutlinedButton(onClick = actions.forget) { Text(t("Dimentica il dongle", "Forget the dongle")) }
            Button(
                onClick = actions.disconnect,
                colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White),
            ) { Text(t("Disconnetti", "Disconnect")) }
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
    Card(t("Ascolto della rete", "Bus listening")) {
        Text(
            t("Le centraline si scambiano messaggi di continuo, anche senza che nessuno chieda niente. Per circa 40 secondi ", "ECUs exchange messages all the time, even when nobody asks for anything. For about 40 seconds ") +
                t("l'app smette di leggere i valori e ascolta quel traffico, un gruppo di indirizzi alla volta. Fallo col motore ", "the app stops reading values and listens to that traffic, one group of IDs at a time. Do it with the engine ") +
                t("acceso e l'auto ferma; mentre ascolta dai qualche colpo di acceleratore, così si vede quali dati seguono giri e turbo.", "running and the car stopped; blip the throttle a few times while it listens, so you can see which data follow rpm and boost."),
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
                InfoRow(t("Gruppo", "Group"), t("${s.window} di ${s.windows}", "${s.window} of ${s.windows}"))
                InfoRow(t("Messaggi sentiti", "Messages heard"), "${s.frames}")
                InfoRow(t("Indirizzi diversi", "Different IDs"), "${s.ids}")
                if (s.overflows > 0) InfoRow(t("Dongle saturo", "Dongle overflowed"), t("${s.overflows} volte", "${s.overflows} times"))
                OutlinedButton(onClick = onStop, modifier = Modifier.padding(top = 8.dp)) { Text(t("Ferma", "Stop")) }
            }
            else -> {
                if (s.summary != null) {
                    InfoRow(t("Messaggi sentiti", "Messages heard"), "${s.frames}")
                    InfoRow(t("Indirizzi diversi", "Different IDs"), "${s.ids}")
                    InfoRow(t("Dongle saturo", "Dongle overflowed"), if (s.overflows == 0) t("mai", "never") else t("${s.overflows} volte", "${s.overflows} times"))
                    VSpace(4)
                }
                s.message?.let { Text(it, color = Pal.amber, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.summary != null) {
                        OutlinedButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(t("Ascolto Cruscotto", "Cruscotto bus capture"), s.summary))
                        }) {
                            Icon(Ic.copy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t("Copia riassunto", "Copy summary"))
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
                                ctx.startActivity(Intent.createChooser(send, t("Manda il file dell'ascolto", "Send the capture file")))
                            }
                        }) { Text(t("Condividi file", "Share file")) }
                    }
                    Button(onClick = onStart, enabled = connected) {
                        Text(if (s.summary != null || s.message != null) t("Ascolta di nuovo", "Listen again") else t("Ascolta la rete", "Listen to the bus"))
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
        t("Carburante", "Fuel"),
        listOf(Choice(t("Automatico · ${(p.detectedFuel ?: Fuel.PETROL).label}", "Automatic · ${(p.detectedFuel ?: Fuel.PETROL).label}"), s.fuel == null) { onSetup(s.copy(fuel = null)) }) +
            Fuel.entries.map { f -> Choice(f.label, s.fuel == f) { onSetup(s.copy(fuel = f)) } },
    )
    ChoiceRow(
        t("Turbo", "Turbo"),
        listOf(
            Choice(t("Automatico · ${if (p.autoTurbo) "sì" else "non ancora visto"}", "Automatic · ${if (p.autoTurbo) "yes" else "not seen yet"}"), s.turbo == null) { onSetup(s.copy(turbo = null)) },
            Choice(t("Sì", "Yes"), s.turbo == true) { onSetup(s.copy(turbo = true)) },
            Choice(t("No", "No"), s.turbo == false) { onSetup(s.copy(turbo = false)) },
        ),
    )
    ChoiceRow(
        t("Fondo scala contagiri", "Rev counter full scale"),
        listOf(Choice(t("Automatico · ${p.autoRpmMax}", "Automatic · ${p.autoRpmMax}"), s.rpmMax == null) { onSetup(s.copy(rpmMax = null)) }) +
            Profile.RPM_CHOICES.map { r -> Choice("$r", s.rpmMax == r) { onSetup(s.copy(rpmMax = r)) } },
    )
    Text(
        t("Le scelte valgono solo per quest'auto (telaio ${vin ?: "non letto"}). In automatico il carburante lo dice ", "These choices apply only to this car (VIN ${vin ?: "not read"}). In automatic mode the fuel comes from ") +
            t("la centralina, e il turbo si riconosce la prima volta che la pressione nel collettore supera quella esterna.", "the ECU, and the turbo is recognised the first time manifold pressure goes above outside pressure."),
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
