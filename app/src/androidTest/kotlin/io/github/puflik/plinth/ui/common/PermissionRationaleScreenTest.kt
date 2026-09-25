package io.github.puflik.plinth.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import org.junit.Rule
import org.junit.Test

/**
 * Ревью №18: на Android 11+ закрытый «Назад» системный диалог выглядит для
 * приложения так же, как отказ навсегда. Спросить снова можно и тогда: если
 * отказ правда навсегда, система ответит сразу, и останутся настройки.
 */
class PermissionRationaleScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun turned_off_access_can_still_be_asked_again() {
        var requests = 0
        var settings = 0
        compose.setContent {
            PermissionRationaleScreen(
                permanentlyDenied = true,
                onRequest = { requests++ },
                onOpenSettings = { settings++ },
            )
        }

        compose.onNodeWithText(context.getString(R.string.library_permission_ask_again)).performClick()
        compose.onNodeWithText(context.getString(R.string.library_permission_open_settings)).performClick()

        assertThat(requests).isEqualTo(1)
        assertThat(settings).isEqualTo(1)
    }
}
