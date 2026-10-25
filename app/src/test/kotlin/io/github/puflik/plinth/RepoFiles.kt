package io.github.puflik.plinth

import java.io.File

/**
 * Файлы репозитория вне `src/main` для стражей выпуска Ю3г (`docs/work/yu3g3.md`): `NOTICE`, fastlane,
 * `docs/`, `gradle/`. Корень — ближайший каталог вверх от рабочего, где лежит `settings.gradle.kts`: Gradle
 * запускает тесты в каталоге модуля, IDE — в корне проекта (так же ищет [SourceTree]).
 */
object RepoFiles {
    private val root: File = findRoot()

    /**
     * Текст файла по пути от корня репозитория, переводы строк приведены к `\n` (на Windows `git` мог
     * поставить `\r\n`). Нет файла — `null`: тест сам скажет, чего не хватает, а не упадёт на чтении.
     */
    fun text(path: String): String? =
        File(root, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.replace("\r\n", "\n")

    private fun findRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Не найден корень репозитория (settings.gradle.kts) выше ${File("").absoluteFile}")
    }
}
