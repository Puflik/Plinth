package io.github.puflik.plinth.ui.online

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.puflik.plinth.R
import io.github.puflik.plinth.online.FirmwareNetworkBlock

/**
 * «Нет сети», а сеть у Android есть и проверена, на телефоне Transsion —
 * значит, сеть закрыла прошивка (Н7). Считается, когда ошибка появилась на
 * экране: следующий поиск посчитает заново.
 */
@Composable
fun rememberFirmwareNetworkBlock(): Boolean {
    val context = LocalContext.current
    return remember(context) { FirmwareNetworkBlock.suspected(Build.MANUFACTURER, context.networkValidated()) }
}

/** Объяснение и кнопка в «Управление сетями» Phone Master. */
@Composable
fun FirmwareNetworkNote(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.online_firmware_block),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { openNetworkSettings(context) }) {
            Text(stringResource(R.string.online_firmware_block_open))
        }
    }
}

private fun Context.networkValidated(): Boolean =
    getSystemService(ConnectivityManager::class.java)
        ?.let { it.getNetworkCapabilities(it.activeNetwork) }
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

/**
 * Экран Phone Master, если он есть и открывается; иначе страница приложения
 * в настройках Android — там у HiOS тоже бывает «Использование данных».
 */
private fun openNetworkSettings(context: Context) {
    val target = FirmwareNetworkBlock.SETTINGS
    val candidates =
        listOf(
            Intent().setComponent(ComponentName(target.packageName, target.className)),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    candidates.firstOrNull { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (expected: ActivityNotFoundException) {
            false
        } catch (expected: SecurityException) {
            false
        }
    }
}
