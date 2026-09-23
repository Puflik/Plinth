package io.github.puflik.plinth.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Выбор аудиофайла через SAF — мимо библиотеки и без разрешения на музыку.
 * Возвращает действие, открывающее системный диалог; выбранный файл
 * приходит в [onPicked] адресом `content://` и именем, если провайдер его знает.
 */
@Composable
fun rememberAudioFilePicker(onPicked: (uri: String, name: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                keepAccess(context, uri)
                onPicked(uri.toString(), displayName(context, uri))
            }
        }
    return { picker.launch(arrayOf(AUDIO_MIME)) }
}

/**
 * Сохраняет доступ к файлу за пределами жизни процесса: без этого после
 * перезапуска `content://` станет недоступен, и восстановление позиции
 * упрётся в `SourceUnavailable`. Не каждый провайдер SAF даёт постоянный
 * доступ — тогда играем с временным, пока жив процесс.
 */
private fun keepAccess(
    context: Context,
    uri: Uri,
) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun displayName(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private const val AUDIO_MIME = "audio/*"
