package io.github.puflik.plinth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.settings.StartScreen
import io.github.puflik.plinth.settings.StartSettings
import io.github.puflik.plinth.startup.StartDecision
import io.github.puflik.plinth.startup.StartLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Выбор стартового экрана и почему этот запуск открылся там, где открылся.
 *
 * @property lastDecision решение этого запуска; `null` — ещё не принято.
 */
data class StartScreenSettingUiState(
    val choice: StartScreen,
    val lastDecision: StartDecision?,
)

/**
 * Настройка «Стартовый экран» (F3, план 12.6) — в обоих режимах; рядом с
 * ней объяснение решения этого запуска (12.2): «Почему открылось это».
 * Новый выбор действует со следующего запуска.
 */
@HiltViewModel
class StartScreenSettingViewModel
    @Inject
    constructor(
        private val settings: StartSettings,
        log: StartLog,
    ) : ViewModel() {
        val uiState: StateFlow<StartScreenSettingUiState> =
            combine(settings.startScreen, log.last, ::StartScreenSettingUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), INITIAL)

        fun onChoose(screen: StartScreen) {
            viewModelScope.launch { settings.setStartScreen(screen) }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
            val INITIAL = StartScreenSettingUiState(StartScreen.AUTO, null)
        }
    }
