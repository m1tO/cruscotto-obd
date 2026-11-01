package com.mithole.cruscotto.obd

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Conn {
    data object Idle : Conn
    data class Connecting(val label: String, val step: String) : Conn
    data class Connected(val label: String, val demo: Boolean) : Conn
    data class Retrying(val label: String, val reason: String) : Conn
}

data class CarInfo(
    val elm: String? = null,
    val protocol: String? = null,
    val vin: String? = null,
    val pids: Set<Int> = emptySet(),
    val profile: Profile = Profile(),
)

data class Live(
    val values: Map<Metric, Double> = emptyMap(),
    val available: Set<Metric> = emptySet(),
    val peaks: Map<Metric, Double> = emptyMap(),
    val instantL100: Double? = null,
    val tripKm: Double = 0.0,
    val tripFuelL: Double = 0.0,
    val hz: Double = 0.0,
    val gear: Int? = null,
    /** Giri per km/h delle marce imparate, dalla prima in su. */
    val gearRatios: List<Double> = emptyList(),
    val advice: Advice? = null,
    val gpsKmh: Double? = null,
) {
    val tripL100: Double? get() = if (tripKm > 0.3 && tripFuelL > 0) tripFuelL / tripKm * 100 else null
}

data class Diag(
    val loading: Boolean = false,
    val readAt: Long? = null,
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc>? = null,
    val status: Status? = null,
    val freeze: FreezeFrame? = null,
    val message: String? = null,
)

/** L'ascolto della rete CAN: avanzamento mentre gira, poi il riassunto da copiare e il file completo. */
data class SniffState(
    val running: Boolean = false,
    val window: Int = 0,
    val windows: Int = 0,
    val frames: Int = 0,
    val ids: Int = 0,
    val overflows: Int = 0,
    val message: String? = null,
    val summary: String? = null,
    val file: File? = null,
)

data class Sample(val t: Long, val v: Float)

/** Ultimi minuti di ogni valore, per i grafici. */
class History(private val windowMs: Long = WINDOW_MS) {
    private val data = HashMap<Metric, ArrayDeque<Sample>>()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized
    fun add(m: Metric, t: Long, v: Float) {
        val q = data.getOrPut(m) { ArrayDeque() }
        q.addLast(Sample(t, v))
        while (q.isNotEmpty() && q.first().t < t - windowMs) q.removeFirst()
    }

    @Synchronized
    fun snapshot(m: Metric): List<Sample> = data[m]?.toList().orEmpty()

    @Synchronized
    fun clear() {
        data.clear()
        bump()
    }

    fun bump() {
        _version.value++
    }

    companion object {
        const val WINDOW_MS = 180_000L
    }
}

/** Durata di una finestra d'ascolto: nove finestre fanno 36 secondi. */
const val SNIFF_MS = 4_000L

/** Sessione OBD dell'app: collegamento, lettura continua, errori. Vive quanto il processo. */
object Obd {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private val _diag = MutableStateFlow(Diag())
    val diag: StateFlow<Diag> = _diag.asStateFlow()
    private val _info = MutableStateFlow(CarInfo())
    val info: StateFlow<CarInfo> = _info.asStateFlow()
    private val _analysis = MutableStateFlow(Analysis())
    val analysis: StateFlow<Analysis> = _analysis.asStateFlow()
    private var analyzer = Analyzer()
    private val gears = Gears()
    private var gearsKey: String? = null
    @Volatile private var resetGears = false
    private var recorder: Recorder? = null
    private val _recordingSince = MutableStateFlow<Long?>(null)
    /** Quando è partita la registrazione in corso, o null se non si registra. */
    val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()
    val history = History()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val dyno = Dyno()
    private val _dyno = MutableStateFlow(DynoState())
    val dynoState: StateFlow<DynoState> = _dyno.asStateFlow()
    private val advisor = Advisor()
    private val alerter = Alerter()
    private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Frasi da dire a voce. */
    val alerts: SharedFlow<String> = _alerts
    private val tracker = TripTracker()
    private var tripLog: TripLog? = null
    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()
    @Volatile private var lastGps: GpsFix? = null
    @Volatile var gpsStatus = GpsStatus.NO_PERMISSION

    private val _sniff = MutableStateFlow(SniffState())
    val sniff: StateFlow<SniffState> = _sniff.asStateFlow()
    @Volatile private var sniffing = false
    private var sniffJob: Job? = null
    private var sniffDir: File? = null
    @Volatile private var demoNow = false
    private var label = ""

    private var prefs: SharedPreferences? = null
    private var cm: ConnectivityManager? = null
    private var carKey = "car.nd."
    private var job: Job? = null
    private var autoTried = false
    @Volatile private var elm: Elm327? = null
    @Volatile private var resetTrip = false
    @Volatile private var resetPeaks = false
    @Volatile private var resetRecords = false
    @Volatile private var lastDtcCount = -1

    fun init(context: Context) {
        if (prefs == null) prefs = context.getSharedPreferences("cruscotto", Context.MODE_PRIVATE)
        if (cm == null) cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (sniffDir == null) sniffDir = File(context.filesDir, "ascolto")
        if (recorder == null) {
            recorder = Recorder(File(context.filesDir, "registrazioni"))
            refreshRecordings()
        }
        if (tripLog == null) {
            val log = TripLog(File(context.filesDir, "viaggi.txt"))
            tripLog = log
            _settings.value = loadSettings()
            // Un viaggio rimasto aperto (app chiusa a metà) si chiude con l'ultimo stato salvato.
            prefs?.getString("trip.open", null)?.let(Trip::parse)?.takeIf { it.km >= 0.3 }?.let(log::append)
            prefs?.edit()?.remove("trip.open")?.apply()
            scope.launch { _trips.value = log.list() }
        }
    }

    private fun loadSettings(): Settings {
        val p = prefs ?: return Settings()
        val d = Settings()
        return Settings(
            massKg = p.getInt("set.mass", d.massKg),
            fuelPrice = p.getFloat("set.price", d.fuelPrice.toFloat()).toDouble(),
            voice = p.getBoolean("set.voice", d.voice),
            tireFront = p.getFloat("set.tireFront", d.tireFront.toFloat()).toDouble(),
            tireRear = p.getFloat("set.tireRear", d.tireRear.toFloat()).toDouble(),
            tires = Wheel.entries.mapNotNull { w -> p.getString("set.tire." + w.name, null)?.let { w to it } }.toMap(),
        )
    }

    fun setSettings(s: Settings) {
        _settings.value = s
        prefs?.edit()?.let { ed ->
            ed.putInt("set.mass", s.massKg)
            ed.putFloat("set.price", s.fuelPrice.toFloat())
            ed.putBoolean("set.voice", s.voice)
            ed.putFloat("set.tireFront", s.tireFront.toFloat())
            ed.putFloat("set.tireRear", s.tireRear.toFloat())
            Wheel.entries.forEach { w -> s.tires[w]?.let { ed.putString("set.tire." + w.name, it) } ?: ed.remove("set.tire." + w.name) }
            ed.apply()
        }
    }

    /** Monta il sensore [address] sulla ruota [w] (null la libera); un sensore sta su una ruota sola. */
    fun assignTire(w: Wheel, address: String?) {
        val s = _settings.value
        val tires = s.tires.filterValues { it != address }.toMutableMap()
        if (address != null) tires[w] = address else tires.remove(w)
        setSettings(s.copy(tires = tires))
    }

    fun onGps(fix: GpsFix) {
        lastGps = fix
    }

    private fun closeTrip() {
        val t = synchronized(tracker) { tracker.finish(_settings.value.fuelPrice) } ?: return
        saveTrip(t)
    }

    private fun saveTrip(t: Trip) {
        prefs?.edit()?.remove("trip.open")?.apply()
        val log = tripLog ?: return
        scope.launch {
            log.append(t)
            _trips.value = log.list()
        }
    }

    /** Ultimo dongle Bluetooth usato; null se l'ultimo era quello Wi-Fi. */
    val savedAddress: String? get() = prefs?.takeIf { it.getString("link", "bt") == "bt" }?.getString("addr", null)
    /** Indirizzo dell'ultimo dongle Wi-Fi che ha risposto. */
    val savedWifiHost: String? get() = prefs?.getString("wifi.host", null)
    val lastWasWifi: Boolean get() = prefs?.getString("link", null) == "wifi"

    fun connectBluetooth(adapter: BluetoothAdapter, address: String, name: String) {
        prefs?.edit()?.putString("link", "bt")?.putString("addr", address)?.putString("name", name)?.apply()
        start(name, demo = false) { BluetoothLink(adapter, address) }
    }

    /** [host] = "ip[:porta]" scritto a mano; null = quello ricordato, o ricerca automatica. */
    fun connectWifi(host: String? = null) {
        val c = cm ?: return
        val p = prefs
        p?.edit()?.putString("link", "wifi")?.apply()
        val manual = host?.takeIf { parseHostPort(it) != null } ?: p?.getString("wifi.host", null)
        start("OBD Wi-Fi", demo = false) {
            WifiLink(c, manual) { found -> p?.edit()?.putString("wifi.host", found)?.apply() }
        }
    }

    fun connectDemo() = start("Demo", demo = true) { DemoLink() }

    /**
     * All'avvio si ricollega da solo all'ultimo dongle usato, una volta sola. [adapter] è null finché il Bluetooth
     * non è pronto: per un dongle Bluetooth si aspetta la chiamata successiva, per quello Wi-Fi non serve.
     */
    fun autoConnectOnce(adapter: BluetoothAdapter?) {
        if (autoTried || _conn.value != Conn.Idle) return
        val p = prefs ?: return
        if (lastWasWifi) {
            autoTried = true
            connectWifi()
            return
        }
        if (adapter == null) return
        autoTried = true
        val addr = p.getString("addr", null) ?: return
        connectBluetooth(adapter, addr, p.getString("name", null) ?: addr)
    }

    fun startRecording() {
        val r = recorder ?: return
        r.start()
        _recordingSince.value = System.currentTimeMillis()
        refreshRecordings()
    }

    fun stopRecording() {
        recorder?.stop()
        _recordingSince.value = null
        refreshRecordings()
    }

    fun deleteRecording(r: Recording) {
        if (r.file.delete()) refreshRecordings()
    }

    fun refreshRecordings() {
        scope.launch { _recordings.value = recorder?.list().orEmpty() }
    }

    /** Dimentica le marce imparate per quest'auto, per esempio dopo aver cambiato misura delle gomme. */
    fun resetGears() {
        resetGears = true
    }

    /**
     * Ascolto della rete: per una quarantina di secondi le letture normali si fermano e il dongle ripete
     * i messaggi che le centraline si scambiano da sole. Prima tutto, poi un gruppo di indirizzi alla volta,
     * perché un dongle economico non regge tutto il traffico insieme e i messaggi rari si perderebbero.
     */
    fun startSniff() {
        val e = elm ?: return
        if (sniffJob?.isActive == true) return
        sniffJob = scope.launch { runSniff(e) }
    }

    /** Interrompe l'ascolto: quello che si è sentito fin lì resta nel riassunto e nel file. */
    fun stopSniff() {
        sniffJob?.cancel()
    }

    private suspend fun runSniff(e: Elm327) {
        val plan = if (e.is11bit) {
            // Ultimo il gruppo 7xx: è quello delle risposte OBD, così se il dongle non capisce il ripristino dei filtri
            // le domande normali passano comunque.
            listOf(SniffWindow(SNIFF_MS)) + (0..7).map { SniffWindow(SNIFF_MS, filter = it shl 8, mask = 0x700) }
        } else {
            List(5) { SniffWindow(SNIFF_MS * 2) }
        }
        val now = System.currentTimeMillis()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ITALY).format(java.util.Date(now))
        val dir = sniffDir
        val file = dir?.let { File(it, "ascolto_$stamp.txt") }
        val info = _info.value
        val header = listOf(
            "Cruscotto · ascolto della rete CAN · ${java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale.ITALY).format(java.util.Date(now))}",
            "Dongle: $label · ${info.elm ?: "?"} · protocollo ${info.protocol ?: "?"}" + if (demoNow) " · DEMO, dati simulati" else "",
            "VIN: ${info.vin ?: "N/D"} · marca: ${Wmi.brand(info.vin) ?: "N/D"}",
            "Valori OBD all'inizio: " + obdSnapshot(_live.value.values),
        )
        val writer = runCatching {
            dir?.mkdirs()
            // Si tengono gli ultimi cinque ascolti: ogni file pesa qualche centinaio di kB.
            dir?.listFiles()?.filter { it.name.startsWith("ascolto_") }?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
            file?.bufferedWriter(Charsets.UTF_8)
        }.getOrNull()
        val sniffer = Sniffer(writer)
        writer?.let { w -> header.forEach { w.write("# $it\n") }; w.write("# ms indirizzo byte…\n") }
        _sniff.value = SniffState(running = true, windows = plan.size)
        sniffing = true
        var problem: String? = null
        var stopped = false
        try {
            coroutineScope {
                // L'avanzamento si aggiorna ogni mezzo secondo, non a ogni messaggio.
                val ticker = launch {
                    while (true) {
                        delay(500)
                        _sniff.update { it.copy(frames = sniffer.frames, ids = sniffer.stats.size, overflows = sniffer.overflows) }
                    }
                }
                e.sniff(sniffer, plan, now) { i -> _sniff.update { it.copy(window = i + 1) } }
                ticker.cancel()
            }
        } catch (ce: CancellationException) {
            stopped = true
        } catch (ex: Exception) {
            problem = ex.message ?: ex.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                val end = runCatching {
                    e.endSniff()
                    readSnapshot(e)
                }.getOrElse { "non lette (${it.message})" }
                sniffing = false
                val summary = sniffer.summary(
                    header + listOf(
                        "Valori OBD alla fine: $end",
                        "Durata: ${sniffer.windows.sumOf { it.ms } / 1000} s in ${sniffer.windows.size} finestre" +
                            (if (sniffer.windows.any { it.mask != 0 }) " (la prima su tutto, poi un gruppo di indirizzi alla volta)" else " (filtri del dongle non disponibili)") +
                            (if (stopped) " · interrotto a mano" else "") + (problem?.let { " · fermato da un errore: $it" } ?: ""),
                    ),
                )
                runCatching {
                    writer?.write("# Valori OBD alla fine: $end\n# --- Riassunto ---\n")
                    writer?.write(summary.lines().joinToString("\n") { "# $it" })
                    writer?.close()
                }
                _sniff.value = SniffState(
                    frames = sniffer.frames,
                    ids = sniffer.stats.size,
                    overflows = sniffer.overflows,
                    message = problem ?: if (sniffer.frames == 0) "Nessun messaggio sentito: il motore è acceso?" else null,
                    summary = summary.takeIf { sniffer.frames > 0 },
                    file = file?.takeIf { it.exists() && sniffer.frames > 0 },
                )
            }
        }
    }

    private fun obdSnapshot(v: Map<Metric, Double>): String =
        listOf(Metric.RPM, Metric.SPEED, Metric.MAP, Metric.COOLANT, Metric.IAT, Metric.PEDAL, Metric.VOLT)
            .mapNotNull { m -> v[m]?.let { "${m.label} ${String.format(java.util.Locale.ITALY, "%.${m.decimals}f", it)} ${m.unit}" } }
            .joinToString(" · ").ifEmpty { "N/D" }

    private suspend fun readSnapshot(e: Elm327): String {
        val v = HashMap<Metric, Double>()
        for (pid in listOf(0x0C, 0x0D, 0x0B, 0x05, 0x0F, 0x49)) {
            e.mode01(pid)?.let { Pids.decode(pid, it) }?.let { (m, x) -> v[m] = x }
        }
        e.voltage()?.let { v[Metric.VOLT] = it }
        return obdSnapshot(v)
    }

    fun disconnect() {
        sniffJob?.cancel()
        if (_recordingSince.value != null) stopRecording()
        closeTrip()
        autoTried = true
        job?.cancel()
        job = null
        _conn.value = Conn.Idle
    }

    fun forgetDevice() {
        prefs?.edit()?.remove("addr")?.remove("name")?.remove("link")?.remove("wifi.host")?.apply()
        disconnect()
    }

    /** Le scelte a mano valgono per l'auto collegata: si ricordano per telaio. */
    fun setSetup(s: Setup) {
        prefs?.edit()?.let { ed ->
            if (s.fuel != null) ed.putString(carKey + "fuel", s.fuel.name) else ed.remove(carKey + "fuel")
            if (s.turbo != null) ed.putBoolean(carKey + "turbo", s.turbo) else ed.remove(carKey + "turbo")
            if (s.rpmMax != null) ed.putInt(carKey + "rpm", s.rpmMax) else ed.remove(carKey + "rpm")
            ed.apply()
        }
        _info.update { it.copy(profile = it.profile.copy(setup = s)) }
    }

    private fun loadProfile(vin: String?): Profile {
        carKey = "car.${vin ?: "nd"}."
        val p = prefs ?: return Profile()
        val fuel = p.getString(carKey + "fuel", null)?.let { n -> Fuel.entries.firstOrNull { it.name == n } }
        val turbo = if (p.contains(carKey + "turbo")) p.getBoolean(carKey + "turbo", false) else null
        val rpm = p.getInt(carKey + "rpm", 0).takeIf { it > 0 }
        return Profile(Setup(fuel, turbo, rpm), boostSeen = p.getBoolean(carKey + "boost", false))
    }

    private fun markBoostSeen() {
        prefs?.edit()?.putBoolean(carKey + "boost", true)?.apply()
        _info.update { it.copy(profile = it.profile.copy(boostSeen = true)) }
    }

    fun resetTrip() {
        resetTrip = true
    }

    fun resetPeaks() {
        resetPeaks = true
    }

    fun resetRecords() {
        resetRecords = true
    }

    private fun loadBests(): Map<Run, Double> {
        val p = prefs ?: return emptyMap()
        return Run.entries.mapNotNull { r ->
            p.getFloat(carKey + "best." + r.name, 0f).takeIf { it > 0f }?.let { r to it.toDouble() }
        }.toMap()
    }

    fun readDtcs() {
        if (sniffing) return
        val e = elm ?: return
        scope.launch { loadDiag(e) }
    }

    fun clearDtcs() {
        if (sniffing) return
        val e = elm ?: return
        scope.launch {
            _diag.update { it.copy(loading = true, message = null) }
            val msg = try {
                e.clearDtcs()
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                "Cancellazione non riuscita: ${ex.message}"
            }
            delay(1500)
            loadDiag(e)
            _diag.update { it.copy(message = msg) }
        }
    }

    private fun start(label: String, demo: Boolean, factory: () -> ElmLink) {
        sniffJob?.cancel()
        sniffing = false
        _sniff.value = SniffState()
        this.label = label
        demoNow = demo
        job?.cancel()
        closeTrip()
        _live.value = Live()
        _diag.value = Diag()
        _info.value = CarInfo()
        _analysis.value = Analysis()
        analyzer = Analyzer()
        history.clear()
        lastDtcCount = -1
        _conn.value = Conn.Connecting(label, "Avvio…")
        job = scope.launch { runLoop(label, demo, factory) }
    }

    private suspend fun runLoop(label: String, demo: Boolean, factory: () -> ElmLink) {
        var failures = 0
        while (true) {
            val link = factory()
            try {
                _conn.value = Conn.Connecting(label, "Collego il dongle…")
                link.open()
                val e = Elm327(link)
                e.init { _conn.value = Conn.Connecting(label, it) }
                _conn.value = Conn.Connecting(label, "Leggo cosa offre l'auto…")
                val pids = e.supportedPids()
                val vin = try { e.vin() } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { null }
                // Carburante: dal PID 51 se c'è, altrimenti dal tipo di accensione che la centralina dichiara.
                val fuel = (if (0x51 in pids) e.mode01(0x51)?.firstOrNull()?.let(Fuel::fromPid51) else null)
                    ?: e.mode01(0x01)?.let(ObdParse::status)?.let { if (it.diesel) Fuel.DIESEL else Fuel.PETROL }
                _info.value = CarInfo(e.version, e.protocol, vin, pids, loadProfile(vin).copy(detectedFuel = fuel))
                analyzer.setBests(loadBests())
                if (gearsKey != carKey) {
                    gearsKey = carKey
                    gears.load(prefs?.getString(carKey + "gears", null))
                    dyno.restore(DynoResult.load(prefs?.getString(carKey + "dyno", null)))
                    _dyno.value = dyno.state
                }
                elm = e
                failures = 0
                _conn.value = Conn.Connected(label, demo)
                coroutineScope {
                    launch { loadDiag(e) }
                    poll(e, pids, this)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                failures++
                _conn.value = Conn.Retrying(label, ex.message ?: ex.javaClass.simpleName)
            } finally {
                elm = null
                link.close()
            }
            delay(if (failures < 5) 3_000 else 10_000)
        }
    }

    private suspend fun loadDiag(e: Elm327) {
        _diag.update { it.copy(loading = true, message = null) }
        try {
            val status = e.mode01(0x01)?.let(ObdParse::status)
            val stored = e.dtcs(0x03).orEmpty()
            val pending = e.dtcs(0x07).orEmpty()
            val permanent = e.dtcs(0x0A)
            val freeze = e.freezeFrame()
            status?.let { lastDtcCount = it.dtcCount }
            _diag.value = Diag(
                loading = false,
                readAt = System.currentTimeMillis(),
                stored = stored,
                pending = pending,
                permanent = permanent,
                status = status,
                freeze = freeze,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            _diag.update { it.copy(loading = false, message = "Lettura non riuscita: ${ex.message}") }
        }
    }

    private suspend fun poll(e: Elm327, supported: Set<Int>, children: CoroutineScope) {
        // Giri, velocità e pressione nel collettore a ogni giro; il resto a rotazione, uno per giro.
        val fast = listOf(0x0C, 0x0D, 0x0B).filter { it in supported }
        val slow = listOf(0x05, 0x0F, 0x04, 0x49, 0x11, 0x0E, 0x10, 0x5E, 0x44, 0x06, 0x07, 0x2F, 0x5C, 0x46)
            .filter { it in supported }
        val values = HashMap<Metric, Double>()
        val peaks = HashMap<Metric, Double>()
        var learnedBaro: Double? = null
        var tripKm = 0.0
        var tripFuel = 0.0
        var slowIdx = 0
        var lastT = System.currentTimeMillis()
        var baroAt = 0L
        var fuelAt = System.currentTimeMillis()
        var statusAt = System.currentTimeMillis()
        var queries = 0
        var hzT = lastT
        var hz = 0.0
        var tripSavedAt = 0L

        suspend fun read(pid: Int) {
            queries++
            e.mode01(pid)?.let { Pids.decode(pid, it) }?.let { (m, v) -> values[m] = v }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            if (sniffing) {
                // Il dongle è occupato ad ascoltare la rete: le letture riprendono dopo.
                delay(200)
                lastT = System.currentTimeMillis()
                continue
            }
            for (p in fast) read(p)
            val k = slowIdx++ % (slow.size + 1)
            if (k < slow.size) {
                read(slow[k])
            } else {
                queries++
                e.voltage()?.let { values[Metric.VOLT] = it }
            }
            val now = System.currentTimeMillis()
            if (0x33 in supported && now - baroAt > 60_000) {
                read(0x33)
                baroAt = now
            }
            if (0x51 in supported && now - fuelAt > 60_000) {
                fuelAt = now
                queries++
                e.mode01(0x51)?.firstOrNull()?.let(Fuel::fromPid51)?.let { f ->
                    _info.update { it.copy(profile = it.profile.copy(detectedFuel = f)) }
                }
            }
            if (0x01 in supported && now - statusAt > 15_000) {
                statusAt = now
                queries++
                e.mode01(0x01)?.let(ObdParse::status)?.let { s ->
                    _diag.update { it.copy(status = s) }
                    if (lastDtcCount >= 0 && s.dtcCount != lastDtcCount && !_diag.value.loading) {
                        children.launch { loadDiag(e) }
                    }
                    lastDtcCount = s.dtcCount
                }
            }

            val profile = _info.value.profile

            // Pressione relativa: collettore meno atmosferica (letta, o presa a motore spento).
            // Se una volta supera la soglia, il motore è sovralimentato e lo si ricorda per questa auto.
            val map = values[Metric.MAP]
            if (map != null && values[Metric.RPM] == 0.0) learnedBaro = map
            val baro = values[Metric.BARO] ?: learnedBaro ?: 101.3
            if (map != null) {
                val boost = (map - baro) / 100.0
                values[Metric.BOOST] = boost
                if (boost > Profile.BOOST_PROOF && !profile.boostSeen) markBoostSeen()
            }

            // Consumo stimato dal flusso d'aria: aria / (rapporto stechiometrico × lambda comandata), poi in litri.
            // I diesel vanno sempre magri e lambda non si conosce: lì vale solo il consumo dato dalla centralina.
            val density = profile.fuel.density.takeIf { profile.fuel != Fuel.DIESEL }
            if (0x5E !in supported) {
                val maf = values[Metric.MAF]
                val lambda = values[Metric.LAMBDA]?.takeIf { it in 0.5..1.99 } ?: 1.0
                if (maf != null && density != null) {
                    values[Metric.FUEL_RATE] = maf * 3600.0 / (profile.fuel.afr * lambda * density)
                } else {
                    values.remove(Metric.FUEL_RATE)
                }
            }
            val available = buildSet {
                supported.mapNotNullTo(this) { Pids.byPid[it]?.metric }
                add(Metric.VOLT)
                if (0x0B in supported && profile.turbo) {
                    remove(Metric.MAP)
                    add(Metric.BOOST)
                }
                if (0x5E in supported || (0x10 in supported && density != null)) add(Metric.FUEL_RATE)
            }

            val speed = values[Metric.SPEED]
            val fuel = values[Metric.FUEL_RATE]
            val dt = (now - lastT) / 1000.0
            lastT = now
            if (resetTrip) {
                resetTrip = false
                tripKm = 0.0
                tripFuel = 0.0
            }
            if (dt in 0.0..5.0) {
                if (speed != null) tripKm += speed * dt / 3600
                if (fuel != null) tripFuel += fuel * dt / 3600
            }
            if (resetPeaks) {
                resetPeaks = false
                peaks.clear()
            }
            for ((m, v) in values) {
                if (v > (peaks[m] ?: Double.NEGATIVE_INFINITY)) peaks[m] = v
                history.add(m, now, v.toFloat())
            }
            history.bump()
            if (now - hzT >= 2000) {
                hz = queries * 1000.0 / (now - hzT)
                queries = 0
                hzT = now
            }
            if (resetRecords) {
                resetRecords = false
                analyzer.clearRecords()
                prefs?.edit()?.let { ed -> Run.entries.forEach { ed.remove(carKey + "best." + it.name) }; ed.apply() }
            }
            val dtOk = if (dt in 0.0..5.0) dt else 0.0
            val gps = lastGps?.takeIf { now - it.t < 3000 }
            val records = analyzer.feed(now, dtOk, values, profile, gps?.kmh)
            if (records.isNotEmpty()) {
                prefs?.edit()?.let { ed -> records.forEach { (r, s) -> ed.putFloat(carKey + "best." + r.name, s.toFloat()) }; ed.apply() }
            }
            _analysis.value = analyzer.snapshot(now, profile, available, values, gpsStatus)
            if (resetGears) {
                resetGears = false
                gears.clear()
            }
            gears.feed(speed, values[Metric.RPM], dtOk)
            if (gears.takeDirty()) prefs?.edit()?.putString(carKey + "gears", gears.save())?.apply()
            val ratios = gears.ratios()
            val gear = gears.gear(speed, values[Metric.RPM], ratios)
            recorder?.takeIf { it.active }?.write(now, values, gear, gps)

            val settings = _settings.value
            val pedal = values[Metric.PEDAL] ?: values[Metric.THROTTLE] ?: values[Metric.LOAD]
            dyno.feed(now, values[Metric.RPM], gear, gear?.let { ratios.getOrNull(it - 1) }, pedal, settings.massKg)?.let { r ->
                prefs?.edit()?.putString(carKey + "dyno", r.save())?.apply()
            }
            _dyno.value = dyno.state
            val advice = advisor.feed(dtOk, values, profile)
            val tires = Tpms.states(settings, now)
            alerter.feed(now, dtOk, values, _diag.value.status, advice, advisor.cooledDown, tires).forEach {
                if (settings.voice) _alerts.tryEmit(it)
            }
            val ended = synchronized(tracker) { tracker.feed(now, dtOk, speed, values[Metric.RPM], fuel, settings.fuelPrice) }
            if (ended != null) {
                saveTrip(ended)
            } else if (now - tripSavedAt > 30_000) {
                tripSavedAt = now
                synchronized(tracker) { tracker.snapshot(settings.fuelPrice) }?.let { prefs?.edit()?.putString("trip.open", it.line())?.apply() }
            }
            val instant = if (speed != null && speed >= 5 && fuel != null) fuel / speed * 100 else null
            _live.value = Live(HashMap(values), available, HashMap(peaks), instant, tripKm, tripFuel, hz, gear, ratios, advice, gps?.kmh)
        }
    }
}
