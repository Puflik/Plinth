package io.github.puflik.plinth.settings

import io.github.puflik.plinth.library.sort.AlbumSort
import io.github.puflik.plinth.library.sort.TrackSort
import kotlinx.coroutines.flow.MutableStateFlow

/** Порядок списков в памяти для тестов. */
class FakeSortSettings(
    track: TrackSort = TrackSort.TITLE,
    album: AlbumSort = AlbumSort.TITLE,
) : SortSettings {
    override val trackSort = MutableStateFlow(track)
    override val albumSort = MutableStateFlow(album)

    override suspend fun setTrackSort(sort: TrackSort) {
        trackSort.value = sort
    }

    override suspend fun setAlbumSort(sort: AlbumSort) {
        albumSort.value = sort
    }
}
