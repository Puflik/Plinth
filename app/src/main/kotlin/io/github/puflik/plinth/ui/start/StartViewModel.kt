package io.github.puflik.plinth.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.sort.TrackSort
import io.github.puflik.plinth.settings.StartSettings
import io.github.puflik.plinth.startup.PlayHistory
import io.github.puflik.plinth.startup.StartDecision
import io.github.puflik.plinth.startup.StartLog
import io.github.puflik.plinth.startup.StartRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock

/**
 * Стартовый экран (F3, план 12.6): решает один раз на запуск Activity — по
 * настройке, пустоте библиотеки и времени последней игры — и отдаёт решение
 * экрану ровно один раз ([take]): поворот экрана не открывает плеер снова.
 * Решение записывается в [StartLog] — настройки объяснят его и позже.
 */
@HiltViewModel
class StartViewModel
    @Inject
    constructor(
        settings: StartSettings,
        repository: LibraryRepository,
        history: PlayHistory,
        clock: Clock,
        log: StartLog,
    ) : ViewModel() {
        private val mutableDecision = MutableStateFlow<StartDecision?>(null)
        private var taken = false

        /** Решение; `null` — ещё решается: читаются настройка, библиотека и история. */
        val decision: StateFlow<StartDecision?> = mutableDecision.asStateFlow()

        init {
            viewModelScope.launch {
                val decision =
                    StartRules.decide(
                        setting = settings.startScreen.first(),
                        libraryEmpty = repository.tracks(TrackSort.TITLE).first().isEmpty(),
                        lastPlayed = history.lastPlayed.first(),
                        now = clock.now(),
                    )
                log.record(decision)
                mutableDecision.value = decision
            }
        }

        /** Решение, если его ещё не применяли; дальше — `null`. */
        fun take(): StartDecision? {
            val decision = decision.value
            if (decision == null || taken) return null
            taken = true
            return decision
        }
    }
