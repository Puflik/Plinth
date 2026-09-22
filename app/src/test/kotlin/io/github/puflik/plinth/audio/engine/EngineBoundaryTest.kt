package io.github.puflik.plinth.audio.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
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
 *
 * Тест читает исходники, а не байт-код: импорт заметнее и понятнее в диагностике.
 */
class EngineBoundaryTest {
    @Test
    fun `engine abstraction has sources to check`() {
        val files = engineSources()

        assertThat(files.map(File::getName)).contains("AudioEngine.kt")
    }

    @Test
    fun `engine abstraction does not import android`() {
        val offenders =
            engineSources().flatMap { file ->
                file
                    .readLines()
                    .map(String::trim)
                    .filter { line -> FORBIDDEN_PREFIXES.any(line::startsWith) }
                    .map { line -> "${file.name}: $line" }
            }

        assertThat(offenders).isEmpty()
    }

    private fun engineSources(): List<File> = engineSourceDir().walkTopDown().filter { it.extension == "kt" }.toList()

    /**
     * Рабочий каталог unit-тестов зависит от того, кто их запускает
     * (Gradle — каталог модуля, IDE — корень проекта), поэтому каталог
     * исходников ищется подъёмом вверх.
     */
    private fun engineSourceDir(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, ENGINE_PATH), File(directory, "app/$ENGINE_PATH"))) {
                if (candidate.isDirectory) return candidate
            }
            directory = directory.parentFile
        }
        fail("Не найден каталог $ENGINE_PATH — проверь структуру исходников")
        error("недостижимо")
    }

    private companion object {
        const val ENGINE_PATH = "src/main/kotlin/io/github/puflik/plinth/audio/engine"
        val FORBIDDEN_PREFIXES = listOf("import android.", "import androidx.", "import com.google.android")
    }
}
