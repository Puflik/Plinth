package io.github.puflik.plinth.startup

import kotlinx.coroutines.flow.Flow

/**
 * Что осталось от мастера первого запуска (F1).
 *
 * @property finished мастер пройден или пропущен — больше он не показывается.
 * @property skipped шаги, пропущенные в мастере: их предложат позже, к месту (F2).
 */
data class OnboardingRecord(
    val finished: Boolean = false,
    val skipped: Set<OnboardingStep> = emptySet(),
) {
    companion object {
        /** Мастер ещё не открывали. */
        val FIRST_LAUNCH = OnboardingRecord()
    }
}

/** Где мастер первого запуска помнит, что он пройден (F1). Хранение — забота реализации. */
interface OnboardingSettings {
    val record: Flow<OnboardingRecord>

    /** Мастер закончен; [skipped] — что в нём пропустили. */
    suspend fun finish(skipped: Set<OnboardingStep>)
}
