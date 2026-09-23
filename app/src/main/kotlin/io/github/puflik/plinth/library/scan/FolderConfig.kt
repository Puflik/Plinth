package io.github.puflik.plinth.library.scan

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
 * Где хранится выбор пользователя, решает шаг с экраном папок; здесь — модель.
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

        /** Без крайних `/`, в нижнем регистре, с `/` в конце; корень — пустая строка. */
        private fun normalized(folder: String): String {
            val trimmed = folder.trim().trim('/').lowercase(Locale.ROOT)
            return if (trimmed.isEmpty()) "" else "$trimmed/"
        }
    }
}
