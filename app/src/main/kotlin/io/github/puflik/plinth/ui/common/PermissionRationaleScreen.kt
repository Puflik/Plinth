package io.github.puflik.plinth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/**
 * Зачем приложению музыка и как её дать (C1.2) — шаг мастера первого запуска.
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
        Explanation(permanentlyDenied, MaterialTheme.typography.titleMedium)
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.library_permission_open_settings)) }
            TextButton(onClick = onRequest) { Text(stringResource(R.string.library_permission_ask_again)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.library_permission_allow)) }
        }
    }
}

/**
 * То же объяснение карточкой над файловыми вкладками библиотеки (Н4): под ней
 * остаются списки, а вкладка плейлистов, где сетевые треки играют и без
 * доступа, — рядом. Кнопки — те же, справа, как у баннера.
 */
@Composable
fun PermissionRationaleCard(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Explanation(permanentlyDenied, MaterialTheme.typography.titleSmall)
            // Две кнопки по-русски в узкий телефон не влезают — вторая уходит строкой ниже.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (permanentlyDenied) {
                    TextButton(onClick = onRequest) { Text(stringResource(R.string.library_permission_ask_again)) }
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.library_permission_open_settings))
                    }
                } else {
                    Button(onClick = onRequest) { Text(stringResource(R.string.library_permission_allow)) }
                }
            }
        }
    }
}

@Composable
private fun Explanation(
    permanentlyDenied: Boolean,
    titleStyle: TextStyle,
) {
    Text(text = stringResource(R.string.library_permission_title), style = titleStyle)
    val explanation =
        if (permanentlyDenied) R.string.library_permission_denied else R.string.library_permission_rationale
    Text(text = stringResource(explanation), style = MaterialTheme.typography.bodyMedium)
}
