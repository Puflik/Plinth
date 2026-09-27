package io.github.puflik.plinth.ui.backup

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ui.settings.OpenFolder
import java.text.DateFormat
import java.util.Date

/**
 * Шаг мастера «Копия ваших данных» (C4): зачем папка, выбор папки и, если
 * в ней данные прошлой установки, вопрос «Восстановить?». Шаг можно
 * пропустить — тогда о папке напомнят настройки.
 */
@Composable
fun BackupStep(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val pick = rememberMirrorFolderPicker(viewModel::onFolderPicked)
    Column(
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.backup_text), style = MaterialTheme.typography.bodyMedium)
        state.folder?.let {
            Text(stringResource(R.string.backup_folder, it), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = pick, enabled = !state.busy) {
            Text(stringResource(if (state.folder == null) R.string.backup_choose else R.string.backup_change))
        }
        if (state.busy) LinearProgressIndicator()
    }
    BackupPrompts(state, viewModel)
}

/**
 * Раздел настроек «Копия данных»: папка или напоминание, что её нет, и
 * выбор папки. Вопрос и итоги показывает [BackupPrompts] экрана настроек.
 */
internal fun LazyListScope.backupSection(
    state: BackupUiState,
    onPick: () -> Unit,
) {
    item(key = "backup") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_settings_title)) },
            supportingContent = {
                val folder = state.folder
                Text(
                    if (folder != null) {
                        stringResource(R.string.backup_folder, folder)
                    } else {
                        stringResource(R.string.backup_none)
                    },
                )
            },
            trailingContent = {
                OutlinedButton(onClick = onPick, enabled = !state.busy) {
                    Text(stringResource(if (state.folder == null) R.string.backup_choose else R.string.backup_change))
                }
            },
        )
    }
}

/** Вопрос «Восстановить?» и сообщения об итоге. */
@Composable
fun BackupPrompts(
    state: BackupUiState,
    viewModel: BackupViewModel,
) {
    val context = LocalContext.current
    state.found?.let { found ->
        AlertDialog(
            onDismissRequest = viewModel::onDecline,
            title = { Text(stringResource(R.string.backup_title)) },
            text = { Text(foundText(found)) },
            confirmButton = {
                TextButton(onClick = viewModel::onRestore) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDecline) { Text(stringResource(R.string.restore_decline)) }
            },
        )
    }
    val message =
        when (state.outcome) {
            BackupOutcome.RESTORED -> R.string.backup_restored
            BackupOutcome.NO_ACCESS -> R.string.backup_no_access
            BackupOutcome.FAILED -> R.string.backup_failed
            null -> null
        }
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.onOutcomeShown()
        }
    }
}

/**
 * «Найдены данные предыдущей установки от 27 сентября 2026 г.: 12 лайков, 3 плейлиста. Восстановить?»
 * Дата — не в конце фразы: по-русски она сама кончается точкой.
 */
@Composable
private fun foundText(found: FoundData): String {
    val likes = pluralStringResource(R.plurals.backup_likes, found.likes, found.likes)
    val playlists = pluralStringResource(R.plurals.backup_playlists, found.playlists, found.playlists)
    val locale = LocalConfiguration.current.locales[0]
    val date =
        found.writtenAt?.let {
            DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(it.toEpochMilliseconds()))
        }
    return if (date != null) {
        stringResource(R.string.restore_text, likes, playlists, date)
    } else {
        stringResource(R.string.restore_text_undated, likes, playlists)
    }
}

/** Системный выбор папки копии; открывается на `Music` внутренней памяти. */
@Composable
fun rememberMirrorFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val picker = rememberLauncherForActivityResult(OpenFolder()) { uri: Uri? -> uri?.let { onPicked(it.toString()) } }
    return { picker.launch(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, MUSIC)) }
}

private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
private const val MUSIC = "primary:Music"
