package io.github.puflik.plinth.flavor

import io.github.puflik.plinth.core.config.ApiKeySource

/**
 * Константы сборки `github` (A1.3).
 *
 * Канал распространения — GitHub Releases. Автообновления там нет,
 * поэтому проверку новых версий делает само приложение.
 */
object FlavorConfig {
    const val NAME: String = "github"

    const val UPDATE_CHECK_ENABLED: Boolean = true

    val API_KEY_SOURCE: ApiKeySource = ApiKeySource.BUILD_CONFIG
}
