package io.github.puflik.plinth.startup

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.settings.StartScreen
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Куда открывается приложение и почему (F3, план 12.6). */
class StartRulesTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)

    @Test
    fun `auto - empty library comes first, even right after a play`() {
        val decision = decide(StartScreen.AUTO, libraryEmpty = true, playedAgo = 1.minutes)

        assertThat(decision).isEqualTo(StartDecision(StartDestination.LIBRARY, DecisionExplanation.EmptyLibrary))
    }

    @Test
    fun `auto - played less than half an hour ago continues in the player`() {
        val decision = decide(StartScreen.AUTO, playedAgo = 20.minutes)

        assertThat(decision)
            .isEqualTo(StartDecision(StartDestination.PLAYER, DecisionExplanation.RecentlyPlayed(20.minutes)))
    }

    @Test
    fun `auto - half an hour and longer opens the library`() {
        val decision = decide(StartScreen.AUTO, playedAgo = 30.minutes)

        assertThat(decision)
            .isEqualTo(StartDecision(StartDestination.LIBRARY, DecisionExplanation.LongBreak(30.minutes)))
    }

    @Test
    fun `auto - nothing played yet opens the library`() {
        val decision = decide(StartScreen.AUTO, playedAgo = null)

        assertThat(decision).isEqualTo(StartDecision(StartDestination.LIBRARY, DecisionExplanation.NeverPlayed))
    }

    @Test
    fun `fixed library is the library whatever happened`() {
        val decision = decide(StartScreen.LIBRARY, playedAgo = 1.minutes)

        assertThat(decision)
            .isEqualTo(StartDecision(StartDestination.LIBRARY, DecisionExplanation.Fixed(StartScreen.LIBRARY)))
    }

    @Test
    fun `fixed continue opens the player after any break, or the library when nothing played`() {
        assertThat(decide(StartScreen.CONTINUE, playedAgo = 3.days))
            .isEqualTo(StartDecision(StartDestination.PLAYER, DecisionExplanation.Fixed(StartScreen.CONTINUE)))
        assertThat(decide(StartScreen.CONTINUE, playedAgo = null))
            .isEqualTo(StartDecision(StartDestination.LIBRARY, DecisionExplanation.NothingToContinue))
    }

    @Test
    fun `only auto decisions are automatic`() {
        assertThat(DecisionExplanation.RecentlyPlayed(1.minutes).automatic).isTrue()
        assertThat(DecisionExplanation.EmptyLibrary.automatic).isTrue()
        assertThat(DecisionExplanation.Fixed(StartScreen.LIBRARY).automatic).isFalse()
        assertThat(DecisionExplanation.NothingToContinue.automatic).isFalse()
    }

    @Test
    fun `play time from the future counts as just now`() {
        val decision = decide(StartScreen.AUTO, playedAgo = (-5).minutes)

        assertThat(decision)
            .isEqualTo(StartDecision(StartDestination.PLAYER, DecisionExplanation.RecentlyPlayed(Duration.ZERO)))
    }

    private fun decide(
        setting: StartScreen,
        libraryEmpty: Boolean = false,
        playedAgo: Duration?,
    ) = StartRules.decide(setting, libraryEmpty, playedAgo?.let { now - it }, now)
}
