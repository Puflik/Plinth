package io.github.puflik.plinth.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.ScanProgress

/**
 * Экран папок (C2.5): что сканировать и что пропускать; папки выбираются
 * системным диалогом (`OpenDocumentTree`). Изменения доходят до библиотеки
 * после «Пересканировать».
 */
@Composable
fun FolderSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: FolderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val include = rememberFolderPicker(viewModel::onInclude)
    val exclude = rememberFolderPicker(viewModel::onExclude)
    LazyColumn(modifier = modifier.fillMaxSize()) {
        folderSection(R.string.folders_included, state.folders.included, viewModel::onRemove)
        folderSection(R.string.folders_excluded, state.folders.excluded, viewModel::onRemove)
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = include) { Text(stringResource(R.string.folders_add)) }
                OutlinedButton(onClick = exclude) { Text(stringResource(R.string.folders_exclude)) }
            }
        }
        item(key = "reset") {
            TextButton(
                onClick = viewModel::onReset,
                enabled = !state.isDefault,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.folders_reset)) }
        }
        item(key = "rescan") { Rescan(state.scan, viewModel::onRescan) }
    }
}

private fun LazyListScope.folderSection(
    @StringRes title: Int,
    folders: List<String>,
    onRemove: (String) -> Unit,
) {
    item(key = title) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
    }
    if (folders.isEmpty()) {
        item(key = "$title-empty") { ListItem(headlineContent = { Text(stringResource(R.string.folders_none)) }) }
    }
    items(folders, key = { "$title-$it" }) { folder ->
        ListItem(
            headlineContent = { Text(folder.ifEmpty { stringResource(R.string.folders_whole_storage) }) },
            trailingContent = {
                TextButton(onClick = { onRemove(folder) }) { Text(stringResource(R.string.folders_remove)) }
            },
        )
    }
}

@Composable
private fun Rescan(
    scan: ScanProgress,
    onRescan: () -> Unit,
) {
    val running = scan is ScanProgress.Running
    Button(onClick = onRescan, enabled = !running, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.folders_rescan))
    }
    when (scan) {
        is ScanProgress.Running -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
        is ScanProgress.Done ->
            Text(
                text = pluralStringResource(R.plurals.library_found, scan.found, scan.found),
                modifier = Modifier.padding(16.dp),
            )
        ScanProgress.Failed ->
            Text(
                text = stringResource(R.string.library_scan_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        ScanProgress.Idle -> Unit
    }
}

/** Системный выбор папки; папку не из хранилища устройства не берём и говорим об этом. */
@Composable
private fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val folder = uri.authority?.let { TreeFolder.path(it, DocumentsContract.getTreeDocumentId(uri)) }
                if (folder != null) {
                    onPicked(folder)
                } else {
                    Toast.makeText(context, R.string.folders_not_local, Toast.LENGTH_LONG).show()
                }
            }
        }
    return { picker.launch(null) }
}
