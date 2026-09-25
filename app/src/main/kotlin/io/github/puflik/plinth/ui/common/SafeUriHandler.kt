package io.github.puflik.plinth.ui.common

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.github.puflik.plinth.R
import io.github.puflik.plinth.diagnostics.log.AppLog

/**
 * Ссылка, которую нечем открыть (ревью №8): телефон без браузера, рабочий
 * профиль, детский режим. `AndroidUriHandler` превращает
 * `ActivityNotFoundException` в `IllegalArgumentException`, и без этой обёртки
 * касание «Сообщить о проблеме» роняло приложение.
 */
class SafeUriHandler(
    private val delegate: UriHandler,
    private val onFailure: () -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        try {
            delegate.openUri(uri)
        } catch (expected: IllegalArgumentException) {
            AppLog.w(TAG, "no app to open a link", expected)
            onFailure()
        }
    }

    private companion object {
        const val TAG = "Links"
    }
}

/** [SafeUriHandler] поверх системного: ссылку нечем открыть — короткое сообщение. */
@Composable
fun rememberSafeUriHandler(): UriHandler {
    val platform = LocalUriHandler.current
    val context = LocalContext.current
    return remember(platform, context) {
        SafeUriHandler(platform) { Toast.makeText(context, R.string.link_no_app, Toast.LENGTH_SHORT).show() }
    }
}
