package io.github.puflik.plinth.library.model

import java.util.Locale

/**
 * Какие папки сканировать (C2.5): включённые — со всеми подпапками, кроме
 * исключённых. Умолчание из плана (13.1) — `Music` и `Download`.
 *
 * Папка — путь от корня хранилища, как `RELATIVE_PATH` в `MediaStore`:
 * `Music/Queen/`. Регистр и крайние `/` не важны: общее хранилище Android
 * регистр не различает, `music` и `Music` — одна папка. Папка совпадает
 * только целиком: `Music` не захватывает `MusicVideos`.
 *
 * Выбор пользователя хранит `FolderSettings`; здесь — модель и её правка.
 */
data class FolderConfig(
    val included: List<String> = listOf("Music/", "Download/"),
    val excluded: List<String> = emptyList(),
) {
    private val includedPrefixes = included.map(::normalized)
    private val excludedPrefixes = excluded.map(::normalized)

    fun includes(folder: String): Boolean {
        val path = normalized(folder)
        return includedPrefixes.any(path::startsWith) && excludedPrefixes.none(path::startsWith)
    }

    /** Сканировать [folder]; если она была исключена — больше не исключать. */
    fun include(folder: String) = FolderConfig(included.plusOnce(folder), excluded.without(folder))

    /** Не сканировать [folder] со всеми подпапками. */
    fun exclude(folder: String) = FolderConfig(included.without(folder), excluded.plusOnce(folder))

    /** Забыть о [folder]: ни включать, ни исключать. */
    fun remove(folder: String) = FolderConfig(included.without(folder), excluded.without(folder))

    private fun List<String>.plusOnce(folder: String): List<String> =
        if (any { normalized(it) == normalized(folder) }) this else this + canonical(folder)

    private fun List<String>.without(folder: String): List<String> = filterNot { normalized(it) == normalized(folder) }

    companion object {
        val DEFAULT = FolderConfig()

        /** Общее хранилище: `/storage/emulated/<пользователь>/` или `/storage/<том>/` (SD-карта). */
        private val SHARED_STORAGE = Regex("^/storage/(?:emulated/\\d+|[^/]+)/(.*/)?[^/]*$")

        /**
         * Папка файла по абсолютному пути — для Android 8–9, где `RELATIVE_PATH`
         * ещё нет и `MediaStore` знает только путь (`DATA`). `null` — файл вне
         * общего хранилища.
         */
        fun folderOf(path: String): String? = SHARED_STORAGE.find(path)?.let { it.groupValues[1] }

        /** Как папку записывают в список: без крайних `/` и пробелов, с `/` в конце; корень — пустая строка. */
        private fun canonical(folder: String): String {
            val trimmed = folder.trim().trim('/')
            return if (trimmed.isEmpty()) "" else "$trimmed/"
        }

        /** Без крайних `/`, в нижнем регистре, с `/` в конце; корень — пустая строка. */
        private fun normalized(folder: String): String {
            val trimmed = folder.trim().trim('/').lowercase(Locale.ROOT)
            return if (trimmed.isEmpty()) "" else "$trimmed/"
        }
    }
}
