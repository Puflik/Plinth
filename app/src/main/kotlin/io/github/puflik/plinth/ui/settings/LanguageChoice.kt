package io.github.puflik.plinth.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.settings.AppLanguage

/**
 * Язык Plinth из настроек Android 13+ (12.12); на старых версиях `null` —
 * там у приложения нет своего языка, только системный. Перечитывается при
 * смене конфигурации: выбор в системе пересоздаёт экран.
 */
@Composable
internal fun rememberAppLanguage(): AppLanguage? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) { appLanguage(context) }
}

/** «Язык приложения» с текущим языком; касание — системный выбор языка для Plinth. */
internal fun LazyListScope.languageChoice(
    language: AppLanguage,
    onOpen: () -> Unit,
) {
    item(key = "language-title") {
        Text(
            text = stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
    }
    item(key = "language") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_language_app)) },
            supportingContent = {
                Text(
                    when (language) {
                        AppLanguage.System -> stringResource(R.string.settings_language_system)
                        is AppLanguage.Chosen -> language.nativeName
                    },
                )
            },
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

/** Системный экран «Язык приложения» для Plinth (Android 13+). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun openLanguageSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun appLanguage(context: Context): AppLanguage =
    AppLanguage.of(context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
