package io.github.puflik.plinth.ui.common

import androidx.compose.ui.platform.UriHandler
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Ссылка, которую нечем открыть (ревью №8): телефон без браузера, рабочий
 * профиль, детский режим. `AndroidUriHandler` превращает
 * `ActivityNotFoundException` в `IllegalArgumentException` — приложение не
 * падает, человек узнаёт, что ссылку открыть нечем.
 */
class SafeUriHandlerTest {
    @Test
    fun `link goes to the system as it is`() {
        val opened = mutableListOf<String>()
        val handler = SafeUriHandler(handler { opened += it }, onFailure = {})

        handler.openUri("https://github.com/puflik/plinth/releases")

        assertThat(opened).containsExactly("https://github.com/puflik/plinth/releases")
    }

    @Test
    fun `link with nothing to open it is reported without a crash`() {
        var failures = 0
        val handler =
            SafeUriHandler(
                handler { throw IllegalArgumentException("Can't open $it.") },
                onFailure = { failures++ },
            )

        handler.openUri("https://github.com/puflik/plinth/issues/new")

        assertThat(failures).isEqualTo(1)
    }

    private fun handler(open: (String) -> Unit) =
        object : UriHandler {
            override fun openUri(uri: String) = open(uri)
        }
}
