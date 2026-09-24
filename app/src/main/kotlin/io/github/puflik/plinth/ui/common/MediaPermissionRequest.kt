package io.github.puflik.plinth.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.puflik.plinth.library.permission.MediaPermission
import io.github.puflik.plinth.library.permission.PermissionState

/**
 * Следит за разрешением на музыку и сообщает его [onState] при каждом
 * возврате на экран: его могли выдать или отозвать в настройках.
 * Возвращает действие «спросить».
 *
 * @param askFirst спросить самому, пока не спрашивали: так делает
 *   библиотека. Мастер первого запуска сначала объясняет и спрашивает по
 *   кнопке.
 */
@Composable
fun rememberMediaPermission(
    askFirst: Boolean,
    onState: (PermissionState) -> Unit,
): () -> Unit {
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
            onState(currentState())
        }
    LifecycleResumeEffect(Unit) {
        val current = currentState()
        onState(current)
        if (askFirst && current == PermissionState.NotRequested) request.launch(permission)
        onPauseOrDispose {}
    }
    return { request.launch(permission) }
}

/** Страница приложения в системных настройках: после отказа навсегда разрешение выдаётся только там. */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
