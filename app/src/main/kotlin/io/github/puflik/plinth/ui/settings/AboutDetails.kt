package io.github.puflik.plinth.ui.settings

import io.github.puflik.plinth.BuildConfig
import io.github.puflik.plinth.core.config.ProjectLinks
import io.github.puflik.plinth.diagnostics.AppInfo

/**
 * Что показывает «О приложении» (Ю3г): значения готовы к показу, Android-типов нет — их проверяют на JVM.
 * Версия и flavor те же, что в строке диагностики ([AppInfo.line]); имя и тег экстрактора не переводятся.
 *
 * @property extractorVersion тег NewPipeExtractor из каталога версий, как он есть: `v0.26.5`.
 * @property sourceUrl адрес исходного кода Plinth.
 */
data class AboutDetails(
    val version: String,
    val flavor: String,
    val extractorVersion: String,
    val sourceUrl: String,
) {
    /** Версия и flavor, как в [AppInfo.line]: `0.3.0 (github)`. */
    val versionLine: String get() = "$version ($flavor)"

    /** Имя экстрактора и его тег: `NewPipeExtractor v0.26.5`. */
    val extractorLine: String get() = "NewPipeExtractor $extractorVersion"
}

/** Сведения для «О приложении» по сведениям о сборке; тег экстрактора по умолчанию — из `BuildConfig`. */
fun aboutDetails(
    info: AppInfo,
    extractorVersion: String = BuildConfig.NEWPIPE_EXTRACTOR_VERSION,
): AboutDetails =
    AboutDetails(
        version = info.version,
        flavor = info.flavor,
        extractorVersion = extractorVersion,
        sourceUrl = ProjectLinks.SOURCE,
    )
