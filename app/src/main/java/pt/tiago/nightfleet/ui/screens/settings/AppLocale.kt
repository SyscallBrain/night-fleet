package pt.tiago.nightfleet.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.app.LocaleManager
import pt.tiago.nightfleet.data.LanguageChoice
import java.util.Locale

/**
 * The language of the app.
 *
 * - Android 13 and later (API 33): the choice goes to `LocaleManager.applicationLocales`. The system
 *   recreates the Activity by itself, and `locales_config.xml` lists the two languages.
 * - Below API 33: a context wrapper applies the choice. [wrap] must be called from the Activity's
 *   `attachBaseContext`, and the Settings screen recreates the Activity after a change ([needsRecreate]).
 *
 * The choice is also written to a small private preference, because `attachBaseContext` runs before
 * DataStore can be read. It holds no personal data and is only a cache of the setting in SettingsRepository.
 */
object AppLocale {
    private const val PREFERENCES = "nightfleet-locale"
    private const val KEY_LANGUAGE = "language"

    /** True when the platform keeps the per-app locale (API 33 and later). */
    fun usesPlatformApi(sdkInt: Int): Boolean = sdkInt >= 33

    /** Below API 33 the Activity must be recreated for the new language to apply. */
    fun needsRecreate(sdkInt: Int): Boolean = !usesPlatformApi(sdkInt)

    /** Applies [choice] for the whole app and remembers it for the next start. */
    fun apply(context: Context, choice: LanguageChoice) {
        remember(context, choice)
        // Inline check: lint only recognises the API level when it is written at the call site.
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = localeList(choice)
        }
    }

    /**
     * Context for the Activity's `attachBaseContext`. On API 33 and later the platform already applies the
     * locale, so the base context is returned unchanged.
     */
    // Lint AppBundleLocaleChanges asks for Play Core language downloads. Not used: the app ships one APK (F-Droid,
    // no dynamic feature modules) and the language is chosen inside the app, so the per-app locale is enough.
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        if (usesPlatformApi(Build.VERSION.SDK_INT)) return base
        val tag = remembered(base).languageTag() ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(configuration)
    }

    /** The locale list for the platform: empty means "follow the system". */
    fun localeList(choice: LanguageChoice): LocaleList =
        choice.languageTag()?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()

    /** The last choice written by [apply]; follows the system when nothing was stored. */
    fun remembered(context: Context): LanguageChoice {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        return parse(stored)
    }

    private fun remember(context: Context, choice: LanguageChoice) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE, choice.name)
        }
    }

    /** Unknown or missing names fall back to following the system. */
    fun parse(name: String?): LanguageChoice = LanguageChoice.entries.firstOrNull { it.name == name } ?: LanguageChoice.SYSTEM
}
