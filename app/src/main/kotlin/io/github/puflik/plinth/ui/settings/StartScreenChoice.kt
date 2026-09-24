package io.github.puflik.plinth.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.settings.StartScreen
import io.github.puflik.plinth.ui.start.explanationText

/**
 * «Стартовый экран»: Авто, Библиотека, Продолжить (F3, план 12.6) — и под
 * выбором, почему этот запуск открылся там, где открылся (12.2).
 */
internal fun LazyListScope.startScreenChoice(
    state: StartScreenSettingUiState,
    onChoose: (StartScreen) -> Unit,
) {
    item(key = "start-title") {
        Text(
            text = stringResource(R.string.settings_start_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
    }
    StartScreen.entries.forEach { screen ->
        item(key = "start-$screen") {
            ListItem(
                headlineContent = { Text(stringResource(screen.labelRes)) },
                supportingContent = { Text(stringResource(screen.hintRes)) },
                leadingContent = { RadioButton(selected = state.choice == screen, onClick = null) },
                modifier =
                    Modifier.selectable(
                        selected = state.choice == screen,
                        onClick = { onChoose(screen) },
                        role = Role.RadioButton,
                    ),
            )
        }
    }
    state.lastDecision?.let { decision ->
        item(key = "start-why") {
            Text(
                text = stringResource(R.string.settings_start_why, explanationText(decision.explanation)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@get:StringRes
internal val StartScreen.labelRes: Int
    get() =
        when (this) {
            StartScreen.AUTO -> R.string.settings_start_auto
            StartScreen.LIBRARY -> R.string.settings_start_library
            StartScreen.CONTINUE -> R.string.settings_start_continue
        }

@get:StringRes
private val StartScreen.hintRes: Int
    get() =
        when (this) {
            StartScreen.AUTO -> R.string.settings_start_auto_hint
            StartScreen.LIBRARY -> R.string.settings_start_library_hint
            StartScreen.CONTINUE -> R.string.settings_start_continue_hint
        }
