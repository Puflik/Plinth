package io.github.puflik.plinth.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [SortSettings] в DataStore: имя варианта строкой. Вариант, которого эта
 * версия не знает (переименовали или убрали), читается как «по названию».
 */
class DataStoreSortSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : SortSettings {
        override val trackSort: Flow<TrackSort> = read(TRACK_SORT, TrackSort.TITLE)

        override val albumSort: Flow<AlbumSort> = read(ALBUM_SORT, AlbumSort.TITLE)

        override suspend fun setTrackSort(sort: TrackSort) {
            store.edit { it[TRACK_SORT] = sort.name }
        }

        override suspend fun setAlbumSort(sort: AlbumSort) {
            store.edit { it[ALBUM_SORT] = sort.name }
        }

        private inline fun <reified T : Enum<T>> read(
            key: Preferences.Key<String>,
            default: T,
        ): Flow<T> =
            store.data
                .map { preferences -> enumValues<T>().find { it.name == preferences[key] } ?: default }
                .distinctUntilChanged()

        private companion object {
            val TRACK_SORT = stringPreferencesKey("track_sort")
            val ALBUM_SORT = stringPreferencesKey("album_sort")
        }
    }
