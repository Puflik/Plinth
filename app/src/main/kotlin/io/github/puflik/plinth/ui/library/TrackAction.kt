package io.github.puflik.plinth.ui.library

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueAction
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem

/** Что сделать с треком списка (D1.2): касание — [PLAY], остальное — из меню долгого нажатия. */
enum class TrackAction {
    /** Играть список, в котором трек стоит, начиная с него; вручную добавленное остаётся. */
    PLAY,
    PLAY_NEXT,
    ADD_TO_QUEUE,

    /** Как [PLAY], но вручную добавленное пропадает. */
    REPLACE_QUEUE,
    ;

    /** Трек заиграет сразу — после действия открывается плеер. */
    val startsPlayback: Boolean get() = this == PLAY || this == REPLACE_QUEUE
}

/** Трек библиотеки как элемент очереди: очередь библиотеку не знает. */
fun LibraryTrack.toQueueItem() =
    QueueItem(
        source = AudioSource.LocalFile(uri),
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        albumOwner = albumOwner,
    )

/**
 * [action] над [track] из списка [tracks], который играет контекстом
 * [context]. Трека в списке нет (список успел обновиться) — играет он один.
 */
fun PlaybackController.act(
    action: TrackAction,
    context: QueueContext,
    tracks: List<LibraryTrack>,
    track: LibraryTrack,
) {
    when (action) {
        TrackAction.PLAY, TrackAction.REPLACE_QUEUE -> {
            val start = tracks.indexOf(track)
            val list = if (start >= 0) tracks else listOf(track)
            val items = list.map(LibraryTrack::toQueueItem)
            if (action == TrackAction.PLAY) {
                play(context, items, start.coerceAtLeast(0))
            } else {
                replace(context, items, start.coerceAtLeast(0))
            }
        }
        TrackAction.PLAY_NEXT -> perform(QueueAction.PLAY_NEXT, track.toQueueItem())
        TrackAction.ADD_TO_QUEUE -> perform(QueueAction.ADD_TO_QUEUE, track.toQueueItem())
    }
}
