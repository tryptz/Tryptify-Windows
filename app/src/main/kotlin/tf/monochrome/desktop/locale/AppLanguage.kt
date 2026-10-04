package tf.monochrome.desktop.locale

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.desktop.res.Strings

/**
 * The app's own display language, independent of the computer's.
 *
 * Android had two mechanisms (LocaleManager on 13+, a SharedPreferences tag
 * plus a wrapped activity context below). Desktop: there is one process-wide
 * language. The choice is kept in a one-line file in the app's data folder —
 * it has to be readable synchronously at startup, before the first frame, which
 * rules out the DataStore the rest of the settings live in, exactly as it did
 * for Android's `attachBaseContext` — and applying it hands the language to
 * [Strings] (whose `language` flow the composables observe, so the window
 * re-renders) and moves [Locale.getDefault], as the system does when it
 * applies a language: `String.format` and the number formatting in the UI read
 * it, so a German screen writes "1,5 dB" rather than mixing conventions.
 *
 * Desktop: this stays an `object`, the process-wide singleton, rather than an
 * injected class: the settings screen, the share helper and the tests call it
 * statically, and the stored choice must be applied before the object graph's
 * first screen exists.
 *
 * An empty tag means "follow the computer", which is the default and the only
 * value that is never stored as a locale.
 */
object AppLanguage {

    /**
     * One language on offer. [nativeName] is the language's name in itself —
     * 日本語, not "Japanese" — so whoever lands in a language they cannot read
     * can still find their own in the list.
     */
    data class Option(val tag: String, val nativeName: String)

    /**
     * The languages there are translations for, in the picker's order. Keep in
     * step with the translation table (`app/l10n/strings.tsv`, shipped as
     * `resources/i18n/strings_<lang>.json`): a tag here with no table behind it
     * would switch the app to English while claiming otherwise.
     *
     * Chinese is Simplified (`zh-CN`), the script of the mainland and of
     * Singapore. A Traditional reader (Taiwan, Hong Kong) whose computer asks for
     * zh-TW does not match it and gets English rather than a script they did not
     * choose; they can still pick it here.
     */
    val OPTIONS: List<Option> = listOf(
        Option("en", "English"),
        Option("zh-CN", "简体中文"),
        Option("ja", "日本語"),
        Option("fr", "Français"),
        Option("es", "Español"),
        Option("tr", "Türkçe"),
        Option("de", "Deutsch"),
    )

    /** The computer's own locale, captured before this object ever moves the default. */
    private val systemLocale: Locale = Locale.getDefault()

    private val _chosen = MutableStateFlow<String?>(null)

    /**
     * The chosen tag ("" = follow the computer), once loaded. A root composable
     * can key its content on this (or on `Strings.language`) to rebuild screens
     * that cached text, the desktop analogue of Android recreating the activity.
     */
    val chosen: StateFlow<String?> = _chosen.asStateFlow()

    @Volatile private var applied = false

    /** The tag in effect: one of [OPTIONS]' tags, or "" for the computer's language. */
    fun current(context: Context): String {
        _chosen.value?.let { return it }
        return matchOption(readStored(context))
    }

    /**
     * Switches the app to [tag] ("" for the computer's language), stores it and
     * applies it at once.
     *
     * Android recreated the activity here; on the desktop [Strings.language]
     * changes and every `stringResource` re-reads it.
     */
    fun set(context: Context, tag: String) {
        val clean = matchOption(tag)
        writeStored(context, clean)
        applyLanguage(clean)
    }

    /**
     * Android built the activity's base context in the chosen language here.
     * Desktop: there is one language for the whole process, so [base] is
     * returned unchanged — after making sure the stored choice has been applied,
     * which makes this safe to call as the startup hook (main() before the
     * first frame) as well as from the call sites that wrapped a context.
     */
    fun wrap(base: Context): Context {
        applyStored(base)
        return base
    }

    /** Applies the stored choice once per process; later calls are free. */
    fun applyStored(context: Context) {
        if (applied) return
        synchronized(this) {
            if (applied) return
            applyLanguage(matchOption(readStored(context)))
        }
    }

    /**
     * [tag] as the [OPTIONS] tag it means, or "" if it means none of them.
     *
     * Exact match first, then by language alone, so a stored or system-reported
     * `de-AT` or `fr-CA` still lands on German or French. Chinese is the one
     * language where the region carries the script: `zh-TW` and `zh-HK` (and an
     * explicit `zh-Hant`) are Traditional and must not be taken for the
     * Simplified translation.
     */
    fun matchOption(tag: String): String {
        if (tag.isBlank()) return ""
        OPTIONS.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it.tag }
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language
        if (language == "zh") {
            val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                (locale.script.isEmpty() && locale.country.uppercase() in TRADITIONAL_REGIONS)
            return if (traditional) "" else "zh-CN"
        }
        return OPTIONS.firstOrNull { Locale.forLanguageTag(it.tag).language == language }?.tag ?: ""
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    /**
     * Makes [clean] (an [OPTIONS] tag or "") the language in effect.
     *
     * "" resolves the computer's language through [matchOption] here rather
     * than leaving it to [Strings], so its rules hold for the system language
     * too: a zh-TW computer gets English, as Android gave it, not Simplified.
     */
    private fun applyLanguage(clean: String) {
        val effective = clean.ifEmpty { matchOption(systemLocale.toLanguageTag()) }
        Strings.setLanguage(tableLanguage(effective))
        Locale.setDefault(if (clean.isEmpty()) systemLocale else Locale.forLanguageTag(clean))
        _chosen.value = clean
        applied = true
    }

    /** The translation table's name for an [OPTIONS] tag ("" → English). */
    private fun tableLanguage(tag: String): String = when (tag) {
        "" -> Strings.DEFAULT
        "zh-CN" -> "zh"
        else -> Locale.forLanguageTag(tag).language
    }

    private fun file(context: Context): File = context.filesDir.resolve(FILE_NAME)

    private fun readStored(context: Context): String =
        runCatching { file(context).takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()

    private fun writeStored(context: Context, tag: String) {
        runCatching {
            val f = file(context)
            if (tag.isEmpty()) f.delete() else {
                f.parentFile?.mkdirs()
                f.writeText(tag)
            }
        }.onFailure { Log.w(TAG, "Could not store the language choice: ${it.message}") }
    }

    private const val TAG = "AppLanguage"
    private const val FILE_NAME = "app_language"
}
