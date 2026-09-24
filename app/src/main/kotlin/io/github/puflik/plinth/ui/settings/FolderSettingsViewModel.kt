package io.github.puflik.plinth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.library.FolderSettings
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Что видит экран папок: выбор и последний скан. */
data class FolderSettingsUiState(
    val folders: FolderConfig,
    val scan: ScanProgress,
) {
    /** Выбор совпадает с умолчанием — сбрасывать нечего. */
    val isDefault: Boolean get() = folders == FolderConfig.DEFAULT
}

/**
 * Экран папок (C2.5): какие папки сканировать, какие пропускать. Выбор
 * сохраняется сразу, а в библиотеке меняется после «Пересканировать» —
 * скан сам не запускается, пока пользователь правит список.
 */
@HiltViewModel
class FolderSettingsViewModel
    @Inject
    constructor(
        private val settings: FolderSettings,
        private val scan: LibraryScan,
    ) : ViewModel() {
        val uiState: StateFlow<FolderSettingsUiState> =
            combine(settings.folders, scan.progress, ::FolderSettingsUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), INITIAL)

        fun onInclude(folder: String) = change { it.include(folder) }

        fun onExclude(folder: String) = change { it.exclude(folder) }

        fun onRemove(folder: String) = change { it.remove(folder) }

        /**
         * Назад к `Music` и `Download`. Другого пути вернуть `Download` нет:
         * на Android 11+ `OpenDocumentTree` не даёт выбрать её саму.
         */
        fun onReset() = change { FolderConfig.DEFAULT }

        fun onRescan() = scan.start()

        private fun change(transform: (FolderConfig) -> FolderConfig) {
            viewModelScope.launch { settings.update(transform) }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
            val INITIAL = FolderSettingsUiState(FolderConfig.DEFAULT, ScanProgress.Idle)
        }
    }
