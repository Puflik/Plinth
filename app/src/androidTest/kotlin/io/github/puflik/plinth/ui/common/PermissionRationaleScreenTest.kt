package io.github.puflik.plinth.ui.common

import androidx.compose.ui.test.assertIsDisplayed
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
 * отказ правда навсегда, система ответит сразу, и останутся настройки. Так и
 * в мастере, и в карточке над файловыми вкладками библиотеки (Н4).
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

    @Test
    fun card_after_the_first_refusal_explains_and_asks_again() {
        var requests = 0
        compose.setContent {
            PermissionRationaleCard(permanentlyDenied = false, onRequest = { requests++ }, onOpenSettings = {})
        }

        compose.onNodeWithText(context.getString(R.string.library_permission_rationale)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_permission_open_settings)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_permission_allow)).performClick()

        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun card_with_access_turned_off_leads_to_settings_and_can_still_ask() {
        var requests = 0
        var settings = 0
        compose.setContent {
            PermissionRationaleCard(
                permanentlyDenied = true,
                onRequest = { requests++ },
                onOpenSettings = { settings++ },
            )
        }

        compose.onNodeWithText(context.getString(R.string.library_permission_denied)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_permission_ask_again)).performClick()
        compose.onNodeWithText(context.getString(R.string.library_permission_open_settings)).performClick()

        assertThat(requests).isEqualTo(1)
        assertThat(settings).isEqualTo(1)
    }
}
