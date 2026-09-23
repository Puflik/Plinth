package io.github.puflik.plinth.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.permission.PermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Что видит экран библиотеки: разрешение и скан. */
data class LibraryUiState(
    val permission: PermissionState,
    val scan: ScanProgress,
)

/**
 * Библиотека до списков (шаг 4 вертикали): разрешение на чтение музыки и
 * фоновый скан.
 *
 * Скан запускается, когда разрешение становится выданным: при старте
 * приложения, если оно уже есть, и сразу после выдачи. Узнаёт о разрешении
 * экран — только у него есть Activity для запроса и объяснения.
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val scan: LibraryScan,
    ) : ViewModel() {
        private val permission = MutableStateFlow(PermissionState.NotRequested)

        val uiState: StateFlow<LibraryUiState> =
            combine(permission, scan.progress, ::LibraryUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), INITIAL)

        fun onPermission(state: PermissionState) {
            val wasGranted = permission.value == PermissionState.Granted
            permission.value = state
            if (state == PermissionState.Granted && !wasGranted) scan.start()
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
            val INITIAL = LibraryUiState(PermissionState.NotRequested, ScanProgress.Idle)
        }
    }
