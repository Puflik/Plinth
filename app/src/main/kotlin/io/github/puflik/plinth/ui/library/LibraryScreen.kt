package io.github.puflik.plinth.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.permission.MediaPermission
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.ui.common.PermissionRationaleScreen
import io.github.puflik.plinth.ui.player.FilePlayerScreen

/**
 * Библиотека до списков (шаг 4 вертикали): разрешение, скан и «Найдено N
 * треков» над плеером первой вертикали. Списки встанут сюда на шаге 5.
 *
 * Разрешение проверяется при каждом возврате на экран: его могли выдать или
 * отозвать в настройках. Первый раз экран спрашивает сам, не объясняя, —
 * объяснение нужно только после отказа.
 */
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = remember { MediaPermission.name() }
    // Пришёл ли ответ на запрос: без него «нет объяснения» не отличить от «отказано навсегда».
    var requested by rememberSaveable { mutableStateOf(false) }

    fun currentState(): PermissionState {
        val showRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) }
        return PermissionState.of(MediaPermission.isGranted(context), requested, showRationale == true)
    }

    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            requested = true
            viewModel.onPermission(currentState())
        }
    LifecycleResumeEffect(Unit) {
        val current = currentState()
        viewModel.onPermission(current)
        if (current == PermissionState.NotRequested) request.launch(permission)
        onPauseOrDispose {}
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (state.permission) {
            PermissionState.Granted -> ScanStatus(state.scan)
            PermissionState.Denied, PermissionState.PermanentlyDenied ->
                PermissionRationaleScreen(
                    permanentlyDenied = state.permission == PermissionState.PermanentlyDenied,
                    onRequest = { request.launch(permission) },
                    onOpenSettings = { openAppSettings(context) },
                )
            PermissionState.NotRequested -> Unit
        }
        FilePlayerScreen(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ScanStatus(scan: ScanProgress) {
    val text =
        when (scan) {
            ScanProgress.Idle -> return
            is ScanProgress.Running ->
                if (scan.total == 0) {
                    stringResource(R.string.library_scanning)
                } else {
                    stringResource(R.string.library_scan_progress, scan.written, scan.total)
                }
            is ScanProgress.Done -> pluralStringResource(R.plurals.library_found, scan.found, scan.found)
            ScanProgress.Failed -> stringResource(R.string.library_scan_failed)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
