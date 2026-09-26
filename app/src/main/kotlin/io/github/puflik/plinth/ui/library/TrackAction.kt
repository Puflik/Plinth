package io.github.puflik.plinth.ui.library

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueAction
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem

/** Что сделать с треком списка (D1.2, D4): касание — [PLAY], остальное — из меню долгого нажатия. */
enum class TrackAction {
    /** Играть список, в котором трек стоит, начиная с него; вручную добавленное остаётся. */
    PLAY,
    PLAY_NEXT,
    ADD_TO_QUEUE,

    /** Как [PLAY], но вручную добавленное пропадает. */
    REPLACE_QUEUE,

    /** Поставить лайк; снять — [UNLIKE]. Очередь не трогают. */
    LIKE,
    UNLIKE,

    /** В плейлист (D4b): какой — выбирают в меню трека, пишет [TrackActions]. Очередь не трогают. */
    ADD_TO_PLAYLIST,
    ;

    /** Трек заиграет сразу — после действия открывается плеер. */
    val startsPlayback: Boolean get() = this == PLAY || this == REPLACE_QUEUE

    /** Трек встал в очередь — об этом коротко говорится. */
    val queues: Boolean get() = this == PLAY_NEXT || this == ADD_TO_QUEUE
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
 * [context]; [at] — место трека в списке: в плейлисте один трек бывает
 * дважды. Трека на этом месте нет (список успел обновиться) — играет он один.
 * Лайк и плейлисты — не дело очереди: их пишет [TrackActions].
 */
fun PlaybackController.act(
    action: TrackAction,
    context: QueueContext,
    tracks: List<LibraryTrack>,
    track: LibraryTrack,
    at: Int = tracks.indexOf(track),
) {
    when (action) {
        TrackAction.PLAY, TrackAction.REPLACE_QUEUE -> {
            val found = tracks.getOrNull(at) == track
            val items = (if (found) tracks else listOf(track)).map(LibraryTrack::toQueueItem)
            val start = if (found) at else 0
            if (action == TrackAction.PLAY) play(context, items, start) else replace(context, items, start)
        }
        TrackAction.PLAY_NEXT -> perform(QueueAction.PLAY_NEXT, track.toQueueItem())
        TrackAction.ADD_TO_QUEUE -> perform(QueueAction.ADD_TO_QUEUE, track.toQueueItem())
        TrackAction.LIKE, TrackAction.UNLIKE, TrackAction.ADD_TO_PLAYLIST -> Unit
    }
}
