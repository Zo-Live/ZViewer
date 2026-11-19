package dev.zolive.zviewer.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val tag: String, val endonym: String) {
    SIMPLIFIED_CHINESE("zh-Hans", "简体中文"),
    TRADITIONAL_CHINESE("zh-Hant", "繁體中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語");

    fun localizedContext(context: Context): Context = context.createConfigurationContext(
        Configuration().apply { setLocale(Locale.forLanguageTag(tag)) }
    )

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SIMPLIFIED_CHINESE
    }
}
