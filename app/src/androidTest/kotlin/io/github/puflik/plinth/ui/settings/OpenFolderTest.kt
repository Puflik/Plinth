package io.github.puflik.plinth.ui.settings

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Н4 прогона на старых Android: на Android 8 системный выбор папки открывался
 * на «Recent — No items», а память телефона пряталась за «⋮ → Show internal
 * storage». Запрос просит показать её сразу.
 */
class OpenFolderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun folder_picker_shows_the_phone_storage_at_once() {
        val intent = OpenFolder().createIntent(context, null)

        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT_TREE)
        assertThat(intent.getBooleanExtra("android.content.extra.SHOW_ADVANCED", false)).isTrue()
    }
}
