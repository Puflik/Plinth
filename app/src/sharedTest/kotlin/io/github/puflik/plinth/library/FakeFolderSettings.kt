package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Настройки папок в памяти для тестов (C2.5). */
class FakeFolderSettings(
    initial: FolderConfig = FolderConfig.DEFAULT,
) : FolderSettings {
    override val folders = MutableStateFlow(initial)

    override suspend fun update(transform: (FolderConfig) -> FolderConfig) {
        folders.update(transform)
    }
}
