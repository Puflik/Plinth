package io.github.puflik.plinth.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ui.common.rememberSafeUriHandler
import kotlinx.coroutines.launch

/**
 * После сбоя (G1.3): диалог «сохранить отчёт или нет». Сохранили —
 * внизу «Сообщить на GitHub»: шаблон issue открывается, только если человек
 * сам этого захотел. Ответ любой — предложение больше не появляется.
 */
@Composable
fun CrashReportOffer(
    snackbar: SnackbarHostState,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val hasCrash by viewModel.hasCrash.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = rememberSafeUriHandler()
    val saved = stringResource(R.string.report_saved)
    val failed = stringResource(R.string.report_save_failed)
    val toGitHub = stringResource(R.string.report_on_github)
    val save =
        rememberReportSaver(viewModel) { ok ->
            scope.launch {
                if (!ok) {
                    snackbar.showSnackbar(failed)
                } else if (snackbar.showSnackbar(saved, toGitHub, duration = SnackbarDuration.Long) ==
                    SnackbarResult.ActionPerformed
                ) {
                    uriHandler.openUri(viewModel.issueUrl)
                }
            }
        }
    if (hasCrash) {
        AlertDialog(
            // Касание мимо и «Назад» — не отказ: отчёт остаётся (ревью №14). Отказ — только «Не сейчас».
            onDismissRequest = viewModel::closeCrashOffer,
            title = { Text(stringResource(R.string.crash_title)) },
            text = { Text(stringResource(R.string.crash_text)) },
            confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.crash_save)) } },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCrash) { Text(stringResource(R.string.crash_not_now)) }
            },
        )
    }
}

/** «Диагностика» в настройках (G1.3): сохранить лог файлом и сообщить о проблеме. */
internal fun LazyListScope.diagnosticsSection(
    onSaveLog: () -> Unit,
    onReport: () -> Unit,
) {
    item(key = "diagnostics-title") {
        Text(
            text = stringResource(R.string.settings_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
    }
    item(key = "diagnostics-save") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_save_log)) },
            supportingContent = { Text(stringResource(R.string.settings_save_log_hint)) },
            modifier = Modifier.clickable(onClick = onSaveLog),
        )
    }
    item(key = "diagnostics-report") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_report)) },
            supportingContent = { Text(stringResource(R.string.settings_report_hint)) },
            modifier = Modifier.clickable(onClick = onReport),
        )
    }
}

/** «Сохранить лог» из настроек: итог — коротким сообщением. */
@Composable
internal fun rememberLogSaver(viewModel: DiagnosticsViewModel): () -> Unit {
    val context = LocalContext.current
    return rememberReportSaver(viewModel) { ok ->
        val text = if (ok) R.string.report_saved else R.string.report_save_failed
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Системный «Создать файл» и отчёт в него; [onDone] — сохранили или нет.
 * Отказ в самом диалоге выбора файла — не ошибка: ничего не происходит.
 */
@Composable
private fun rememberReportSaver(
    viewModel: DiagnosticsViewModel,
    onDone: (Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                scope.launch {
                    val stream = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                    val ok = stream != null && runCatching { stream.use { viewModel.writeReport(it) } }.isSuccess
                    onDone(ok)
                }
            }
        }
    return { launcher.launch(REPORT_FILE) }
}

private const val REPORT_FILE = "plinth-report.txt"
