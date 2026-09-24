package io.github.puflik.plinth.startup

import io.github.puflik.plinth.settings.StartScreen
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Куда открывается приложение (F3). Плеер — поверх библиотеки: «назад» ведёт в неё. */
enum class StartDestination { LIBRARY, PLAYER }

/**
 * Почему стартовый экран такой, какой он есть (F3, планы 12.2 и 12.6):
 * «Показываю продолжение: ты слушал 20 минут назад».
 */
sealed interface DecisionExplanation {
    /** Решили правила «Авто», а не зафиксированный выбор в настройках. */
    val automatic: Boolean

    /** «Авто»: в библиотеке пусто — пустое состояние с проводником. */
    data object EmptyLibrary : DecisionExplanation {
        override val automatic = true
    }

    /** «Авто»: играло [ago] назад — меньше [StartRules.RECENT], продолжаем. */
    data class RecentlyPlayed(
        val ago: Duration,
    ) : DecisionExplanation {
        override val automatic = true
    }

    /** «Авто»: перерыв [ago] — библиотека. */
    data class LongBreak(
        val ago: Duration,
    ) : DecisionExplanation {
        override val automatic = true
    }

    /** «Авто»: ничего ещё не играло — библиотека. */
    data object NeverPlayed : DecisionExplanation {
        override val automatic = true
    }

    /** Экран зафиксирован в настройках. */
    data class Fixed(
        val screen: StartScreen,
    ) : DecisionExplanation {
        override val automatic = false
    }

    /** Зафиксировано «Продолжить», но ничего ещё не играло — библиотека. */
    data object NothingToContinue : DecisionExplanation {
        override val automatic = false
    }
}

data class StartDecision(
    val destination: StartDestination,
    val explanation: DecisionExplanation,
)

/**
 * Правила стартового экрана (F3, план 12.6). «Авто» — первое подходящее
 * сверху вниз: библиотека пуста → пустое состояние; играло меньше
 * [RECENT] назад → плеер; иначе библиотека. Правила с «Открытиями» и
 * «недавним» придут вместе с ними.
 */
object StartRules {
    val RECENT: Duration = 30.minutes

    fun decide(
        setting: StartScreen,
        libraryEmpty: Boolean,
        lastPlayed: Instant?,
        now: Instant,
    ): StartDecision {
        // Время из будущего (часы перевели назад) — «только что».
        val ago = lastPlayed?.let { (now - it).coerceAtLeast(Duration.ZERO) }
        return when (setting) {
            StartScreen.LIBRARY -> StartDecision(StartDestination.LIBRARY, DecisionExplanation.Fixed(setting))
            StartScreen.CONTINUE ->
                if (ago != null) {
                    StartDecision(StartDestination.PLAYER, DecisionExplanation.Fixed(setting))
                } else {
                    StartDecision(StartDestination.LIBRARY, DecisionExplanation.NothingToContinue)
                }
            StartScreen.AUTO ->
                when {
                    libraryEmpty -> StartDecision(StartDestination.LIBRARY, DecisionExplanation.EmptyLibrary)
                    ago == null -> StartDecision(StartDestination.LIBRARY, DecisionExplanation.NeverPlayed)
                    ago < RECENT -> StartDecision(StartDestination.PLAYER, DecisionExplanation.RecentlyPlayed(ago))
                    else -> StartDecision(StartDestination.LIBRARY, DecisionExplanation.LongBreak(ago))
                }
        }
    }
}
