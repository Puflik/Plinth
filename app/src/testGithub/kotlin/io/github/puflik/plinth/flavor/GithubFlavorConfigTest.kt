package io.github.puflik.plinth.flavor

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.config.ApiKeySource
import org.junit.Test

/**
 * Сборка `github` распространяется через GitHub Releases: обновления
 * проверяет само приложение, ключи API зашиты в сборку.
 */
class GithubFlavorConfigTest {
    @Test
    fun `update check is enabled`() {
        assertThat(FlavorConfig.UPDATE_CHECK_ENABLED).isTrue()
    }

    @Test
    fun `api keys come from the build`() {
        assertThat(FlavorConfig.API_KEY_SOURCE).isEqualTo(ApiKeySource.BUILD_CONFIG)
    }

    @Test
    fun `flavor is named github`() {
        assertThat(FlavorConfig.NAME).isEqualTo("github")
    }
}
