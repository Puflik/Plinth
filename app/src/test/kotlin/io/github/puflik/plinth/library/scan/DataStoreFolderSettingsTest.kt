package io.github.puflik.plinth.library.scan

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Выбор папок переживает перезапуск (C2.5): хранится в DataStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreFolderSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `nothing chosen yet means the default folders`() =
        runTest {
            val settings = settingsIn(backgroundScope)

            assertThat(settings.folders.first()).isEqualTo(FolderConfig.DEFAULT)
        }

    @Test
    fun `chosen folders are read back from the same file`() =
        runTest {
            // Один файл — один живой DataStore: первый закрывается до того, как файл откроют снова.
            val first = CoroutineScope(backgroundScope.coroutineContext + Job())
            settingsIn(first).update { it.exclude("Download/").include("Podcasts/") }
            first.cancel()
            advanceUntilIdle()

            val reopened = settingsIn(backgroundScope)

            assertThat(
                reopened.folders.first(),
            ).isEqualTo(FolderConfig(listOf("Music/", "Podcasts/"), listOf("Download/")))
        }

    @Test
    fun `default chosen again reads back exactly as the default`() =
        runTest {
            val settings = settingsIn(backgroundScope)
            settings.update { it.remove("Download/") }

            settings.update { FolderConfig.DEFAULT }

            assertThat(settings.folders.first()).isEqualTo(FolderConfig.DEFAULT)
        }

    @Test
    fun `removing every folder is kept, not replaced by the default`() =
        runTest {
            val settings = settingsIn(backgroundScope)

            settings.update { it.remove("Music/").remove("Download/") }

            assertThat(settings.folders.first()).isEqualTo(FolderConfig(emptyList(), emptyList()))
        }

    private fun settingsIn(scope: CoroutineScope): DataStoreFolderSettings {
        val file = temp.root.resolve("folders.preferences_pb")
        return DataStoreFolderSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
    }
}
