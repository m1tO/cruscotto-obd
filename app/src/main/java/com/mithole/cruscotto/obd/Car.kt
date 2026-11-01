package com.mithole.cruscotto.obd

/** Carburante: rapporto stechiometrico aria/carburante e densità in g/L, per stimare il consumo dal flusso d'aria. */
enum class Fuel(val label: String, val afr: Double, val density: Double?) {
    PETROL("Benzina", 14.7, 745.0),
    DIESEL("Diesel", 14.5, 832.0),
    LPG("GPL", 15.5, 540.0),
    CNG("Metano", 17.2, null), // il metano si misura a kg, non a litri
    ;

    companion object {
        /** PID 01 51 (tipo di carburante); sulle bifuel dice quello in uso in quel momento. */
        fun fromPid51(code: Int): Fuel? = when (code) {
            0x01, 0x09, 0x11 -> PETROL
            0x04, 0x13, 0x17 -> DIESEL
            0x05, 0x07, 0x0C, 0x0E -> LPG
            0x06, 0x0D -> CNG
            else -> null
        }
    }
}

/** Le scelte fatte a mano nella scheda Auto; null vuol dire "lo decide l'app". */
data class Setup(val fuel: Fuel? = null, val turbo: Boolean? = null, val rpmMax: Int? = null)

/** Com'è fatta l'auto collegata: quello che si legge dalla centralina, corretto dalle scelte a mano. */
data class Profile(
    val setup: Setup = Setup(),
    val detectedFuel: Fuel? = null,
    val boostSeen: Boolean = false,
) {
    val fuel: Fuel get() = setup.fuel ?: detectedFuel ?: Fuel.PETROL
    val autoTurbo: Boolean get() = boostSeen || fuel == Fuel.DIESEL
    val turbo: Boolean get() = setup.turbo ?: autoTurbo
    val autoRpmMax: Int get() = if (fuel == Fuel.DIESEL) 6000 else 7000
    val rpmMax: Int get() = setup.rpmMax ?: autoRpmMax
    val rpmRed: Int get() = if (setup.rpmMax == null && fuel == Fuel.DIESEL) 4500 else rpmMax - 750

    /** Giri a cui la shift light lampeggia: poco prima della zona rossa. */
    val shiftRpm: Int get() = rpmRed - 250

    // Manometro: depressione da -1 bar; i diesel soffiano più forte dei benzina.
    val boostMax: Double get() = if (!turbo) 0.0 else if (fuel == Fuel.DIESEL) 2.5 else 1.6
    val boostRed: Double? get() = if (!turbo) null else if (fuel == Fuel.DIESEL) 2.0 else 1.2

    companion object {
        val RPM_CHOICES = listOf(5000, 6000, 7000, 8000, 9000)

        /** Sopra questa pressione relativa (bar) un motore aspirato non ci arriva: c'è il turbo. */
        const val BOOST_PROOF = 0.15
    }
}

/** Marca dal telaio (le prime lettere del VIN), per le case più diffuse in Italia. */
object Wmi {
    private val TABLE = mapOf(
        "ZAR" to "Alfa Romeo", "ZFA" to "Fiat", "ZFC" to "Fiat", "ZLA" to "Lancia", "ZAM" to "Maserati",
        "ZFF" to "Ferrari", "ZHW" to "Lamborghini", "ZCF" to "Iveco",
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen", "WVG" to "Volkswagen",
        "WAU" to "Audi", "WUA" to "Audi", "TRU" to "Audi", "WBA" to "BMW", "WBS" to "BMW", "WBY" to "BMW",
        "WMW" to "Mini", "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz",
        "W1K" to "Mercedes-Benz", "W1N" to "Mercedes-Benz", "WME" to "smart", "W0L" to "Opel", "W0V" to "Opel",
        "WF0" to "Ford", "WP0" to "Porsche", "WP1" to "Porsche",
        "VF1" to "Renault", "VF3" to "Peugeot", "VR3" to "Peugeot", "VF7" to "Citroën", "VR7" to "Citroën",
        "VR1" to "DS", "UU1" to "Dacia", "VSS" to "Seat", "TMB" to "Škoda", "YV1" to "Volvo",
        "SAJ" to "Jaguar", "SAL" to "Land Rover", "SB1" to "Toyota", "VNK" to "Toyota", "NMT" to "Toyota",
        "JHM" to "Honda", "JMZ" to "Mazda", "JM1" to "Mazda", "JN1" to "Nissan", "SJN" to "Nissan",
        "JS1" to "Suzuki", "JSA" to "Suzuki", "TSM" to "Suzuki", "JMB" to "Mitsubishi", "JF1" to "Subaru",
        "KNA" to "Kia", "KNE" to "Kia", "U5Y" to "Kia", "KMH" to "Hyundai", "TMA" to "Hyundai",
        "5YJ" to "Tesla", "LRW" to "Tesla", "LSJ" to "MG",
    )

    fun brand(vin: String?): String? {
        if (vin == null || vin.length < 3) return null
        return TABLE[vin.take(3).uppercase()] ?: if (vin.startsWith("JT", ignoreCase = true)) "Toyota" else null
    }
}
