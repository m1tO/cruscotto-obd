package com.mithole.cruscotto.obd

enum class Metric(val label: String, val unit: String, val decimals: Int) {
    RPM("Giri motore", "giri/min", 0),
    SPEED("Velocità", "km/h", 0),
    BOOST("Turbo", "bar", 2),
    MAP("Pressione collettore", "kPa", 0),
    COOLANT("Acqua", "°C", 0),
    IAT("Aria aspirata", "°C", 0),
    VOLT("Batteria", "V", 1),
    LOAD("Carico motore", "%", 0),
    PEDAL("Acceleratore", "%", 0),
    THROTTLE("Farfalla", "%", 0),
    TIMING("Anticipo", "°", 1),
    MAF("Flusso aria", "g/s", 1),
    FUEL_RATE("Consumo orario", "L/h", 1),
    STFT("Correzione miscela breve", "%", 1),
    LTFT("Correzione miscela lunga", "%", 1),
    FUEL_LEVEL("Carburante", "%", 0),
    OIL("Olio", "°C", 0),
    AMBIENT("Temperatura esterna", "°C", 0),
    BARO("Pressione atmosferica", "kPa", 0),
    LAMBDA("Lambda comandata", "λ", 2),
}

class PidDef(val pid: Int, val metric: Metric, val size: Int, val decode: (List<Int>) -> Double)

/** PID standard del modo 01 (SAE J1979) con le formule di conversione. */
object Pids {
    val all = listOf(
        PidDef(0x04, Metric.LOAD, 1) { it[0] * 100.0 / 255 },
        PidDef(0x05, Metric.COOLANT, 1) { it[0] - 40.0 },
        PidDef(0x06, Metric.STFT, 1) { (it[0] - 128) * 100.0 / 128 },
        PidDef(0x07, Metric.LTFT, 1) { (it[0] - 128) * 100.0 / 128 },
        PidDef(0x0B, Metric.MAP, 1) { it[0].toDouble() },
        PidDef(0x0C, Metric.RPM, 2) { (it[0] * 256 + it[1]) / 4.0 },
        PidDef(0x0D, Metric.SPEED, 1) { it[0].toDouble() },
        PidDef(0x0E, Metric.TIMING, 1) { it[0] / 2.0 - 64 },
        PidDef(0x0F, Metric.IAT, 1) { it[0] - 40.0 },
        PidDef(0x10, Metric.MAF, 2) { (it[0] * 256 + it[1]) / 100.0 },
        PidDef(0x11, Metric.THROTTLE, 1) { it[0] * 100.0 / 255 },
        PidDef(0x2F, Metric.FUEL_LEVEL, 1) { it[0] * 100.0 / 255 },
        PidDef(0x33, Metric.BARO, 1) { it[0].toDouble() },
        PidDef(0x44, Metric.LAMBDA, 2) { (it[0] * 256 + it[1]) * 2.0 / 65536 },
        PidDef(0x46, Metric.AMBIENT, 1) { it[0] - 40.0 },
        PidDef(0x49, Metric.PEDAL, 1) { it[0] * 100.0 / 255 },
        PidDef(0x5C, Metric.OIL, 1) { it[0] - 40.0 },
        PidDef(0x5E, Metric.FUEL_RATE, 2) { (it[0] * 256 + it[1]) / 20.0 },
    )

    val byPid = all.associateBy { it.pid }

    fun decode(pid: Int, data: List<Int>): Pair<Metric, Double>? {
        val def = byPid[pid] ?: return null
        if (data.size < def.size) return null
        return def.metric to def.decode(data)
    }
}
