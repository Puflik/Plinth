package io.github.puflik.plinth.backup

import kotlinx.coroutines.flow.Flow

/** Какую папку человек выбрал для копии журнала (C4). Хранение — забота реализации. */
interface MirrorSettings {
    /** Адрес дерева SAF; папку не выбирали — `null`. */
    val folder: Flow<String?>

    suspend fun setFolder(tree: String)
}
