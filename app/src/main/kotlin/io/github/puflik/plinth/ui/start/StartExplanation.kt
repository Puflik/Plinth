package io.github.puflik.plinth.ui.start

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.puflik.plinth.R
import io.github.puflik.plinth.startup.DecisionExplanation
import io.github.puflik.plinth.ui.settings.labelRes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** «Почему показываю это» словами (F3, 12.2): «…you listened 20 minutes ago». */
@Composable
fun explanationText(explanation: DecisionExplanation): String =
    when (explanation) {
        DecisionExplanation.EmptyLibrary -> stringResource(R.string.start_explain_empty)
        is DecisionExplanation.RecentlyPlayed ->
            if (explanation.ago < 1.minutes) {
                stringResource(R.string.start_explain_recent_now)
            } else {
                stringResource(R.string.start_explain_recent, agoText(explanation.ago))
            }
        is DecisionExplanation.LongBreak -> stringResource(R.string.start_explain_break, agoText(explanation.ago))
        DecisionExplanation.NeverPlayed -> stringResource(R.string.start_explain_never)
        is DecisionExplanation.Fixed ->
            stringResource(R.string.start_explain_fixed, stringResource(explanation.screen.labelRes))
        DecisionExplanation.NothingToContinue -> stringResource(R.string.start_explain_nothing_to_continue)
    }

/** Сколько прошло: минуты до часа, часы до двух суток, дальше дни. */
@Composable
private fun agoText(ago: Duration): String =
    when {
        ago < 1.hours -> ago.inWholeMinutes.toInt().let { pluralStringResource(R.plurals.time_minutes, it, it) }
        ago < 2.days -> ago.inWholeHours.toInt().let { pluralStringResource(R.plurals.time_hours, it, it) }
        else -> ago.inWholeDays.toInt().let { pluralStringResource(R.plurals.time_days, it, it) }
    }
