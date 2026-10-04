package tf.monochrome.desktop.res

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app's translated strings on the desktop.
 *
 * Android resolved `R.string.x` through the resource system, picking the
 * `values-<lang>` folder from the configuration. Here the same translation
 * table (`app/l10n/strings.tsv`) is generated into one JSON file per language
 * under `resources/i18n/`, and this object does the lookup. Formatting goes
 * through [String.format] exactly as `Context.getString(id, args)` and
 * `stringResource(id, args)` do on Android, so `%1$s`, `%2$d` and `%1$.1f`
 * behave identically — Compose Multiplatform's own string resources are not
 * used because they format differently and only from a composable.
 *
 * [language] is the app's current language, which the Settings screen can
 * override (see `AppLanguage`); composables observe it so a change re-renders.
 */
object Strings {
    @Serializable
    internal class Table(
        val lang: String,
        val strings: Map<String, String> = emptyMap(),
        val plurals: Map<String, Map<String, String>> = emptyMap(),
    )

    /** Languages the table ships, in the order Settings lists them. */
    val SUPPORTED: List<String> = listOf("en", "zh", "ja", "fr", "es", "tr", "de")
    const val DEFAULT = "en"

    private val _language = MutableStateFlow(systemLanguage())
    val language: StateFlow<String> get() = _language

    fun setLanguage(tag: String?) {
        _language.value = tag?.takeIf { it in SUPPORTED } ?: systemLanguage()
    }

    /** The JVM locale matching [language], for number and date formatting. */
    val locale: Locale get() = localeFor(_language.value)

    fun localeFor(lang: String): Locale = when (lang) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "ja" -> Locale.JAPANESE
        "fr" -> Locale.FRENCH
        "es" -> Locale.forLanguageTag("es")
        "tr" -> Locale.forLanguageTag("tr")
        "de" -> Locale.GERMAN
        else -> Locale.ENGLISH
    }

    private fun systemLanguage(): String {
        val sys = Locale.getDefault().language
        return if (sys in SUPPORTED) sys else DEFAULT
    }

    private val tables = ConcurrentHashMap<String, Table>()
    private val json = Json { ignoreUnknownKeys = true }

    internal fun table(lang: String): Table = tables.getOrPut(lang) {
        val stream = Strings::class.java.getResourceAsStream("/i18n/strings_$lang.json")
            ?: error("missing translation table for '$lang' (run app/l10n/generate_strings.py)")
        stream.use { json.decodeFromString(Table.serializer(), it.readBytes().decodeToString()) }
    }

    /** Raw template for [key] in [lang], falling back to English then the key name. */
    fun raw(key: String, lang: String = _language.value): String =
        table(lang).strings[key]
            ?: (if (lang != DEFAULT) table(DEFAULT).strings[key] else null)
            ?: key

    fun get(key: StringKey, vararg args: Any?): String = get(key.name, _language.value, *args)

    fun get(key: String, lang: String, vararg args: Any?): String {
        val template = raw(key, lang)
        return if (args.isEmpty()) template else format(template, localeFor(lang), args)
    }

    fun plural(key: PluralKey, quantity: Int, vararg args: Any?): String =
        plural(key.name, quantity, _language.value, *args)

    fun plural(key: String, quantity: Int, lang: String, vararg args: Any?): String {
        val forms = table(lang).plurals[key]
            ?: (if (lang != DEFAULT) table(DEFAULT).plurals[key] else null)
            ?: return key
        val form = PluralRules.select(lang, quantity)
        val template = forms[form] ?: forms["other"] ?: forms.values.first()
        return if (args.isEmpty()) template else format(template, localeFor(lang), args)
    }

    /** Every language's text for [key]: what the settings search index needs. */
    fun allTranslations(key: String): Map<String, String> =
        SUPPORTED.associateWith { lang -> table(lang).strings[key] ?: "" }

    private fun format(template: String, locale: Locale, args: Array<out Any?>): String =
        try {
            String.format(locale, template, *args)
        } catch (e: java.util.IllegalFormatException) {
            template
        }
}

/** CLDR plural categories for the languages the app ships. */
internal object PluralRules {
    fun select(lang: String, n: Int): String = when (lang) {
        "zh", "ja" -> "other"
        "fr", "es" -> when {
            n == 0 || n == 1 -> "one"
            n != 0 && n % 1_000_000 == 0 -> "many"
            else -> "other"
        }
        else -> if (n == 1) "one" else "other"   // en, de, tr
    }
}

/** A string resource id: the Android `R.string.x` of the desktop build. */
@JvmInline
value class StringKey(val name: String)

/** A plurals resource id: the Android `R.plurals.x` of the desktop build. */
@JvmInline
value class PluralKey(val name: String)

/** A font resource id: a file under `resources/fonts/`. */
@JvmInline
value class FontKey(val file: String)
