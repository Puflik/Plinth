package io.github.puflik.plinth.startup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Что решил стартовый экран в этот запуск (F3): решение объясняется не
 * только в момент показа, но и потом — в настройках, рядом с выбором (12.2).
 * Живёт, пока жив процесс.
 */
class StartLog {
    private val mutableLast = MutableStateFlow<StartDecision?>(null)

    val last: StateFlow<StartDecision?> = mutableLast.asStateFlow()

    fun record(decision: StartDecision) {
        mutableLast.value = decision
    }
}
