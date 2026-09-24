package io.github.puflik.plinth.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.startup.OnboardingSettings
import io.github.puflik.plinth.startup.OnboardingStep
import io.github.puflik.plinth.startup.OnboardingWizard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Показывать ли мастер первого запуска и какой шаг. */
sealed interface OnboardingUiState {
    /** Запись о мастере ещё читается: неизвестно, первый ли это запуск. */
    data object Loading : OnboardingUiState

    data class Step(
        val step: OnboardingStep,
    ) : OnboardingUiState

    /** Мастер пройден — сейчас или в прошлые запуски; дальше — само приложение. */
    data object Finished : OnboardingUiState
}

/**
 * Мастер первого запуска (F1): стоит перед приложением, пока его не пройдут
 * или не пропустят. Закончить его — записать, что пропущено; после этого
 * он не показывается.
 *
 * Шаг разрешения проходит сам, когда разрешение выдано: ответ на запрос или
 * возврат из настроек. Прерванный мастер начинается заново — и при уже
 * выданном разрешении сразу проскакивает на папки.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: OnboardingSettings,
    ) : ViewModel() {
        private val wizard = MutableStateFlow(OnboardingWizard())

        val uiState: StateFlow<OnboardingUiState> =
            combine(settings.record, wizard) { record, wizard ->
                val step = wizard.step
                if (record.finished || step == null) OnboardingUiState.Finished else OnboardingUiState.Step(step)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OnboardingUiState.Loading)

        fun onPermission(state: PermissionState) {
            if (state == PermissionState.Granted && wizard.value.step == OnboardingStep.PERMISSION) move { it.next() }
        }

        fun onNext() = move { it.next() }

        fun onSkip() = move { it.skip() }

        fun onSkipAll() = move { it.skipAll() }

        private fun move(transform: (OnboardingWizard) -> OnboardingWizard) {
            val before = wizard.value
            if (before.finished) return
            val after = transform(before)
            wizard.value = after
            if (after.finished) viewModelScope.launch { settings.finish(after.skipped) }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
