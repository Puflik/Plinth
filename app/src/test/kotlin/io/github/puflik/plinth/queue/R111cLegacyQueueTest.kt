package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Ревью v0.2, №26 (Р1.11в): очередь, сохранённая 0.1.x, называет треки адресами
 * `content://media/…`, а фонотека ядра знает файлы по пути — без пути у трека нет ни
 * истории, ни сердца. При чтении адреса переводятся в пути; играли они и так.
 */
class R111cLegacyQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val directory by lazy { temp.root.resolve("queue") }
    private val file by lazy { FileQueueStore(directory, Dispatchers.Unconfined) }
    private val paths = FakePaths((1..5).associate { old(it) to path(it) })
    private val store by lazy { LegacyUriQueueStore(file, paths) }
    private val playedAt = Instant.fromEpochSeconds(1_700_000_000)

    /** Подставная система: знает адреса из [known] и помнит, о чём её спросили. */
    private class FakePaths(
        private val known: Map<String, String>,
    ) : MediaPaths {
        val asked = mutableListOf<Set<String>>()

        override suspend fun pathsOf(uris: Set<String>): Map<String, String> {
            asked += uris
            return known.filterKeys { it in uris }
        }
    }

    private fun old(id: Int) = "content://media/external/audio/media/$id"

    private fun path(id: Int) = "/storage/emulated/0/Music/Song $id.mp3"

    private fun item(
        address: String,
        title: String,
    ) = QueueItem(AudioSource.LocalFile(address), title, "Queen", "A Night at the Opera", 3.minutes, "Queen")

    /**
     * Очередь, в которой каждое место с файлами — контекст, играющий ручной трек и ручной блок —
     * называет файл через [address]; всё остальное одинаково.
     */
    private fun queueOf(address: (Int) -> String): PlaybackQueue =
        PlaybackQueue.EMPTY
            .withRepeat(RepeatMode.ONE)
            .play(
                QueueContext.Album("A Night at the Opera", "Queen"),
                (1..3).map { item(address(it), "Song $it") },
                start = 1,
            ).withShuffle(true, Random(5))
            .perform(QueueAction.ADD_TO_QUEUE, item(address(4), "Song 4"))
            .perform(QueueAction.ADD_TO_QUEUE, item(address(5), "Song 5"))
            .next()!!

    @Test
    fun `a queue of 0_1_x comes back with the paths of the library files`() =
        runTest {
            val saved = queueOf(::old)
            assertThat(saved.contextItems).isNotEmpty()
            assertThat(saved.playingManual).isNotNull()
            assertThat(saved.upNext).isNotEmpty()
            file.saveQueue(saved)
            file.savePosition(83.seconds)
            file.savePlayedAt(playedAt)

            assertThat(store.load()).isEqualTo(SavedQueue(queueOf(::path), 83.seconds, playedAt))
        }

    /** Адрес, которого система не знает (базу переиндексировали), остаётся как был — играл и будет играть. */
    @Test
    fun `an address the system does not know stays as it was`() =
        runTest {
            val known = FakePaths(mapOf(old(1) to path(1), old(4) to path(4)))
            file.saveQueue(queueOf(::old))

            val loaded = LegacyUriQueueStore(file, known).load()

            assertThat(loaded?.queue).isEqualTo(queueOf { if (it == 1 || it == 4) path(it) else old(it) })
        }

    @Test
    fun `a blank path is not a path`() =
        runTest {
            file.saveQueue(queueOf(::old))

            val loaded = LegacyUriQueueStore(file, FakePaths(mapOf(old(2) to " ", old(3) to ""))).load()

            assertThat(loaded?.queue).isEqualTo(queueOf(::old))
        }

    /** Одна очередь — один вопрос, а не по вопросу на трек: в очереди бывают тысячи треков. */
    @Test
    fun `the system is asked once for the whole queue without repeats`() =
        runTest {
            val twice = listOf(item(old(1), "One"), item(old(2), "Two"), item(old(1), "One again"))
            file.saveQueue(PlaybackQueue.EMPTY.play(QueueContext.Tracks, twice, start = 0))

            val loaded = store.load()

            assertThat(paths.asked).containsExactly(setOf(old(1), old(2)))
            assertThat(loaded?.queue?.contextItems?.map { (it.source as AudioSource.LocalFile).uri })
                .containsExactly(path(1), path(2), path(1))
                .inOrder()
        }

    /** Путь, чужой провайдер, поток и сетевой трек — переводить нечего, система не зовётся. */
    @Test
    fun `nothing to translate means nothing is asked`() =
        runTest {
            val other =
                listOf(
                    item("/storage/emulated/0/Music/a.mp3", "Plain path"),
                    item("content://com.android.providers.media.documents/document/audio%3A5", "Documents"),
                    QueueItem(AudioSource.Remote("https://example.test/a.flac"), "Stream"),
                    QueueItem(AudioSource.Online("0192f7c4-0000-7000-8000-00000000000a"), "Online"),
                )
            val queue = PlaybackQueue.EMPTY.play(QueueContext.Liked, other, start = 0)
            file.saveQueue(queue)

            assertThat(store.load()?.queue).isEqualTo(queue)
            assertThat(paths.asked).isEmpty()
        }

    @Test
    fun `nothing saved is nothing and nothing is asked`() =
        runTest {
            assertThat(store.load()).isNull()
            assertThat(paths.asked).isEmpty()
        }

    /** Перевод — только в памяти: файл не переписывается, повторное чтение даёт то же. */
    @Test
    fun `reading does not rewrite the files and gives the same twice`() =
        runTest {
            file.saveQueue(queueOf(::old))
            file.savePosition(83.seconds)
            val before = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

            val first = store.load()
            val second = store.load()

            assertThat(directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
                .isEqualTo(before)
            assertThat(second).isEqualTo(first)
        }

    /** Запись идёт в хранилище как есть: следующая очередь уже сохранится с путями. */
    @Test
    fun `writes go straight to the store`() =
        runTest {
            val queue = queueOf(::path)

            store.saveQueue(queue)
            store.savePosition(5.seconds)
            store.savePlayedAt(playedAt)

            assertThat(file.load()).isEqualTo(SavedQueue(queue, 5.seconds, playedAt))
            assertThat(paths.asked).isEmpty()
        }
}
