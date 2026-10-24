package io.github.puflik.plinth.online

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Выбор онлайн-источников переживает перезапуск (Ю3г). Имена ключей — формат на диске: главный `online_sources`
 * остаётся как был, выбор провайдеров — `online_providers_off`, набор выключенных.
 *
 * На Windows такие тесты падают с `IOException` в `FileStorage.kt` (как `DataStore*SettingsTest`);
 * в CI на Linux проходят.
 */
class DataStoreOnlineSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `nothing chosen yet means everything is on`() =
        runTest {
            val settings = DataStoreOnlineSettings(storeIn(this))

            assertThat(settings.enabled.first()).isTrue()
            assertThat(settings.disabledProviders.first()).isEmpty()
        }

    @Test
    fun `the main switch is still read from its old key`() =
        runTest {
            val store = storeIn(this)
            store.edit { it[booleanPreferencesKey("online_sources")] = false }

            assertThat(DataStoreOnlineSettings(store).enabled.first()).isFalse()
        }

    @Test
    fun `a provider switched off is kept apart from the main switch`() =
        runTest {
            val settings = DataStoreOnlineSettings(storeIn(this))

            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, false)
            settings.setEnabled(false)

            assertThat(settings.disabledProviders.first()).containsExactly(OnlineProviderIds.YOUTUBE)
            assertThat(settings.enabled.first()).isFalse()
        }

    @Test
    fun `switching a provider on removes only it and the same twice changes nothing`() =
        runTest {
            val settings = DataStoreOnlineSettings(storeIn(this))
            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, false)
            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, false)
            settings.setProviderEnabled(OnlineProviderIds.ARCHIVE, false)

            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, true)
            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, true)

            assertThat(settings.disabledProviders.first()).containsExactly(OnlineProviderIds.ARCHIVE)
        }

    @Test
    fun `an id this version does not know survives the switching of the others`() =
        runTest {
            val store = storeIn(this)
            store.edit { it[stringSetPreferencesKey("online_providers_off")] = setOf("future.provider") }
            val settings = DataStoreOnlineSettings(store)

            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, false)
            assertThat(settings.disabledProviders.first()).containsExactly("future.provider", OnlineProviderIds.YOUTUBE)

            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, true)
            assertThat(settings.disabledProviders.first()).containsExactly("future.provider")
        }

    @Test
    fun `the choice is on disk under its key`() =
        runTest {
            val store = storeIn(this)

            DataStoreOnlineSettings(store).setProviderEnabled(OnlineProviderIds.YOUTUBE, false)

            assertThat(store.data.first()[stringSetPreferencesKey("online_providers_off")])
                .containsExactly(OnlineProviderIds.YOUTUBE)
        }

    private fun storeIn(scope: TestScope) =
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { temp.root.resolve("online.preferences_pb") }
}
