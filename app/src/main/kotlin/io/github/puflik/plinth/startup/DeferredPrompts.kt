package io.github.puflik.plinth.startup

/**
 * Ситуации, в которых пропущенный шаг мастера становится нужен (план 12.4).
 * Будущие — кэш у предела, рекомендациям нечего предложить, поиск не нашёл
 * трек локально, вторая попытка в одной настройке.
 */
enum class PromptTrigger {
    /** Скан закончен, а треков нет. */
    EMPTY_LIBRARY,
}

/**
 * Пропущенные шаги мастера возвращаются к месту (F2, план 12.4): не
 * напоминанием при запуске, а там, где без них плохо. Таблица — какой шаг
 * в какой ситуации. Разрешение сюда не входит: без него библиотека
 * спрашивает сама.
 */
object DeferredPrompts {
    private val triggers: Map<OnboardingStep, PromptTrigger> =
        mapOf(OnboardingStep.FOLDERS to PromptTrigger.EMPTY_LIBRARY)

    /** Пропущенный шаг, который стоит предложить в ситуации [trigger]; `null` — нечего. */
    fun due(
        skipped: Set<OnboardingStep>,
        trigger: PromptTrigger,
    ): OnboardingStep? = OnboardingStep.entries.firstOrNull { it in skipped && triggers[it] == trigger }
}
