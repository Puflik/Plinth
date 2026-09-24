package io.github.puflik.plinth.settings

import java.util.Locale

/**
 * Язык приложения (план 12.12): системный или выбранный для Plinth в
 * настройках Android 13+ («Язык приложения»). Хранит его система, а не мы.
 */
sealed interface AppLanguage {
    data object System : AppLanguage

    data class Chosen(
        val locale: Locale,
    ) : AppLanguage {
        /** Название языка на нём самом, с заглавной: «Русский», «English». */
        val nativeName: String
            get() = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    companion object {
        /**
         * Из списка языков приложения тегами BCP 47 через запятую
         * (`LocaleList.toLanguageTags`); действует первый, пустой список —
         * язык системы.
         */
        fun of(tags: String): AppLanguage =
            tags
                .split(',')
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                ?.let { Chosen(Locale.forLanguageTag(it)) }
                ?: System
    }
}
