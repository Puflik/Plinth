package io.github.puflik.plinth.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Папка копии в памяти (C4). */
class FakeMirrorSettings(
    folder: String? = null,
) : MirrorSettings {
    private val chosen = MutableStateFlow(folder)

    override val folder: StateFlow<String?> = chosen

    override suspend fun setFolder(tree: String) {
        chosen.value = tree
    }
}
