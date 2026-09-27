package io.github.puflik.plinth.backup

import io.github.puflik.plinth.ffi.MirrorFile

/**
 * Папка копии журнала, выбранная человеком через SAF (C4): в ней каталог
 * `.plinth/journal/`, а в нём — файл на установку. Папка задаётся адресом
 * дерева SAF (`content://…/tree/…`). Реализация — `SafMirrorFolder`.
 */
interface MirrorFolder {
    /** Берёт постоянное разрешение на папку [tree]; не вышло — `false`. */
    suspend fun adopt(tree: String): Boolean

    /** Файлы копий в папке [tree]; копий ещё нет — пусто, нет доступа — `null`. */
    suspend fun read(tree: String): List<MirrorFile>?

    /** Записывает [file] в папку [tree]: временный файл, потом имя; не вышло — `false`. */
    suspend fun write(
        tree: String,
        file: MirrorFile,
    ): Boolean
}
