package io.github.puflik.plinth.startup

/**
 * Мастер первого запуска, модель B+ (F1, план 12.4): шаги идут по порядку,
 * любой можно пройти, необязательный — пропустить, а весь мастер —
 * пропустить разом с любого шага. Пропущенное запоминается: к нему
 * вернутся к месту, когда оно понадобится (12.4, F2).
 *
 * @property step шаг на экране; `null` — мастер закончен.
 */
data class OnboardingWizard(
    val step: OnboardingStep? = OnboardingStep.entries.first(),
    val skipped: Set<OnboardingStep> = emptySet(),
) {
    val finished: Boolean get() = step == null

    /** Шаг пройден — к следующему; после последнего мастер закончен. */
    fun next(): OnboardingWizard = copy(step = step?.next)

    /** Пропустить шаг. Обязательный так не пропускается — только вместе со всем мастером. */
    fun skip(): OnboardingWizard {
        val current = step
        return if (current == null || !current.skippable) this else OnboardingWizard(current.next, skipped + current)
    }

    /** Пропустить этот шаг и все после него. */
    fun skipAll(): OnboardingWizard {
        val current = step ?: return this
        return OnboardingWizard(step = null, skipped = skipped + OnboardingStep.entries.drop(current.ordinal))
    }
}
