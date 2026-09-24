package io.github.puflik.plinth.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Стартовый экран — настройка, умолчание «Авто» (F3, план 12.6). */
class DataStoreStartSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `nothing chosen means auto`() =
        runTest {
            assertThat(DataStoreStartSettings(storeIn(this)).startScreen.first()).isEqualTo(StartScreen.AUTO)
        }

    @Test
    fun `chosen screen is kept`() =
        runTest {
            val store = storeIn(this)
            DataStoreStartSettings(store).setStartScreen(StartScreen.CONTINUE)

            assertThat(DataStoreStartSettings(store).startScreen.first()).isEqualTo(StartScreen.CONTINUE)
        }

    @Test
    fun `screen unknown to this version falls back to auto`() =
        runTest {
            val store = storeIn(this)
            store.edit { it[stringPreferencesKey("start_screen")] = "DISCOVER" }

            assertThat(DataStoreStartSettings(store).startScreen.first()).isEqualTo(StartScreen.AUTO)
        }

    private fun storeIn(scope: TestScope) =
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { temp.root.resolve("start.preferences_pb") }
}
