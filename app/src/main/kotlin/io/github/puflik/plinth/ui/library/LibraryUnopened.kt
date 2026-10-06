package io.github.puflik.plinth.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/** Ядро не открылось — вместо [content] экран [LibraryUnopened]; иначе [content]. */
@Composable
internal fun UnopenedOr(
    unopened: Boolean,
    onRetry: () -> Unit,
    onSaveLog: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (unopened) LibraryUnopened(onRetry, onSaveLog) else content()
}

/**
 * Ядро не открылось (Р1.4, ревью v0.2): экран библиотеки говорит об этом, а не
 * остаётся пустым — пустые списки напугали бы, а лайки, плейлисты и история не
 * удалены. Две частые причины — кончилось место и данные более новой версии —
 * названы в тексте; [onRetry] открывает ядро снова, без перезапуска, [onSaveLog]
 * сохраняет лог для отчёта.
 */
@Composable
internal fun LibraryUnopened(
    onRetry: () -> Unit,
    onSaveLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.library_unopened_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.library_unopened_text), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(stringResource(R.string.library_unopened_retry)) }
            TextButton(onClick = onSaveLog) { Text(stringResource(R.string.settings_save_log)) }
        }
    }
}
