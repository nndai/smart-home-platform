package com.nndai.myhome.core.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANG = "language_code"

    /** Mặc định tiếng Việt cho tới khi user đổi trong Profile. */
    private const val DEFAULT_LANG = "vi"

    fun getLanguageCode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
    }

    fun setLanguageCode(context: Context, langCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, langCode)
            .apply()
    }

    fun wrap(context: Context): ContextWrapper {
        val langCode = getLanguageCode(context)
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return object : ContextWrapper(context) {
            override fun getResources() = createConfigurationContext(config).resources
        }
    }
}
