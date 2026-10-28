package io.github.puflik.plinth.ui.library

import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.di.ApplicationScope
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.history.trackOf
import io.github.puflik.plinth.library.PlaylistRepository
import io.github.puflik.plinth.library.UserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import io.github.puflik.plinth.radio.RadioLauncher
import io.github.puflik.plinth.radio.RadioSeed
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
        private val launcher: RadioLauncher = RadioLauncher.None,
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
                TrackAction.RADIO -> launcher.start(RadioSeed(track.toQueueItem(), track.id))
                else -> playback.act(action, context, tracks, track, at)
            }
        }

        /**
         * «Радио» из плеера (Ю4в): от трека, что играет. Плеер знает элемент очереди, а не ID трека, — ID берётся по
         * источнику: файл фонотеки — по пути, сетевой трек — свой; файл не из фонотеки и адрес ID не имеют.
         */
        fun radio(item: QueueItem) {
            scope.launch { launcher.start(RadioSeed(item, userData.trackOf(item.source))) }
        }

        /** «В плейлист» (D4b): [track] — в конец [playlist]. */
        fun addToPlaylist(
            track: LibraryTrack,
            playlist: PlaylistId,
        ) = addToPlaylist(track.id, playlist)

        /** То же по ID трека: плеер знает только его (Р6). */
        fun addToPlaylist(
            track: TrackId,
            playlist: PlaylistId,
        ) {
            scope.launch { playlists.add(playlist, track) }
        }

        /** «В плейлист» → «Новый плейлист»: создаёт плейлист [name] с [track]; пустое имя — ничего. */
        fun addToNewPlaylist(
            track: LibraryTrack,
            name: String,
        ) = addToNewPlaylist(track.id, name)

        /** То же по ID трека: плеер знает только его (Р6). */
        fun addToNewPlaylist(
            track: TrackId,
            name: String,
        ) {
            val clean = playlistName(name) ?: return
            scope.launch { playlists.create(clean)?.let { playlists.add(it, track) } }
        }

        /**
         * То же по источнику: плеер знает, что играет, а не ID трека, — ID берётся в момент
         * записи, а не выбора (Р9а): между ними перепривязка могла перевести трек на другой ID.
         * Файл не из фонотеки и готовый адрес треком не становятся — ничего.
         */
        fun addToPlaylist(
            source: AudioSource,
            playlist: PlaylistId,
        ) {
            scope.launch { userData.trackOf(source)?.let { playlists.add(playlist, it) } }
        }

        /** «В плейлист» → «Новый плейлист» по источнику: ID берётся в момент записи; трека нет — нет и плейлиста. */
        fun addToNewPlaylist(
            source: AudioSource,
            name: String,
        ) {
            val clean = playlistName(name) ?: return
            scope.launch {
                userData.trackOf(source)?.let { track -> playlists.create(clean)?.let { playlists.add(it, track) } }
            }
        }
    }
