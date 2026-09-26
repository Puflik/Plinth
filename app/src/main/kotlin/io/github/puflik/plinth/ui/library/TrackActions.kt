package io.github.puflik.plinth.ui.library

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Действия с треком списка (D1.2, D4a) — одни на все экраны со списками:
 * воспроизведение и очередь идут в [PlaybackController], лайк — в журнал
 * через [UserDataRepository]. Лайк пишется в [scope] приложения: уход с
 * экрана его не отменяет.
 */
class TrackActions
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val userData: UserDataRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /** [action] над [track] из списка [tracks], который играет контекстом [context]. */
        fun act(
            action: TrackAction,
            context: QueueContext,
            tracks: List<LibraryTrack>,
            track: LibraryTrack,
        ) {
            when (action) {
                TrackAction.LIKE, TrackAction.UNLIKE ->
                    scope.launch { userData.setLiked(track.id, liked = action == TrackAction.LIKE) }
                else -> playback.act(action, context, tracks, track)
            }
        }
    }
