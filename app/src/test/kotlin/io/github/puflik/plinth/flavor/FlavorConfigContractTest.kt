package io.github.puflik.plinth.flavor

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.BuildConfig
import org.junit.Test

/**
 * Контракт, общий для обоих flavor (A1.3).
 *
 * Тест компилируется в обоих вариантах сборки и доказывает, что
 * flavor-специфичные исходники действительно подставляются: `FlavorConfig`
 * существует только в `src/github` и `src/fdroid`, в `src/main` его нет.
 */
class FlavorConfigContractTest {
    @Test
    fun `name matches the build flavor`() {
        assertThat(FlavorConfig.NAME).isEqualTo(BuildConfig.FLAVOR)
    }

    @Test
    fun `name is one of the two known flavors`() {
        assertThat(FlavorConfig.NAME).isIn(listOf("github", "fdroid"))
    }

    @Test
    fun `api key source is declared`() {
        assertThat(FlavorConfig.API_KEY_SOURCE).isNotNull()
    }
}
