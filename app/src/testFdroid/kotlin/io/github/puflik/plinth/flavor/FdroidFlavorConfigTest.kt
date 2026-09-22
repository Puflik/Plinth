package io.github.puflik.plinth.flavor

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.config.ApiKeySource
import org.junit.Test

/**
 * F-Droid обновляет приложения сам, а встроенный апдейтер там считается
 * плохим тоном и может стать причиной отказа. Ключи API вводит пользователь.
 */
class FdroidFlavorConfigTest {
    @Test
    fun `update check is disabled`() {
        assertThat(FlavorConfig.UPDATE_CHECK_ENABLED).isFalse()
    }

    @Test
    fun `api keys come from the user`() {
        assertThat(FlavorConfig.API_KEY_SOURCE).isEqualTo(ApiKeySource.USER_PROVIDED)
    }

    @Test
    fun `flavor is named fdroid`() {
        assertThat(FlavorConfig.NAME).isEqualTo("fdroid")
    }
}
