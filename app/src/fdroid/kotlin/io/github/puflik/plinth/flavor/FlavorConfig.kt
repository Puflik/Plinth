package io.github.puflik.plinth.flavor

import io.github.puflik.plinth.core.config.ApiKeySource

/**
 * Константы сборки `fdroid` (A1.3).
 *
 * F-Droid обновляет приложения сам, а встроенный апдейтер там считается
 * плохим тоном и может стать причиной отказа в публикации. Ключи API
 * в воспроизводимую сборку зашить нельзя — их вводит пользователь.
 */
object FlavorConfig {
    const val NAME: String = "fdroid"

    const val UPDATE_CHECK_ENABLED: Boolean = false

    val API_KEY_SOURCE: ApiKeySource = ApiKeySource.USER_PROVIDED
}
