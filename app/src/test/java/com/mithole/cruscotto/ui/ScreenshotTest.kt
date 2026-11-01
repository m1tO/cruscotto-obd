package com.mithole.cruscotto.ui

import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.mithole.cruscotto.obd.Analysis
import com.mithole.cruscotto.obd.CarInfo
import com.mithole.cruscotto.obd.Check
import com.mithole.cruscotto.obd.Level
import com.mithole.cruscotto.obd.Run
import com.mithole.cruscotto.obd.Conn
import com.mithole.cruscotto.obd.Diag
import com.mithole.cruscotto.obd.Dtc
import com.mithole.cruscotto.obd.FreezeFrame
import com.mithole.cruscotto.obd.Fuel
import com.mithole.cruscotto.obd.History
import com.mithole.cruscotto.obd.Live
import com.mithole.cruscotto.obd.Metric
import com.mithole.cruscotto.obd.Monitor
import com.mithole.cruscotto.obd.Profile
import com.mithole.cruscotto.obd.Advice
import com.mithole.cruscotto.obd.BleSeen
import com.mithole.cruscotto.obd.DynoPoint
import com.mithole.cruscotto.obd.DynoResult
import com.mithole.cruscotto.obd.DynoState
import com.mithole.cruscotto.obd.Recording
import com.mithole.cruscotto.obd.Settings
import com.mithole.cruscotto.obd.SniffState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.mithole.cruscotto.obd.TireReading
import com.mithole.cruscotto.obd.Tpms
import com.mithole.cruscotto.obd.Trip
import com.mithole.cruscotto.obd.Wheel
import com.mithole.cruscotto.obd.Status
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val live = Live(
        values = mapOf(
            Metric.RPM to 3420.0, Metric.SPEED to 87.0, Metric.MAP to 186.0, Metric.BOOST to 0.85,
            Metric.COOLANT to 89.0, Metric.IAT to 34.0, Metric.VOLT to 14.1, Metric.LOAD to 72.0,
            Metric.TIMING to 8.5, Metric.PEDAL to 64.0, Metric.MAF to 41.2, Metric.FUEL_RATE to 13.5,
        ),
        available = setOf(
            Metric.RPM, Metric.SPEED, Metric.MAP, Metric.BOOST, Metric.COOLANT, Metric.IAT, Metric.VOLT,
            Metric.LOAD, Metric.TIMING, Metric.PEDAL, Metric.MAF, Metric.FUEL_RATE,
        ),
        peaks = mapOf(Metric.BOOST to 1.12, Metric.SPEED to 118.0, Metric.RPM to 5900.0),
        instantL100 = 15.5,
        tripKm = 12.4,
        tripFuelL = 0.86,
        hz = 11.8,
        gear = 4,
        gearRatios = listOf(128.0, 72.0, 50.0, 39.3, 31.0, 26.0),
    )

    private val status = Status(
        mil = true, dtcCount = 2,
        monitors = listOf(
            Monitor("Mancate accensioni", true), Monitor("Sistema carburante", true), Monitor("Componenti", true),
            Monitor("Catalizzatore", true), Monitor("Sistema evaporativo", false), Monitor("Sonda lambda", true),
            Monitor("Riscaldatore sonda lambda", true),
        ),
    )

    private val diag = Diag(
        readAt = 1_700_000_000_000,
        stored = listOf(Dtc("P0299"), Dtc("P0133")),
        pending = listOf(Dtc("P0300")),
        status = status,
        freeze = FreezeFrame(
            Dtc("P0299"),
            linkedMapOf(
                Metric.RPM to 3150.0, Metric.SPEED to 74.0, Metric.COOLANT to 88.0, Metric.LOAD to 67.0,
                Metric.MAP to 148.0, Metric.IAT to 34.0, Metric.THROTTLE to 100.0, Metric.TIMING to 9.5,
            ),
        ),
    )

    private val info = CarInfo(
        "ELM327 v1.5", "ISO 15765-4 (CAN 11/500)", "ZAR95500001234567", setOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x49),
        Profile(detectedFuel = Fuel.PETROL, boostSeen = true),
    )

    private val analysis = Analysis(
        last = mapOf(Run.ZERO_50 to 3.9, Run.ZERO_100 to 8.6),
        best = mapOf(Run.ZERO_50 to 3.7, Run.ZERO_100 to 8.2, Run.MID_80_120 to 7.4),
        running = mapOf(Run.MID_80_120 to 4.3),
        powerCv = 131.0, powerRpm = 5450.0, torqueNm = 214.0, torqueRpm = 2650.0,
        checks = listOf(
            Check("Miscela aria-benzina", Level.WARN, "minimo +13,8 % · in tiro +2,1 %", "Magra al minimo ma non sotto carico: tipico di un'aria falsa, cioè un manicotto o un tubo del vuoto che perde."),
            Check("Batteria e alternatore", Level.OK, "14,1 V in moto · 12,6 V da spento", "La batteria tiene e l'alternatore carica il giusto."),
            Check("Temperatura e termostato", Level.OK, "80 °C in 6,5 min", "Si scalda nei tempi giusti: termostato a posto."),
            Check("Intercooler", Level.WAIT, "In misura", "Serve qualche secondo col turbo sopra 0,5 bar, dopo un tratto in movimento."),
            Check("Turbo", Level.OK, "max +1,12 bar a 3400 giri", "La spinta più alta di questa sessione."),
        ),
    )

    private val now = 1_761_900_000_000L

    private val settings = Settings(tires = mapOf(Wheel.FL to "A", Wheel.FR to "B", Wheel.RL to "C", Wheel.RR to "D"))

    private val tireStates = run {
        val all = mapOf(
            Wheel.FL to TireReading(2.31, 24.0, 88, now), Wheel.FR to TireReading(2.04, 25.0, 91, now),
            Wheel.RL to TireReading(2.12, 22.0, 79, now), Wheel.RR to TireReading(2.09, 22.0, 83, now),
        )
        Wheel.entries.map { w -> Tpms.tireState(w, settings.tires[w], all[w], settings.target(w), now, all) }
    }

    private val dyno = DynoState(
        last = DynoResult(
            3, now,
            (8..25).map { i ->
                val rpm = i * 250.0
                val nm = if (rpm < 2250) 120 + (rpm - 2000) * 0.36 else if (rpm < 4750) 213.0 else 213 - (rpm - 4750) * 0.05
                DynoPoint(rpm, nm * 2 * Math.PI * rpm / 60 / 735.5 * 1.0, nm)
            },
        ),
    )

    private val trips = listOf(
        Trip(now - 3_600_000, now - 2_200_000, 18.4, 1.32, 112.0, 1300.0, 1.879),
        Trip(now - 90_000_000, now - 89_000_000, 6.2, 0.61, 74.0, 860.0, 1.879),
    )

    private val devices = listOf(
        BleSeen("80:EA:CA:10:12:34", "TPMS1_101234", -48, "0100:80EACA101234B082030092090000 5700", now, TireReading(2.31, 24.0, 88, now)),
        BleSeen("D4:3A:2C:77:01:9E", null, -71, "004C:0215F7826DA64FA24E988024BC5B71E0893E", now, null),
    )

    private val extras = Extras(settings, dyno, trips, tireStates, devices, SensorPermissions(location = true, scan = true))

    private fun history() = History().apply {
        val end = 1_000_000L
        for (i in 0..360) {
            val t = end - (360 - i) * 500L
            val x = i / 10.0
            add(Metric.RPM, t, (2500 + 1500 * sin(x / 2) + 300 * sin(x * 1.7)).toFloat())
            add(Metric.SPEED, t, (80 + 30 * sin(x / 3)).toFloat())
            add(Metric.BOOST, t, (0.3 + 0.7 * sin(x / 2)).toFloat())
            add(Metric.COOLANT, t, (70 + i * 0.05).toFloat())
            add(Metric.VOLT, t, (14.1 + 0.05 * sin(x)).toFloat())
            add(Metric.IAT, t, (32 + 3 * sin(x / 5)).toFloat())
        }
    }

    private fun shot(
        name: String, tab: Tab, conn: Conn = Conn.Connected("OBDII", demo = false), info: CarInfo = this.info, live: Live = this.live,
    ) {
        compose.setContent {
            CruscottoTheme { AppContent(conn, live, diag, info, analysis, history(), 1, tab, {}, Actions(), Recordings(), extras) }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun dashboard() = shot("1_cruscotto", Tab.DASH)

    @Test fun errors() = shot("2_errori", Tab.ERRORS)

    @Test fun analysis() = shot("10_analisi", Tab.ANALYSIS)

    @Test fun tires() = shot("14_gomme", Tab.TIRES)

    @Test
    fun dashboardAdvice() = shot(
        "15_cruscotto_turbo_caldo", Tab.DASH,
        live = live.copy(
            values = live.values + (Metric.RPM to 850.0) + (Metric.SPEED to 0.0) + (Metric.BOOST to -0.62),
            advice = Advice(com.mithole.cruscotto.obd.Level.WARN, "Turbo caldo", "Lascia girare il motore ancora 0:42 prima di spegnere."),
        ),
    )

    @Test
    fun hud() {
        compose.setContent { CruscottoTheme { HudScreen(live.copy(values = live.values + (Metric.RPM to 5800.0)), info.profile, {}, startMirrored = false) } }
        compose.onRoot().captureRoboImage("screenshots/16_hud.png")
    }

    // Vicino al limitatore: shift light quasi piena.
    @Test
    fun dashboardShift() = shot(
        "12_cruscotto_shift", Tab.DASH,
        live = live.copy(values = live.values + (Metric.RPM to 5650.0) + (Metric.SPEED to 112.0), gear = 3),
    )

    @Test
    fun recordings() {
        val now = 1_761_900_000_000L
        val list = listOf(
            Recording(java.io.File("giro_b.csv"), now, 754.0, 1_480_000),
            Recording(java.io.File("giro_a.csv"), now - 86_400_000L * 2, 41.5, 72_000),
        )
        compose.setContent {
            CruscottoTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.background(Pal.bg),
                ) { ChartsScreen(live, history(), 1, Recordings(list = list), startSaved = true) }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/13_registrazioni.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun analysisPortrait() = shot("11_analisi_verticale", Tab.ANALYSIS)

    @Test fun charts() = shot("3_grafici", Tab.CHARTS)

    @Test fun info() = shot("4_auto", Tab.INFO)

    @Test fun reconnecting() = shot("5_riconnessione", Tab.DASH, Conn.Retrying("OBDII", "Il dongle non risponde più."))

    // Un benzina aspirato: il manometro diventa un vacuometro.
    @Test
    fun dashboardAspirated() = shot(
        "7_cruscotto_aspirato", Tab.DASH,
        info = info.copy(vin = null, profile = Profile(detectedFuel = Fuel.PETROL)),
        live = live.copy(values = live.values + (Metric.BOOST to -0.42), available = live.available - Metric.BOOST),
    )

    // Un diesel: contagiri a 6000, manometro fino a 2,5 bar.
    @Test
    fun dashboardDiesel() = shot(
        "8_cruscotto_diesel", Tab.DASH,
        info = info.copy(vin = "WVWZZZ1KZ6W000000", profile = Profile(detectedFuel = Fuel.DIESEL)),
        live = live.copy(values = live.values + (Metric.RPM to 2650.0) + (Metric.BOOST to 1.55), peaks = live.peaks + (Metric.BOOST to 1.9)),
    )

    @Test
    fun infoDiesel() = shot(
        "9_auto_diesel", Tab.INFO,
        info = info.copy(vin = "WVWZZZ1KZ6W000000", profile = Profile(detectedFuel = Fuel.DIESEL)),
        live = live.copy(available = live.available - Metric.FUEL_RATE),
    )

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun dashboardPortrait() = shot("6_cruscotto_verticale", Tab.DASH)

    private fun sniffShot(name: String, state: SniffState) {
        compose.setContent {
            CruscottoTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.techBackground().padding(20.dp).width(700.dp),
                ) { SniffCard(state, connected = true, onStart = {}, onStop = {}) }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun sniffRunning() = sniffShot("16_ascolto_in_corso", SniffState(running = true, window = 4, windows = 9, frames = 1834, ids = 37, overflows = 3))

    @Test
    fun sniffDone() = sniffShot(
        "17_ascolto_finito",
        SniffState(frames = 4120, ids = 52, overflows = 11, summary = "x", file = java.io.File("ascolto.txt")),
    )

    @Test
    fun connect() {
        compose.setContent {
            CruscottoTheme {
                ConnectContent(
                    noBluetooth = false, needPermission = false, bluetoothOff = false,
                    devices = listOf(
                        PairedDevice("OBDII", "00:1D:A5:68:98:8B"), PairedDevice("AirPods", "AC:12:34:56:78:9A"),
                        PairedDevice("MacBook Pro", "F0:18:98:11:22:33"), PairedDevice("JBL Flip 5", "B8:F6:53:44:55:66"),
                        PairedDevice("Apple Watch", "5C:E9:1E:77:88:99"), PairedDevice("Echo Dot", "74:C2:46:AA:BB:CC"),
                    ),
                    savedAddress = "00:1D:A5:68:98:8B",
                    onGrant = {}, onEnable = {}, onSettings = {}, onConnect = {}, onDemo = {},
                    wifiHost = "192.168.0.10:35000", wifiLast = false, onWifiSettings = {}, onConnectWifi = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/0_collegamento.png")
    }
}
