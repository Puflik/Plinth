package io.github.puflik.plinth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/**
 * Зачем приложению музыка и как её дать (C1.2).
 *
 * После первого отказа — объяснение и повторный запрос. Когда система
 * больше не покажет диалог ([permanentlyDenied]), выдать разрешение можно
 * только в настройках приложения — туда и ведёт кнопка.
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
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.library_permission_allow)) }
        }
    }
}
