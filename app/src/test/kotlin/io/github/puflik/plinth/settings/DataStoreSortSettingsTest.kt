package io.github.puflik.plinth.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Порядок списков библиотеки переживает перезапуск (C4.3). */
class DataStoreSortSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `nothing chosen yet means by title`() =
        runTest {
            val settings = DataStoreSortSettings(storeIn(this))

            assertThat(settings.trackSort.first()).isEqualTo(TrackSort.TITLE)
            assertThat(settings.albumSort.first()).isEqualTo(AlbumSort.TITLE)
        }

    @Test
    fun `chosen orders are kept separately`() =
        runTest {
            val settings = DataStoreSortSettings(storeIn(this))

            settings.setTrackSort(TrackSort.ALBUM)
            settings.setAlbumSort(AlbumSort.ARTIST)

            assertThat(settings.trackSort.first()).isEqualTo(TrackSort.ALBUM)
            assertThat(settings.albumSort.first()).isEqualTo(AlbumSort.ARTIST)
        }

    @Test
    fun `order unknown to this version falls back to title`() =
        runTest {
            val store = storeIn(this)
            store.edit { it[stringPreferencesKey("track_sort")] = "YEAR" }

            assertThat(DataStoreSortSettings(store).trackSort.first()).isEqualTo(TrackSort.TITLE)
        }

    private fun storeIn(scope: kotlinx.coroutines.test.TestScope) =
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { temp.root.resolve("sort.preferences_pb") }
}
