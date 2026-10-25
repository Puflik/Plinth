package io.github.puflik.plinth.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ui.common.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Экран «Лицензии» (Ю3г): сторонние компоненты и их лицензии — текст `NOTICE` из assets как есть, без правок.
 * Читается не на главном потоке; не прочитался — сообщение цветом ошибки, а не пустой экран.
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notice by produceState<NoticeState>(NoticeState.Loading, context) {
        value = withContext(Dispatchers.IO) { readNotice(context) }
    }
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = stringResource(R.string.licenses_title), onBack = onBack)
        when (val state = notice) {
            NoticeState.Loading -> Unit
            is NoticeState.Loaded ->
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            NoticeState.Failed ->
                Text(
                    text = stringResource(R.string.licenses_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
        }
    }
}

/** Что сейчас на экране «Лицензии»: ещё читается, прочитано или не прочиталось. */
private sealed interface NoticeState {
    data object Loading : NoticeState

    data class Loaded(
        val text: String,
    ) : NoticeState

    data object Failed : NoticeState
}

/** Текст asset `NOTICE` (UTF-8); не прочитался — запись в журнал и [NoticeState.Failed], а не сбой экрана. */
private fun readNotice(context: Context): NoticeState =
    try {
        NoticeState.Loaded(context.assets.open(NOTICE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (expected: IOException) {
        AppLog.w(TAG, "cannot read the $NOTICE_ASSET asset", expected)
        NoticeState.Failed
    }

private const val NOTICE_ASSET = "NOTICE"
private const val TAG = "Licenses"
