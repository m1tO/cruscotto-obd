package com.mithole.cruscotto.obd

data class Dtc(val code: String) {
    val description: String get() = DtcText.describe(code)
    val system: String get() = DtcText.system(code)
}

object DtcCodec {
    private const val LETTERS = "PCBU"

    fun decode(a: Int, b: Int): String =
        "${LETTERS[a shr 6]}${(a shr 4) and 3}%X%X%X".format(a and 0xF, b shr 4, b and 0xF)

    fun encode(code: String): List<Int> {
        val a = (LETTERS.indexOf(code[0]) shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
        val b = (code[3].digitToInt(16) shl 4) or code[4].digitToInt(16)
        return listOf(a, b)
    }
}

/** Descrizioni in italiano dei codici generici SAE più comuni. */
object DtcText {
    fun system(code: String): String {
        val manufacturer = code.getOrNull(1) == '1' || (code[0] == 'P' && code.getOrNull(1) == '3')
        val base = when (code[0]) {
            'C' -> "Telaio"
            'B' -> "Carrozzeria"
            'U' -> "Rete di comunicazione"
            else -> when (code.getOrNull(2)) {
                '0', '1', '2' -> "Aria e carburante"
                '3' -> "Accensione"
                '4' -> "Controllo emissioni"
                '5' -> "Velocità e minimo"
                '6' -> "Centralina"
                '7', '8', '9' -> "Cambio"
                else -> "Motore"
            }
        }
        return if (manufacturer) "$base · specifico del costruttore" else base
    }

    fun describe(code: String): String {
        TABLE[code]?.let { return it }
        val n = code.substring(1).toIntOrNull()
        if (code[0] == 'P' && n != null) {
            when (n) {
                in 201..212 -> return "Circuito iniettore cilindro ${n - 200}"
                in 301..312 -> return "Mancata accensione cilindro ${n - 300}"
                in 351..362 -> return "Circuito bobina di accensione ${n - 350}"
            }
        }
        val manufacturer = code.getOrNull(1) == '1' || (code[0] == 'P' && code.getOrNull(1) == '3')
        return if (manufacturer) {
            "Codice specifico del costruttore: il significato esatto dipende dalla marca e dal motore."
        } else {
            "Codice generico non presente nella tabella dell'app."
        }
    }

    private val TABLE = mapOf(
        "P0010" to "Attuatore fasatura albero a camme (bancata 1): circuito",
        "P0011" to "Fasatura albero a camme troppo anticipata o prestazioni (bancata 1)",
        "P0012" to "Fasatura albero a camme troppo ritardata (bancata 1)",
        "P0016" to "Correlazione albero motore / albero a camme (bancata 1)",
        "P0030" to "Riscaldatore sonda lambda a monte (B1 S1): circuito di controllo",
        "P0036" to "Riscaldatore sonda lambda a valle (B1 S2): circuito di controllo",
        "P0068" to "Incoerenza tra pressione collettore / flusso d'aria e posizione farfalla",
        "P0087" to "Pressione carburante troppo bassa",
        "P0088" to "Pressione carburante troppo alta",
        "P0100" to "Sensore flusso d'aria (MAF): circuito",
        "P0101" to "Sensore flusso d'aria (MAF): valore fuori campo",
        "P0102" to "Sensore flusso d'aria (MAF): segnale basso",
        "P0103" to "Sensore flusso d'aria (MAF): segnale alto",
        "P0105" to "Sensore pressione collettore / barometrica: circuito",
        "P0106" to "Sensore pressione collettore / barometrica: valore fuori campo",
        "P0107" to "Sensore pressione collettore: segnale basso",
        "P0108" to "Sensore pressione collettore: segnale alto",
        "P0110" to "Sensore temperatura aria aspirata: circuito",
        "P0111" to "Sensore temperatura aria aspirata: valore fuori campo",
        "P0112" to "Sensore temperatura aria aspirata: segnale basso",
        "P0113" to "Sensore temperatura aria aspirata: segnale alto",
        "P0115" to "Sensore temperatura liquido refrigerante: circuito",
        "P0116" to "Sensore temperatura liquido refrigerante: valore fuori campo",
        "P0117" to "Sensore temperatura liquido refrigerante: segnale basso",
        "P0118" to "Sensore temperatura liquido refrigerante: segnale alto",
        "P0120" to "Sensore posizione farfalla / pedale A: circuito",
        "P0121" to "Sensore posizione farfalla A: valore fuori campo",
        "P0122" to "Sensore posizione farfalla A: segnale basso",
        "P0123" to "Sensore posizione farfalla A: segnale alto",
        "P0125" to "Il motore non scalda abbastanza per la regolazione della miscela",
        "P0128" to "Temperatura liquido sotto la soglia del termostato (termostato bloccato aperto?)",
        "P0130" to "Sonda lambda a monte (B1 S1): circuito",
        "P0131" to "Sonda lambda a monte (B1 S1): tensione bassa",
        "P0132" to "Sonda lambda a monte (B1 S1): tensione alta",
        "P0133" to "Sonda lambda a monte (B1 S1): risposta lenta",
        "P0134" to "Sonda lambda a monte (B1 S1): nessuna attività",
        "P0135" to "Riscaldatore sonda lambda a monte (B1 S1): circuito",
        "P0136" to "Sonda lambda a valle (B1 S2): circuito",
        "P0137" to "Sonda lambda a valle (B1 S2): tensione bassa",
        "P0138" to "Sonda lambda a valle (B1 S2): tensione alta",
        "P0139" to "Sonda lambda a valle (B1 S2): risposta lenta",
        "P0140" to "Sonda lambda a valle (B1 S2): nessuna attività",
        "P0141" to "Riscaldatore sonda lambda a valle (B1 S2): circuito",
        "P0171" to "Miscela troppo magra (bancata 1)",
        "P0172" to "Miscela troppo grassa (bancata 1)",
        "P0217" to "Surriscaldamento del motore",
        "P0219" to "Fuorigiri del motore",
        "P0221" to "Sensore posizione farfalla B: valore fuori campo",
        "P0222" to "Sensore posizione farfalla B: segnale basso",
        "P0223" to "Sensore posizione farfalla B: segnale alto",
        "P0234" to "Sovralimentazione eccessiva del turbo (overboost)",
        "P0235" to "Sensore pressione turbo A: circuito",
        "P0236" to "Sensore pressione turbo A: valore fuori campo",
        "P0237" to "Sensore pressione turbo A: segnale basso",
        "P0238" to "Sensore pressione turbo A: segnale alto",
        "P0243" to "Elettrovalvola wastegate turbo A: circuito",
        "P0245" to "Elettrovalvola wastegate turbo A: segnale basso",
        "P0246" to "Elettrovalvola wastegate turbo A: segnale alto",
        "P0299" to "Sovralimentazione insufficiente del turbo (underboost)",
        "P0300" to "Mancate accensioni casuali o su più cilindri",
        "P0325" to "Sensore di battito: circuito",
        "P0326" to "Sensore di battito: valore fuori campo",
        "P0327" to "Sensore di battito: segnale basso",
        "P0328" to "Sensore di battito: segnale alto",
        "P0335" to "Sensore posizione albero motore: circuito",
        "P0336" to "Sensore posizione albero motore: valore fuori campo",
        "P0340" to "Sensore posizione albero a camme: circuito",
        "P0341" to "Sensore posizione albero a camme: valore fuori campo",
        "P0400" to "Ricircolo gas di scarico (EGR): flusso",
        "P0401" to "Ricircolo gas di scarico (EGR): flusso insufficiente",
        "P0420" to "Efficienza del catalizzatore sotto la soglia (bancata 1)",
        "P0441" to "Sistema evaporativo: flusso di spurgo non corretto",
        "P0442" to "Sistema evaporativo: piccola perdita",
        "P0443" to "Valvola di spurgo canister: circuito",
        "P0455" to "Sistema evaporativo: grande perdita",
        "P0456" to "Sistema evaporativo: perdita molto piccola",
        "P0480" to "Ventola di raffreddamento 1: circuito di controllo",
        "P0481" to "Ventola di raffreddamento 2: circuito di controllo",
        "P0500" to "Sensore velocità veicolo",
        "P0501" to "Sensore velocità veicolo: valore fuori campo",
        "P0505" to "Sistema di controllo del minimo",
        "P0506" to "Regime minimo più basso del previsto",
        "P0507" to "Regime minimo più alto del previsto",
        "P0560" to "Tensione di sistema",
        "P0562" to "Tensione di sistema bassa",
        "P0563" to "Tensione di sistema alta",
        "P0571" to "Interruttore del freno: circuito",
        "P0600" to "Collegamento seriale della centralina",
        "P0601" to "Centralina: errore di checksum della memoria",
        "P0602" to "Centralina: errore di programmazione",
        "P0603" to "Centralina: errore della memoria permanente (KAM)",
        "P0604" to "Centralina: errore della RAM",
        "P0605" to "Centralina: errore della ROM",
        "P0606" to "Centralina: errore del processore",
        "P0607" to "Centralina: prestazioni del modulo",
        "P0627" to "Pompa carburante: circuito di controllo",
        "P0638" to "Attuatore farfalla: valore fuori campo (bancata 1)",
        "P0641" to "Tensione di riferimento sensori A: circuito aperto",
        "P0645" to "Relè compressore climatizzatore: circuito",
        "P0650" to "Spia motore (MIL): circuito di controllo",
        "P0651" to "Tensione di riferimento sensori B: circuito aperto",
        "P0685" to "Relè di alimentazione centralina: circuito aperto",
        "P0700" to "Il cambio segnala un guasto",
        "P2096" to "Correzione dopo il catalizzatore: miscela troppo magra",
        "P2097" to "Correzione dopo il catalizzatore: miscela troppo grassa",
        "P2100" to "Motorino attuatore farfalla: circuito aperto",
        "P2101" to "Motorino attuatore farfalla: valore fuori campo",
        "P2106" to "Attuatore farfalla: potenza limitata (recovery)",
        "P2111" to "Attuatore farfalla bloccato aperto",
        "P2112" to "Attuatore farfalla bloccato chiuso",
        "P2122" to "Sensore pedale acceleratore D: segnale basso",
        "P2123" to "Sensore pedale acceleratore D: segnale alto",
        "P2127" to "Sensore pedale acceleratore E: segnale basso",
        "P2128" to "Sensore pedale acceleratore E: segnale alto",
        "P2135" to "Sensori posizione farfalla A/B: valori incoerenti",
        "P2138" to "Sensori pedale acceleratore D/E: valori incoerenti",
        "P2177" to "Miscela troppo magra fuori dal minimo (bancata 1)",
        "P2178" to "Miscela troppo grassa fuori dal minimo (bancata 1)",
        "P2187" to "Miscela troppo magra al minimo (bancata 1)",
        "P2188" to "Miscela troppo grassa al minimo (bancata 1)",
        "P2195" to "Sonda lambda a monte (B1 S1) bloccata su magra",
        "P2196" to "Sonda lambda a monte (B1 S1) bloccata su grassa",
        "P2261" to "Valvola di ricircolo turbo (pop-off): guasto meccanico",
        "P2263" to "Prestazioni del sistema di sovralimentazione",
        "P2270" to "Sonda lambda a valle (B1 S2) bloccata su magra",
        "P2271" to "Sonda lambda a valle (B1 S2) bloccata su grassa",
        "P2279" to "Perdita d'aria nell'aspirazione",
        "U0001" to "Bus CAN ad alta velocità",
        "U0100" to "Comunicazione persa con la centralina motore",
        "U0101" to "Comunicazione persa con la centralina del cambio",
        "U0121" to "Comunicazione persa con la centralina ABS",
        "U0140" to "Comunicazione persa con la centralina carrozzeria (BCM)",
        "U0155" to "Comunicazione persa con il quadro strumenti",
    )
}
