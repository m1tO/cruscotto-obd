package com.mithole.cruscotto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mithole.cruscotto.obd.Diag
import com.mithole.cruscotto.obd.Dtc
import com.mithole.cruscotto.obd.FreezeFrame
import com.mithole.cruscotto.obd.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mithole.cruscotto.obd.t
import com.mithole.cruscotto.obd.L

@Composable
fun ErrorsScreen(diag: Diag, connected: Boolean, onRead: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var ask by remember { mutableStateOf(false) }
    val busy = diag.loading || !connected
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MilHeader(diag.status, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onRead, enabled = !busy) {
                    Icon(Ic.refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("Rileggi", "Read again"))
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { ask = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White),
                ) {
                    Icon(Ic.delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("Cancella errori", "Clear faults"))
                }
            }
        }
        item {
            Column {
                if (diag.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Pal.red)
                diag.message?.let { Text(it, color = Pal.amber, fontSize = 15.sp) }
                diag.readAt?.let {
                    Text(t("Ultima lettura alle ${SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(it))}", "Last read at ${SimpleDateFormat("HH:mm:ss", L.locale).format(Date(it))}"), color = Pal.muted, fontSize = 13.sp)
                }
            }
        }

        section(t("Errori memorizzati", "Stored faults"), null, diag.stored, Pal.red, t("Nessun errore memorizzato.", "No stored faults."))
        section(
            t("In attesa", "Pending"), t("Visti una volta e non ancora confermati: se si ripetono diventano errori veri.", "Seen once and not yet confirmed: if they happen again they become real faults."),
            diag.pending, Pal.amber, t("Nessuno.", "None."),
        )
        diag.permanent?.let {
            section(
                t("Permanenti", "Permanent"), t("Non si cancellano a mano: spariscono quando la centralina verifica che il guasto è risolto.", "They can't be cleared by hand: they go away when the ECU verifies the fault is fixed."),
                it, Pal.violet, t("Nessuno.", "None."),
            )
        }
        diag.freeze?.let { ff -> item { FreezeCard(ff) } }
        diag.status?.let { s -> if (s.monitors.isNotEmpty()) item { ReadinessCard(s) } }
        item {
            Text(
                t("Qui ci sono gli errori di motore ed emissioni (OBD standard). ABS, airbag e carrozzeria ", "These are engine and emissions faults (standard OBD). ABS, airbag and body systems ") +
                    t("usano la diagnosi propria di ogni marca e con un dongle ELM327 generico non si leggono.", "use each manufacturer's own diagnostics and can't be read with a generic ELM327 dongle."),
                color = Pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            containerColor = Pal.surface2,
            title = { Text(t("Cancellare gli errori?", "Clear the faults?")) },
            text = {
                Text(
                    t("Si cancellano i codici, le condizioni registrate al momento del guasto e i test di autodiagnosi, ", "This clears the codes, the conditions recorded when the fault happened and the self-tests, ") +
                        t("che ripartono da zero. La spia si spegne, ma se il guasto c'è ancora torna.\n\n", "which start again from zero. The warning light goes off, but if the fault is still there it comes back.\n\n") +
                        t("Fallo a quadro acceso e motore spento.", "Do it with the ignition on and the engine off."),
                )
            },
            confirmButton = {
                Button(
                    onClick = { ask = false; onClear() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White),
                ) { Text(t("Cancella", "Clear")) }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text(t("Annulla", "Cancel"), color = Pal.text) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, hint: String?, list: List<Dtc>, color: Color, empty: String,
) {
    item {
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            hint?.let { Text(it, color = Pal.muted, fontSize = 13.sp) }
        }
    }
    if (list.isEmpty()) {
        item { Text(empty, color = Pal.muted, fontSize = 15.sp) }
    } else {
        items(list, key = { title + it.code }) { DtcCard(it, color) }
    }
}

@Composable
private fun MilHeader(status: Status?, modifier: Modifier = Modifier) {
    val (color, text) = when {
        status == null -> Pal.muted to t("Spia motore: N/D", "Check engine: N/A")
        status.mil -> Pal.amber to t("Spia motore accesa", "Check engine light on")
        else -> Pal.green to t("Spia motore spenta", "Check engine light off")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (status?.mil == false) Ic.check else Ic.warning, null, tint = color, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, color = Pal.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            status?.let {
                Text(
                    if (it.dtcCount == 1) t("1 errore confermato", "1 confirmed fault") else t("${it.dtcCount} errori confermati", "${it.dtcCount} confirmed faults"),
                    color = Pal.muted, fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun DtcCard(dtc: Dtc, color: Color) {
    Row(
        Modifier.fillMaxWidth().glass(18.dp).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            dtc.code, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 120.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(dtc.description, color = Pal.text, fontSize = 16.sp)
            Text(dtc.system, color = Pal.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FreezeCard(ff: FreezeFrame) {
    Column(Modifier.fillMaxWidth().glass(18.dp).padding(18.dp)) {
        Text(t("Condizioni quando è comparso ${ff.dtc.code}", "Conditions when ${ff.dtc.code} appeared"), color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(t("La centralina le ha registrate nel momento del guasto.", "The ECU recorded them at the moment of the fault."), color = Pal.muted, fontSize = 13.sp)
        VSpace(12)
        if (ff.values.isEmpty()) {
            Text(t("La centralina non ha salvato i dettagli.", "The ECU didn't save the details."), color = Pal.muted)
        } else {
            ff.values.entries.chunked(4).forEach { row ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    row.forEach { (m, v) ->
                        Column(Modifier.weight(1f)) {
                            Text(m.label, color = Pal.muted, fontSize = 13.sp)
                            Text("${fmt(v, m.decimals)} ${m.unit}", color = Pal.text, fontSize = 18.sp, style = Digits)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ReadinessCard(s: Status) {
    var open by remember { mutableStateOf(false) }
    val done = s.monitors.count { it.complete }
    Column(Modifier.fillMaxWidth().glass(18.dp).clickable { open = !open }.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("Test di autodiagnosi", "Self-tests"), color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(t("$done su ${s.monitors.size} completati", "$done of ${s.monitors.size} complete"), color = if (done == s.monitors.size) Pal.green else Pal.amber, fontSize = 14.sp)
            }
            Text(if (open) t("Chiudi", "Close") else t("Dettagli", "Details"), color = Pal.cyan, fontSize = 15.sp)
        }
        if (open) {
            VSpace(10)
            Text(
                t("Dopo una cancellazione degli errori ripartono da zero e si completano da soli guidando normalmente per qualche giorno.", "After clearing the faults they start again from zero and complete by themselves after a few days of normal driving."),
                color = Pal.muted, fontSize = 13.sp,
            )
            VSpace(6)
            s.monitors.forEach {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (it.complete) Pal.green else Pal.amber)
                    Spacer(Modifier.width(12.dp))
                    Text(it.name, color = Pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(if (it.complete) t("Completato", "Complete") else t("Non completato", "Not complete"), color = if (it.complete) Pal.green else Pal.amber, fontSize = 14.sp)
                }
            }
        }
    }
}
