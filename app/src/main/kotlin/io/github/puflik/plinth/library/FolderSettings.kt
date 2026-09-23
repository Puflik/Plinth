package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.flow.Flow

/**
 * Какие папки сканировать — выбор пользователя (C2.5), глазами экранов.
 *
 * Лежит рядом с [LibraryRepository] и [LibraryScan]: экранам сканер не виден,
 * а в v0.2 папки будет знать ядро на Rust. Хранение — забота реализации.
 */
interface FolderSettings {
    /** Текущий выбор; пока пользователь ничего не менял — [FolderConfig.DEFAULT]. */
    val folders: Flow<FolderConfig>

    /** Меняет выбор одним шагом: [transform] получает текущий и возвращает новый. */
    suspend fun update(transform: (FolderConfig) -> FolderConfig)
}
