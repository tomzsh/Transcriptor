package id.tomz.transcriptor

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/** Empty locale tags follow the device. UI language never changes a project's speech language. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""), INDONESIAN("id"), ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage = when (tag?.substringBefore('-')) {
            "id", "in" -> INDONESIAN
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}

object AppLanguageSettings {
    private const val PREFERENCES = "app_language"
    private const val LANGUAGE = "language"
    private const val FRAMEWORK_MIGRATED = "framework_migrated"

    fun read(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        AppLanguage.fromTag(if (locales.isEmpty) "" else locales[0].toLanguageTag())
    } else {
        AppLanguage.fromTag(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE, ""))
    }

    /** Called by Application and Activity attachment on Android 8–12. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val language = read(base)
        val locales = if (language == AppLanguage.SYSTEM) Resources.getSystem().configuration.locales
            else LocaleList.forLanguageTags(language.tag)
        val configuration = Configuration(base.resources.configuration).apply { setLocales(locales) }
        return base.createConfigurationContext(configuration)
    }

    /** Transfer a pre-Android-13 choice once, then let Android Settings own the framework value. */
    fun migrateToFramework(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getBoolean(FRAMEWORK_MIGRATED, false)) return
        val manager = context.getSystemService(LocaleManager::class.java)
        if (manager.applicationLocales.isEmpty && prefs.contains(LANGUAGE)) {
            manager.applicationLocales = LocaleList.forLanguageTags(AppLanguage.fromTag(prefs.getString(LANGUAGE, "")).tag)
        }
        prefs.edit().putBoolean(FRAMEWORK_MIGRATED, true).apply()
    }

    fun select(context: Context, language: AppLanguage) {
        if (read(context) == language) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(LANGUAGE, language.tag).putBoolean(FRAMEWORK_MIGRATED, Build.VERSION.SDK_INT >= 33).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            context.activity()?.recreate()
        }
    }

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
        else -> null
    }
}
