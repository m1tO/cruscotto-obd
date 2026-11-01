package com.mithole.cruscotto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mithole.cruscotto.obd.Obd
import com.mithole.cruscotto.ui.App
import com.mithole.cruscotto.ui.CruscottoTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se l'app si chiude per un errore, il motivo resta in un file e al prossimo avvio si mostra, pronto da copiare.
        val crashFile = File(filesDir, "ultimo_errore.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile.writeText(
                    "Cruscotto ${packageManager.getPackageInfo(packageName, 0).versionName}, " +
                        "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}\n\n" + e.stackTraceToString(),
                )
            }
            previous?.uncaughtException(thread, e)
        }
        enableEdgeToEdge()
        // A tutto schermo: le barre di sistema compaiono con uno scorrimento dal bordo.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        Obd.init(applicationContext)
        val lastCrash = runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()
        setContent {
            var crash by remember { mutableStateOf(lastCrash) }
            CruscottoTheme {
                App(crash) {
                    crashFile.delete()
                    crash = null
                }
            }
        }
    }
}
