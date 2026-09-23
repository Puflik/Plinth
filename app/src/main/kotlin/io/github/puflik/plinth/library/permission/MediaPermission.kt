package io.github.puflik.plinth.library.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Разрешение на чтение музыки (C1.1). С Android 13 — только аудио
 * (`READ_MEDIA_AUDIO`), раньше — всё общее хранилище (`READ_EXTERNAL_STORAGE`,
 * в манифесте ограничено `maxSdkVersion="32"`).
 */
object MediaPermission {
    fun name(sdk: Int = Build.VERSION.SDK_INT): String =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, name()) == PackageManager.PERMISSION_GRANTED
}
