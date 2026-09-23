package io.github.puflik.plinth.audio.engine

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test
import java.io.File

/**
 * Страж границы аудиоядра (B1.1 📌).
 *
 * Правило, ради которого существует вся абстракция: ни один тип из
 * `androidx.media3` и вообще из Android не пересекает каталог `audio/engine`.
 * `Media3Engine` знает об интерфейсе — интерфейс о Media3 не знает никогда.
 * Нарушение этого правила убивает и десктопный клиент, и будущий движок R3:
 * подставить другую реализацию станет нельзя.
 */
class EngineBoundaryTest {
    @Test
    fun `engine abstraction has sources to check`() {
        val files = SourceTree.kotlinFiles(ENGINE_PACKAGE)

        assertThat(files.map(File::getName)).contains("AudioEngine.kt")
    }

    @Test
    fun `engine abstraction does not import android`() {
        val offenders = SourceTree.importsStartingWith(SourceTree.kotlinFiles(ENGINE_PACKAGE), ANDROID_PACKAGES)

        assertThat(offenders).isEmpty()
    }

    private companion object {
        const val ENGINE_PACKAGE = "audio/engine"
        val ANDROID_PACKAGES = listOf("android.", "androidx.", "com.google.android")
    }
}
