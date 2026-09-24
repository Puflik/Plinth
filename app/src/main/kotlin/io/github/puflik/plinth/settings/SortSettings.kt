package io.github.puflik.plinth.settings

import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.Flow

/**
 * Порядок списков библиотеки, выбранный пользователем (C4.3); переживает
 * перезапуск. Пока ничего не выбрано — по названию.
 */
interface SortSettings {
    val trackSort: Flow<TrackSort>

    val albumSort: Flow<AlbumSort>

    suspend fun setTrackSort(sort: TrackSort)

    suspend fun setAlbumSort(sort: AlbumSort)
}
