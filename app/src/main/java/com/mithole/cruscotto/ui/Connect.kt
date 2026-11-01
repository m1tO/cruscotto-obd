package com.mithole.cruscotto.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mithole.cruscotto.obd.Obd
import com.mithole.cruscotto.obd.WIFI_PORT
import com.mithole.cruscotto.obd.parseHostPort

data class PairedDevice(val name: String, val address: String)

private fun looksLikeObd(name: String): Boolean {
    val n = name.uppercase()
    return listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "KONNWEI", "CAR").any { it in n }
}

@Composable
fun ConnectScreen() {
    val ctx = LocalContext.current
    val adapter: BluetoothAdapter? = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var refresh by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val granted = remember(refresh) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    val on = remember(refresh, granted) { granted && adapter?.isEnabled == true }
    val devices = remember(refresh, on) {
        if (on) {
            adapter!!.bondedDevices
                .map { PairedDevice(it.name ?: it.address, it.address) }
                .sortedWith(compareByDescending<PairedDevice> { looksLikeObd(it.name) }.thenBy { it.name })
        } else {
            emptyList()
        }
    }
    // Con l'ultimo dongle Wi-Fi si riparte subito; con quello Bluetooth si aspetta che il Bluetooth sia pronto.
    LaunchedEffect(on) { Obd.autoConnectOnce(if (on) adapter else null) }

    ConnectContent(
        noBluetooth = adapter == null,
        needPermission = !granted,
        bluetoothOff = granted && !on,
        devices = devices,
        savedAddress = Obd.savedAddress,
        onGrant = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onEnable = { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        onSettings = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onConnect = { d -> adapter?.let { Obd.connectBluetooth(it, d.address, d.name) } },
        onDemo = { Obd.connectDemo() },
        wifiHost = Obd.savedWifiHost,
        wifiLast = Obd.lastWasWifi,
        onWifiSettings = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        onConnectWifi = { Obd.connectWifi(it) },
    )
}

@Composable
fun ConnectContent(
    noBluetooth: Boolean,
    needPermission: Boolean,
    bluetoothOff: Boolean,
    devices: List<PairedDevice>,
    savedAddress: String?,
    onGrant: () -> Unit,
    onEnable: () -> Unit,
    onSettings: () -> Unit,
    onConnect: (PairedDevice) -> Unit,
    onDemo: () -> Unit,
    wifiHost: String?,
    wifiLast: Boolean,
    onWifiSettings: () -> Unit,
    onConnectWifi: (String?) -> Unit,
) {
    Box(Modifier.fillMaxSize().techBackground(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
            // Intestazione ferma: demo e impostazioni Bluetooth sempre a vista, anche con tanti dispositivi abbinati.
            Row(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.speed, null, tint = Pal.cyan, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(14.dp))
                Text("Cruscotto", color = Pal.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onSettings) {
                    Icon(Ic.bluetooth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bluetooth")
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onWifiSettings) {
                    Icon(Ic.wifi, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Wi-Fi")
                }
                Spacer(Modifier.width(10.dp))
                Button(onClick = onDemo, colors = ButtonDefaults.buttonColors(containerColor = Pal.cyan, contentColor = Pal.bg)) {
                    Text("Prova la demo", fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                "Inserisci il dongle nella presa OBD (sotto il cruscotto, lato guida), accendi il quadro e scegli il dongle qui sotto: Bluetooth o Wi-Fi.",
                color = Pal.muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp),
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Text("Wi-Fi", color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            WifiCard(wifiHost, wifiLast, onConnectWifi)
            Text(
                "Prima collega il tablet alla rete Wi-Fi del dongle (tasto Wi-Fi in alto): di solito si chiama WiFi_OBDII o simile, " +
                    "senza password. Se Android avvisa che la rete non ha internet, scegli di restare collegato.",
                color = Pal.muted, fontSize = 14.sp,
            )
            Text("Bluetooth", color = Pal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            when {
                noBluetooth -> Notice("Questo dispositivo non ha il Bluetooth.")
                needPermission -> Notice("Serve il permesso per usare il Bluetooth.", "Consenti", onGrant)
                bluetoothOff -> Notice("Il Bluetooth è spento.", "Accendi", onEnable)
                devices.isEmpty() -> Notice("Nessun dispositivo abbinato.", "Impostazioni Bluetooth", onSettings)
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { DeviceRow(it, it.address == savedAddress) { onConnect(it) } }
                }
            }
            Text(
                "Il dongle non c'è? Abbinalo prima dalle impostazioni Bluetooth del tablet (tasto in alto): di solito si chiama OBDII e il PIN è 1234 o 0000.",
                color = Pal.muted, fontSize = 14.sp,
            )
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().glass(18.dp).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Pal.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (action != null) {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Pal.red, contentColor = Color.White)) {
                Text(action)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: PairedDevice, last: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(18.dp).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.bluetooth, null, tint = if (looksLikeObd(d.name)) Pal.cyan else Pal.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(if (last) "${d.address} · usato l'ultima volta" else d.address, color = Pal.muted, fontSize = 13.sp)
        }
        Text("Collega", color = Pal.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WifiCard(host: String?, last: Boolean, onConnect: (String?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf(host.orEmpty()) }
    val typedOk = typed.isBlank() || parseHostPort(typed) != null
    Column(Modifier.fillMaxWidth().glass(18.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onConnect(null) }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.wifi, null, tint = Pal.cyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("OBD Wi-Fi", color = Pal.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        host == null -> "indirizzo trovato da solo"
                        last -> "$host · usato l'ultima volta"
                        else -> host
                    },
                    color = Pal.muted, fontSize = 13.sp,
                )
            }
            Text("Collega", color = Pal.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!editing) {
            TextButton(onClick = { editing = true }, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                Text("Indirizzo a mano", color = Pal.muted, fontSize = 14.sp)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.trim() },
                    singleLine = true,
                    isError = !typedOk,
                    placeholder = { Text("192.168.0.10:$WIFI_PORT", color = Pal.muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pal.text, unfocusedTextColor = Pal.text,
                        focusedBorderColor = Pal.cyan, unfocusedBorderColor = Pal.track, cursorColor = Pal.cyan,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onConnect(typed.ifBlank { null }) },
                    enabled = typedOk,
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.cyan, contentColor = Pal.bg),
                ) { Text("Collega") }
            }
        }
    }
}
