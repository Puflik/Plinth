package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.PlaylistTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Контракт `PlaylistRepository` (D4b) — требования к любому хранилищу
 * плейлистов. `FakePlaylistRepository` проходит его на JVM, ядро на Rust
 * (`CorePlaylistRepository`) — на эмуляторе: экраны тестируются на фейке.
 *
 * Треки появляются в фонотеке как после скана — файлами ([seed]), пропавшие
 * файлы скрываются ([hide]); идентификаторы выдаёт хранилище.
 *
 * Имена тестов — через подчёркивание: класс собирается и в APK для эмулятора.
 */
abstract class PlaylistRepositoryContractTest {
    /** Пустое хранилище; закрывает его [closeRepository]. */
    protected abstract fun createRepository(): PlaylistRepository

    protected open fun closeRepository(repository: PlaylistRepository) = Unit

    /** Кладёт в фонотеку файлы [paths] — как их записал бы скан; треки — по порядку файлов. */
    protected abstract suspend fun seed(
        repository: PlaylistRepository,
        paths: List<String>,
    ): List<TrackId>

    /** Файлы [paths] пропали — как их отметил бы скан. */
    protected abstract suspend fun hide(
        repository: PlaylistRepository,
        paths: List<String>,
    )

    /** Предел ожидания. */
    protected open val timeout: Duration = 5.seconds

    @Test
    fun a_new_repository_has_no_playlists() =
        contract { repository ->
            assertThat(repository.playlists().first()).isEmpty()
        }

    /** По имени, как списки экранов: регистр не важен, числа — по значению. */
    @Test
    fun playlists_go_by_name_like_the_screens() =
        contract { repository ->
            for (name in listOf("mix 10", "Road", "Mix 2", "ambient")) repository.create(name)

            val names = repository.playlists().first().map { it.name }

            assertThat(names).containsExactly("ambient", "Mix 2", "mix 10", "Road").inOrder()
        }

    @Test
    fun a_playlist_is_renamed_and_deleted_with_its_tracks() =
        contract { repository ->
            val (song) = seed(repository, listOf(SONG))
            val mix = checkNotNull(repository.create("Mix"))
            repository.add(mix, song)

            repository.rename(mix, "Road")
            val renamed = repository.playlists().first().single()
            repository.delete(mix)

            assertThat(renamed.id).isEqualTo(mix)
            assertThat(renamed.name).isEqualTo("Road")
            assertThat(repository.playlists().first()).isEmpty()
            assertThat(repository.tracks(mix).first()).isEmpty()
        }

    /** Порядок — плейлиста; повтор трека — две строки со своими записями. */
    @Test
    fun tracks_keep_the_playlist_order_and_may_repeat() =
        contract { repository ->
            val (a, b) = seed(repository, listOf(SONG, OTHER))
            val mix = checkNotNull(repository.create("Mix"))

            for (track in listOf(b, a, b)) repository.add(mix, track)

            val tracks = repository.tracks(mix).first()
            assertThat(tracks.map { it.track.id }).containsExactly(b, a, b).inOrder()
            assertThat(tracks.map { it.track.uri }).containsExactly(OTHER, SONG, OTHER).inOrder()
            assertThat(tracks.map(PlaylistTrack::entry)).containsNoDuplicates()
        }

    @Test
    fun a_moved_entry_lands_where_the_screen_holds_it() =
        contract { repository ->
            val (a, b, c) = seed(repository, listOf(SONG, OTHER, THIRD))
            val mix = filled(repository, listOf(a, b, c))

            repository.move(mix, entryOf(repository, mix, a), to = 2)
            val down = repository.trackIds(mix)
            repository.move(mix, entryOf(repository, mix, c), to = 0)

            assertThat(down).containsExactly(b, c, a).inOrder()
            assertThat(repository.trackIds(mix)).containsExactly(c, b, a).inOrder()
        }

    /**
     * Пропавший файл скрыт, но его запись на месте, а ядро считает место среди
     * всех записей. Индекс экрана — среди видимых: запись встаёт туда, куда её
     * опустили, а не на тот же номер среди всех.
     */
    @Test
    fun moves_count_among_visible_tracks_only() =
        contract { repository ->
            val (a, b, c) = seed(repository, listOf(SONG, OTHER, THIRD))
            val (hidden) = seed(repository, listOf(GONE))
            val mix = filled(repository, listOf(a, hidden, b, c))
            hide(repository, listOf(GONE))
            repository.tracks(mix).first { rows -> rows.none { it.track.id == hidden } }

            repository.move(mix, entryOf(repository, mix, a), to = 1)
            val down = repository.trackIds(mix)
            repository.move(mix, entryOf(repository, mix, c), to = 0)

            assertThat(down).containsExactly(b, a, c).inOrder()
            assertThat(repository.trackIds(mix)).containsExactly(c, b, a).inOrder()
        }

    @Test
    fun a_removed_entry_leaves_the_same_track_elsewhere() =
        contract { repository ->
            val (a, b) = seed(repository, listOf(SONG, OTHER))
            val mix = filled(repository, listOf(a, b, a))
            val (first, _, last) = repository.tracks(mix).first()

            repository.remove(first.entry)

            val left = repository.tracks(mix).first()
            assertThat(left.map { it.track.id }).containsExactly(b, a).inOrder()
            assertThat(left.last().entry).isEqualTo(last.entry)
        }

    /**
     * Правка после подписки приходит в ту же подписку: экран слушает поток, а
     * не перечитывает его. Холодный `first` здесь ничего бы не проверил —
     * каждый вызов читал бы ядро заново.
     */
    @Test
    fun subscribed_lists_follow_changes() =
        contract { repository ->
            val (song) = seed(repository, listOf(SONG))
            val mix = checkNotNull(repository.create("Mix"))
            val names = MutableStateFlow<List<String>?>(null)
            val tracks = MutableStateFlow<List<TrackId>?>(null)
            val subscriptions =
                listOf(
                    launch { repository.playlists().collect { list -> names.value = list.map { it.name } } },
                    launch { repository.tracks(mix).collect { list -> tracks.value = list.map { it.track.id } } },
                )
            names.first { it == listOf("Mix") }
            tracks.first { it == emptyList<TrackId>() }

            repository.add(mix, song)
            repository.rename(mix, "Road")

            assertThat(tracks.first { it == listOf(song) }).containsExactly(song)
            assertThat(names.first { it == listOf("Road") }).containsExactly("Road")
            subscriptions.forEach { it.cancel() }
        }

    /** Удалённого плейлиста нет: правка ничего не делает и не бросает. */
    @Test
    fun a_deleted_playlist_takes_no_edits() =
        contract { repository ->
            val (song) = seed(repository, listOf(SONG))
            val ghost = checkNotNull(repository.create("Ghost"))
            repository.delete(ghost)

            repository.add(ghost, song)
            repository.rename(ghost, "Back")

            assertThat(repository.playlists().first()).isEmpty()
            assertThat(repository.tracks(ghost).first()).isEmpty()
        }

    private suspend fun filled(
        repository: PlaylistRepository,
        tracks: List<TrackId>,
    ): PlaylistId {
        val mix = checkNotNull(repository.create("Mix"))
        for (track in tracks) repository.add(mix, track)
        return mix
    }

    private suspend fun entryOf(
        repository: PlaylistRepository,
        playlist: PlaylistId,
        track: TrackId,
    ) = repository
        .tracks(playlist)
        .first()
        .first { it.track.id == track }
        .entry

    private suspend fun PlaylistRepository.trackIds(playlist: PlaylistId): List<TrackId> =
        tracks(playlist).first().map { it.track.id }

    private fun contract(body: suspend CoroutineScope.(PlaylistRepository) -> Unit) =
        runBlocking {
            val repository = createRepository()
            try {
                withTimeout(timeout) { this.body(repository) }
            } finally {
                closeRepository(repository)
            }
        }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val OTHER = "/storage/emulated/0/Music/other.mp3"
        const val THIRD = "/storage/emulated/0/Music/third.mp3"
        const val GONE = "/storage/emulated/0/Music/gone.mp3"
    }
}
