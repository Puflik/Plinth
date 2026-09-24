package io.github.puflik.plinth.artwork

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test

/**
 * Страж ядра обложек (E2): кэш и загрузчик — чистый Kotlin, как абстракция
 * движка. Android знает только источник встроенных картинок
 * (`artwork/embedded`) — его заменит десктопный клиент или ядро.
 */
class ArtworkBoundaryTest {
    @Test
    fun `artwork core does not import android`() {
        val embedded = SourceTree.kotlinFiles("artwork/embedded").toSet()
        val core = SourceTree.kotlinFiles("artwork").filterNot { it in embedded }

        assertThat(core).isNotEmpty()
        assertThat(SourceTree.importsStartingWith(core, ANDROID_PACKAGES)).isEmpty()
    }

    private companion object {
        val ANDROID_PACKAGES = listOf("android.", "androidx.", "com.google.android")
    }
}
