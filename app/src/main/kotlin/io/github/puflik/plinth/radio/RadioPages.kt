package io.github.puflik.plinth.radio

import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueItem

/**
 * Какие треки [page] ещё не стоят в [queue] (Ю4в) — их индексы в порядке страницы. [known] — ID каталога у треков
 * страницы (`null` — трека в каталоге нет). Повтор — тот же трек каталога или та же песня под другим видео (название
 * и исполнитель без регистра и знаков; без исполнителя сравнивается только ID); сравнивается со всем, что в очереди:
 * контекстом целиком, играющим ручным треком и ручным блоком, и между треками самой страницы.
 */
internal fun freshIndices(
    page: List<OnlineTrack>,
    known: List<TrackId?>,
    queue: PlaybackQueue,
): List<Int> {
    val queued = queue.contextItems + listOfNotNull(queue.playingManual) + queue.upNext
    val ids = queued.mapNotNull { (it.source as? AudioSource.Online)?.track }.toMutableSet()
    val songs = queued.mapNotNull { songKey(it.title, it.artist) }.toMutableSet()
    return page.indices.filter { index ->
        val newId = known.getOrNull(index)?.value?.let(ids::add) ?: true
        val newSong = songKey(page[index].title, page[index].artist)?.let(songs::add) ?: true
        newId && newSong
    }
}

/** Песня для сравнения: название и исполнитель без регистра и знаков; `null` — одного из них нет. */
private fun songKey(
    title: String?,
    artist: String?,
): String? {
    val song = title.orEmpty().lettersAndDigits()
    val by = artist.orEmpty().lettersAndDigits()
    return if (song.isEmpty() || by.isEmpty()) null else "$song\u0000$by"
}

private fun String.lettersAndDigits() = lowercase().filter(Char::isLetterOrDigit)

/** Трек радио как элемент очереди: то же, что у сетевого трека без альбома в списках (`LibraryTrack.toQueueItem()`). */
internal fun OnlineTrack.toRadioItem(id: TrackId) =
    QueueItem(
        source = AudioSource.Online(id.value),
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        albumOwner = artist,
    )
