package io.github.puflik.plinth

import java.io.File

/**
 * Исходники `src/main` для тестов-стражей, которые читают код, а не байт-код:
 * импорт заметнее и понятнее в диагностике.
 */
object SourceTree {
    private const val MAIN_ROOT = "src/main/kotlin/io/github/puflik/plinth"

    /** Файлы `.kt` пакета `io.github.puflik.plinth.<packagePath>` со всеми подпакетами. */
    fun kotlinFiles(packagePath: String): List<File> =
        mainDir(packagePath).walkTopDown().filter { it.extension == "kt" }.toList()

    /** Один файл `src/main` по пути от корневого пакета. */
    fun mainFile(path: String): File = File(mainDir(""), path)

    /** Строки импорта, начинающиеся с одного из префиксов, в виде «файл: импорт». */
    fun importsStartingWith(
        files: List<File>,
        prefixes: List<String>,
    ): List<String> =
        files.flatMap { file ->
            file
                .readLines()
                .map(String::trim)
                .filter { line -> line.startsWith("import ") && prefixes.any { line.startsWith("import $it") } }
                .map { line -> "${file.name}: $line" }
        }

    /**
     * Рабочий каталог unit-тестов зависит от того, кто их запускает
     * (Gradle — каталог модуля, IDE — корень проекта), поэтому каталог
     * исходников ищется подъёмом вверх.
     */
    private fun mainDir(packagePath: String): File {
        val path = if (packagePath.isEmpty()) MAIN_ROOT else "$MAIN_ROOT/$packagePath"
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, path), File(directory, "app/$path"))) {
                if (candidate.isDirectory) return candidate
            }
            directory = directory.parentFile
        }
        error("Не найден каталог $path — проверь структуру исходников")
    }
}
