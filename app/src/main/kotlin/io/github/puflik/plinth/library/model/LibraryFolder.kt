package io.github.puflik.plinth.library.model

import io.github.puflik.plinth.library.sort.CodePointOrder
import io.github.puflik.plinth.library.sort.SortKeys

/**
 * Папка фонотеки (C4.1) — файловая структура как есть, собранная из
 * [папок треков][LibraryTrack.folder]. Своей записи у папки нет, как и у
 * [Album]: папки без треков в дереве не бывает.
 *
 * @property path путь от корня хранилища с `/` в конце: `Music/Queen/`;
 *   у корня — пустая строка.
 * @property name последняя часть пути; у корня — пустая строка.
 * @property folders подпапки в естественном порядке имён, без артиклей.
 * @property tracks треки этой папки в том порядке, в каком пришли.
 */
data class LibraryFolder(
    val path: String,
    val name: String,
    val folders: List<LibraryFolder>,
    val tracks: List<LibraryTrack>,
) {
    /** Треков здесь и во всех подпапках. */
    val trackCount: Int = tracks.size + folders.sumOf(LibraryFolder::trackCount)

    /** Путь папки уровнем выше; у корня — `null`. */
    val parentPath: String?
        get() = if (path.isEmpty()) null else path.dropLast(1).substringBeforeLast('/', "").let(::asPath)

    /**
     * Папка по пути. Если её уже нет (файлы удалили, скан прошёл) — ближайшая
     * уцелевшая папка на этом пути, в крайнем случае сам корень.
     */
    fun open(path: String): LibraryFolder {
        var folder = this
        for (name in segments(path)) {
            folder = folder.folders.find { it.name == name } ?: break
        }
        return folder
    }

    companion object {
        /** Дерево от корня хранилища; треки внутри папок сохраняют порядок [tracks]. */
        fun tree(
            tracks: List<LibraryTrack>,
            keys: SortKeys = SortKeys(),
        ): LibraryFolder = build(path = "", tracks.map { segments(it.folder) to it }, keys)

        private fun build(
            path: String,
            tracks: List<Pair<List<String>, LibraryTrack>>,
            keys: SortKeys,
        ): LibraryFolder {
            val (here, deeper) = tracks.partition { (segments, _) -> segments.isEmpty() }
            val folders =
                deeper
                    .groupBy({ (segments, _) -> segments.first() }, { (segments, track) -> segments.drop(1) to track })
                    .map { (name, inside) -> build(path + asPath(name), inside, keys) }
                    .sortedWith(
                        compareBy(CodePointOrder) { folder: LibraryFolder -> keys.of(folder.name) }
                            .thenBy(CodePointOrder, LibraryFolder::name),
                    )
            return LibraryFolder(path, path.dropLast(1).substringAfterLast('/'), folders, here.map { it.second })
        }

        private fun segments(path: String): List<String> = path.split('/').filter(String::isNotEmpty)

        private fun asPath(name: String): String = if (name.isEmpty()) "" else "$name/"
    }
}
