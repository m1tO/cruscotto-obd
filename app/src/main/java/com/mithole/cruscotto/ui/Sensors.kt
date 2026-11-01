package com.mithole.cruscotto.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mithole.cruscotto.obd.GpsFix
import com.mithole.cruscotto.obd.GpsStatus
import com.mithole.cruscotto.obd.Obd
import com.mithole.cruscotto.obd.Tpms
import java.util.Locale
import com.mithole.cruscotto.obd.L

/** Permessi dei sensori del tablet: posizione per il GPS, posizione e "dispositivi nelle vicinanze" per le gomme. */
data class SensorPermissions(val location: Boolean, val scan: Boolean) {
    val all: Boolean get() = location && scan

    companion object {
        val REQUEST = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
        )

        fun of(ctx: Context) = SensorPermissions(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED,
        )
    }
}

/** Stato dei permessi, riletto ogni volta che l'app torna in primo piano (l'utente può cambiarli dalle impostazioni). */
@Composable
fun rememberSensorPermissions(bump: Int = 0): SensorPermissions {
    val ctx = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    return remember(resumed, bump) { SensorPermissions.of(ctx) }
}

/**
 * Accende il GPS vero, e solo quello: la posizione da Wi-Fi in un'auto in corsa salta da una parte all'altra e non dà la velocità.
 * Molti tablet, come lo Xiaomi Pad 8 Pro, il GPS non ce l'hanno: chiedere un ricevitore che non esiste fa lanciare un errore ad Android.
 */
private fun startGps(ctx: Context): LocationListener? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        Obd.gpsStatus = GpsStatus.NO_PERMISSION
        return null
    }
    val lm = ctx.getSystemService(LocationManager::class.java)
    if (lm == null || LocationManager.GPS_PROVIDER !in lm.allProviders) {
        Obd.gpsStatus = GpsStatus.NO_HARDWARE
        return null
    }
    val listener = LocationListener { loc ->
        Obd.onGps(GpsFix(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed * 3.6 else null, loc.accuracy, System.currentTimeMillis()))
    }
    return runCatching {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        Obd.gpsStatus = GpsStatus.ON
        listener
    }.getOrElse {
        Obd.gpsStatus = GpsStatus.NO_HARDWARE
        null
    }
}

private fun stopGps(ctx: Context, l: LocationListener) {
    runCatching { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l) }
}

private class TpmsCallback : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val rec = result.scanRecord
        val m = rec?.manufacturerSpecificData
        val data = if (m == null) emptyList() else (0 until m.size()).map { m.keyAt(it) to m.valueAt(it) }
        Tpms.onAdvert(result.device.address, rec?.deviceName, result.rssi, data)
    }
}

private fun startScan(ctx: Context): ScanCallback? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return null
    val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return null
    val cb = TpmsCallback()
    scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
    return cb
}

private fun stopScan(ctx: Context, cb: ScanCallback) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
    runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
}

/** GPS e ricerca dei sensori gomme accesi finché la schermata è aperta e i permessi ci sono. */
@Composable
fun SensorsEffect(perms: SensorPermissions) {
    val ctx = LocalContext.current
    DisposableEffect(perms.location) {
        val l = startGps(ctx)
        onDispose { l?.let { stopGps(ctx, it) } }
    }
    DisposableEffect(perms.scan) {
        val cb = if (perms.scan) runCatching { startScan(ctx) }.getOrNull() else null
        onDispose { cb?.let { stopScan(ctx, it) } }
    }
}

/** Dice a voce gli avvisi dell'auto, nella lingua dell'app. */
@Composable
fun VoiceEffect() {
    val ctx = LocalContext.current
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        tts[0] = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) tts[0]?.language = L.locale
        }
        onDispose {
            tts[0]?.shutdown()
            tts[0] = null
        }
    }
    LaunchedEffect(Unit) {
        Obd.alerts.collect { text ->
            // La lingua si può cambiare mentre l'app è aperta: la voce la segue a ogni frase.
            tts[0]?.language = L.locale
            tts[0]?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        }
    }
}
