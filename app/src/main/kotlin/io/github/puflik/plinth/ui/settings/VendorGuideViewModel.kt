package io.github.puflik.plinth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.diagnostics.vendor.KillReport
import io.github.puflik.plinth.diagnostics.vendor.Vendor
import io.github.puflik.plinth.diagnostics.vendor.VendorPromptState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * «Почему музыка останавливается» после убийства (G2.4): показывается, если
 * этот запуск обнаружил убитую игру, и только один раз — закрыли, больше не
 * появится сам; в настройках он доступен всегда.
 */
@HiltViewModel
class VendorGuideViewModel
    @Inject
    constructor(
        report: KillReport,
        private val prompt: VendorPromptState,
        val vendor: Vendor,
    ) : ViewModel() {
        private val closed = MutableStateFlow(false)

        val showGuide: StateFlow<Boolean> =
            combine(prompt.shown, closed) { shown, closed -> report.detected && !shown && !closed }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        fun onClose() {
            closed.value = true
            viewModelScope.launch { prompt.markShown() }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
