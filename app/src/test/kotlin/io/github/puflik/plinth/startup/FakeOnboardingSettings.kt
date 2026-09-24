package io.github.puflik.plinth.startup

import kotlinx.coroutines.flow.MutableStateFlow

/** Запись о мастере первого запуска в памяти для тестов. */
class FakeOnboardingSettings(
    initial: OnboardingRecord = OnboardingRecord.FIRST_LAUNCH,
) : OnboardingSettings {
    override val record = MutableStateFlow(initial)

    override suspend fun finish(skipped: Set<OnboardingStep>) {
        record.value = OnboardingRecord(finished = true, skipped = skipped)
    }

    override suspend fun settle(step: OnboardingStep) {
        record.value = record.value.copy(skipped = record.value.skipped - step)
    }
}
