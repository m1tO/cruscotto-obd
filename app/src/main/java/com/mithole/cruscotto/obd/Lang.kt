package com.mithole.cruscotto.obd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Le due lingue dell'app. Il nome si mostra sempre nella sua lingua, così si ritrova anche senza capire l'altra. */
enum class Lang(val locale: Locale, val tag: String, val native: String) {
    IT(Locale.ITALY, "it", "Italiano"),
    EN(Locale.UK, "en", "English"),
    ;

    companion object {
        fun fromTag(tag: String?): Lang? = entries.firstOrNull { it.tag == tag }

        /** Senza una scelta salvata: italiano se il tablet è in italiano, altrimenti inglese. */
        fun forDevice(locale: Locale): Lang = if (locale.language == IT.tag) IT else EN
    }
}

/**
 * Lingua corrente. È uno stato di Compose: le schermate che leggono un testo con [t] si ridisegnano da sole
 * quando cambia. Senza init (nei test) resta l'italiano, la lingua in cui l'app è nata.
 */
object L {
    var lang: Lang by mutableStateOf(Lang.IT)
    val locale: Locale get() = lang.locale
    val en: Boolean get() = lang == Lang.EN
}

/** Il testo nella lingua corrente. Va chiamato quando il testo serve, non salvato in costanti o in enum. */
fun t(it: String, en: String): String = if (L.lang == Lang.EN) en else it
