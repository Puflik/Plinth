package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Контракт `UserDataRepository` (D4) — требования к любому хранилищу лайков и
 * истории. `FakeUserDataRepository` проходит его на JVM, ядро на Rust
 * (`CoreUserDataRepository`) — на эмуляторе: экраны и запись истории
 * тестируются на фейке.
 *
 * Треки появляются в фонотеке как после скана — файлами ([seed]);
 * идентификаторы выдаёт хранилище.
 *
 * Имена тестов — через подчёркивание: класс собирается и в APK для эмулятора.
 */
abstract class UserDataRepositoryContractTest {
    /** Пустое хранилище; закрывает его [closeRepository]. */
    protected abstract fun createRepository(): UserDataRepository

    protected open fun closeRepository(repository: UserDataRepository) = Unit

    /** Кладёт в фонотеку файлы [paths] — как их записал бы скан. */
    protected abstract suspend fun seed(
        repository: UserDataRepository,
        paths: List<String>,
    )

    /** Предел ожидания. */
    protected open val timeout: Duration = 5.seconds

    @Test
    fun a_file_outside_the_library_has_no_track() =
        contract { repository ->
            assertThat(repository.trackAt(SONG)).isNull()
        }

    @Test
    fun a_library_file_leads_to_its_track() =
        contract { repository ->
            seed(repository, listOf(SONG, OTHER))

            val song = repository.trackAt(SONG)
            val other = repository.trackAt(OTHER)

            assertThat(song).isNotNull()
            assertThat(other).isNotNull()
            assertThat(song).isNotEqualTo(other)
            assertThat(repository.trackAt(SONG)).isEqualTo(song)
        }

    @Test
    fun a_like_is_set_and_taken_back() =
        contract { repository ->
            seed(repository, listOf(SONG, OTHER))
            val song = checkNotNull(repository.trackAt(SONG))
            val other = checkNotNull(repository.trackAt(OTHER))
            val before = repository.liked(song).first()

            repository.setLiked(song, true)
            val liked = repository.liked(song).first { it }
            repository.setLiked(song, false)
            val unliked = repository.liked(song).first { !it }

            assertThat(listOf(before, liked, unliked)).containsExactly(false, true, false).inOrder()
            assertThat(repository.liked(other).first()).isFalse()
        }

    /**
     * Лайк, поставленный после подписки, приходит в ту же подписку: сердце
     * на плеере слушает поток, а не перечитывает его.
     */
    @Test
    fun a_subscribed_like_follows_changes() =
        contract { repository ->
            seed(repository, listOf(SONG))
            val song = checkNotNull(repository.trackAt(SONG))
            val seen = MutableStateFlow<Boolean?>(null)
            val subscription = launch { repository.liked(song).collect { seen.value = it } }
            seen.first { it == false }

            repository.setLiked(song, true)

            assertThat(seen.first { it == true }).isTrue()
            subscription.cancel()
        }

    @Test
    fun recorded_plays_are_in_the_history_newest_first() =
        contract { repository ->
            seed(repository, listOf(SONG, OTHER))
            val song = checkNotNull(repository.trackAt(SONG))
            val other = checkNotNull(repository.trackAt(OTHER))

            repository.recordPlay(play(song, at = 1, listened = 3.minutes))
            repository.recordPlay(play(other, at = 2, listened = 10.seconds))

            val history = repository.recentPlays(limit = 10)
            assertThat(history.map { it.track to it.listened })
                .containsExactly(other to 10.seconds, song to 3.minutes)
                .inOrder()
            assertThat(history.last().trackLength).isEqualTo(TRACK_LENGTH)
            assertThat(repository.recentPlays(limit = 1).map { it.track }).containsExactly(other)
        }

    private fun play(
        track: TrackId,
        at: Long,
        listened: Duration,
    ) = NewPlay(
        track = track,
        startedAt = Instant.fromEpochMilliseconds(START + at * APART_MS),
        utcOffsetMinutes = UTC_OFFSET,
        listened = listened,
        trackLength = TRACK_LENGTH,
    )

    private fun contract(body: suspend CoroutineScope.(UserDataRepository) -> Unit) =
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
        const val START = 1_790_000_000_000
        const val APART_MS = 60_000L
        const val UTC_OFFSET = 300
        val TRACK_LENGTH = 4.minutes
    }
}
