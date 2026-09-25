package io.github.puflik.plinth.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.core.config.ProjectLinks
import io.github.puflik.plinth.startup.OnboardingStep
import io.github.puflik.plinth.ui.common.rememberSafeUriHandler

/**
 * Пустая библиотека (F2, план 12.5): не «файлы не найдены», а проводник —
 * выбрать папку, где лежит музыка, — и честное «что дальше» со ссылкой на
 * релизы.
 *
 * Если папки пропустили в мастере ([prompt]), пустое состояние говорит об
 * этом прямо и даёт отказаться: «Не сейчас» — и шаг больше не вернётся.
 */
@Composable
internal fun EmptyLibrary(
    folders: List<String>,
    prompt: OnboardingStep?,
    onPickFolder: () -> Unit,
    onDismissPrompt: (OnboardingStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = rememberSafeUriHandler()
    val skippedFolders = prompt == OnboardingStep.FOLDERS
    val wholeStorage = stringResource(R.string.folders_whole_storage)
    val lookedIn = folders.joinToString(", ") { folder -> folder.trimEnd('/').ifEmpty { wholeStorage } }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text =
                stringResource(
                    if (skippedFolders) R.string.library_prompt_folders_title else R.string.library_empty_title,
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                when {
                    folders.isEmpty() -> stringResource(R.string.library_empty_no_folders)
                    skippedFolders -> stringResource(R.string.library_prompt_folders_text, lookedIn)
                    else -> stringResource(R.string.library_empty_looked_in, lookedIn)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder) { Text(stringResource(R.string.library_empty_pick)) }
            if (prompt != null) {
                TextButton(onClick = { onDismissPrompt(prompt) }) {
                    Text(stringResource(R.string.library_prompt_not_now))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(text = stringResource(R.string.library_next_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.library_next_text), style = MaterialTheme.typography.bodyMedium)
        // Без бокового отступа кнопки ссылка стоит вровень с текстом над ней.
        TextButton(
            onClick = { uriHandler.openUri(ProjectLinks.RELEASES) },
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            Text(stringResource(R.string.library_next_releases))
        }
    }
}
