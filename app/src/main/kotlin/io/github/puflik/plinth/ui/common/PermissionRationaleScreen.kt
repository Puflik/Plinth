package io.github.puflik.plinth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/**
 * Зачем приложению музыка и как её дать (C1.2).
 *
 * После первого отказа — объяснение и повторный запрос. Когда система,
 * похоже, больше не покажет диалог ([permanentlyDenied]), главная кнопка ведёт
 * в настройки приложения, а рядом — «Спросить снова»: на Android 11+ диалог,
 * закрытый «Назад», для приложения неотличим от отказа навсегда (ревью №18).
 * Если отказ правда навсегда, система ответит сразу, без диалога.
 */
@Composable
fun PermissionRationaleScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.library_permission_title),
            style = MaterialTheme.typography.titleMedium,
        )
        val explanation =
            if (permanentlyDenied) R.string.library_permission_denied else R.string.library_permission_rationale
        Text(
            text = stringResource(explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.library_permission_open_settings)) }
            TextButton(onClick = onRequest) { Text(stringResource(R.string.library_permission_ask_again)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.library_permission_allow)) }
        }
    }
}
