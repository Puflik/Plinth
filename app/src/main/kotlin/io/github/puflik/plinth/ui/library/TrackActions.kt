package io.github.puflik.plinth.ui.library

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.playlists.playlistName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Действия с треком списка (D1.2, D4) — одни на все экраны со списками:
 * воспроизведение и очередь идут в [PlaybackController], лайк и плейлисты —
 * в журнал через [UserDataRepository] и [PlaylistRepository]. Журнал пишется
 * в [scope] приложения: уход с экрана записи не отменяет.
 */
class TrackActions
    @Inject
    constructor(
        private val playback: PlaybackController,
        private val userData: UserDataRepository,
        private val playlists: PlaylistRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /**
         * [action] над [track] из списка [tracks], который играет контекстом
         * [context]; [at] — место трека в списке, если трек в нём не один раз.
         */
        fun act(
            action: TrackAction,
            context: QueueContext,
            tracks: List<LibraryTrack>,
            track: LibraryTrack,
            at: Int = tracks.indexOf(track),
        ) {
            when (action) {
                TrackAction.LIKE, TrackAction.UNLIKE ->
                    scope.launch { userData.setLiked(track.id, liked = action == TrackAction.LIKE) }
                else -> playback.act(action, context, tracks, track, at)
            }
        }

        /** «В плейлист» (D4b): [track] — в конец [playlist]. */
        fun addToPlaylist(
            track: LibraryTrack,
            playlist: PlaylistId,
        ) {
            scope.launch { playlists.add(playlist, track.id) }
        }

        /** «В плейлист» → «Новый плейлист»: создаёт плейлист [name] с [track]; пустое имя — ничего. */
        fun addToNewPlaylist(
            track: LibraryTrack,
            name: String,
        ) {
            val clean = playlistName(name) ?: return
            scope.launch { playlists.create(clean)?.let { playlists.add(it, track.id) } }
        }
    }
