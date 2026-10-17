package io.github.puflik.plinth.queue

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.PlaylistId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Ревью v0.2, №27 (Р1.11в): строка длиннее 64 КБ (имя плейлиста, запрос) не останавливает
 * сохранение очереди, а очередь, которая не записалась, не получает чужую позицию.
 */
class R111cQueueWriteTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val directory by lazy { temp.root.resolve("queue") }
    private val store by lazy { FileQueueStore(directory, Dispatchers.Unconfined) }
    private val old = PlaybackQueue.EMPTY.play(QueueContext.Tracks, (0 until 4).map { track("old-$it") }, start = 1)
    private val new = PlaybackQueue.EMPTY.play(QueueContext.Tracks, (0 until 4).map { track("new-$it") }, start = 2)

    private fun track(
        name: String,
        title: String? = name,
        artist: String? = "Queen",
        album: String? = "Opera",
        owner: String? = "Queen",
    ) = QueueItem(AudioSource.LocalFile("/storage/emulated/0/Music/$name.mp3"), title, artist, album, 3.minutes, owner)

    private fun contextQueue(context: QueueContext) =
        PlaybackQueue.EMPTY.play(context, listOf(track("a"), track("b")), start = 0)

    private fun itemQueue(item: QueueItem) =
        PlaybackQueue.EMPTY.play(QueueContext.Tracks, listOf(track("a"), item), start = 1)

    /** Все места, куда в файле ложится переменная строка: контекст, поля элемента, адрес источника. */
    private val places: Map<String, (String) -> PlaybackQueue> =
        mapOf(
            "search query" to { text -> contextQueue(QueueContext.Search(text)) },
            "album title" to { text -> contextQueue(QueueContext.Album(text, "Queen")) },
            "album artist" to { text -> contextQueue(QueueContext.Album("Demos", text)) },
            "folder" to { text -> contextQueue(QueueContext.Folder(text)) },
            "artist" to { text -> contextQueue(QueueContext.Artist(text)) },
            "playlist id" to { text -> contextQueue(QueueContext.Playlist(PlaylistId(text), "Road")) },
            "playlist name" to { text -> contextQueue(QueueContext.Playlist(PlaylistId("playlist-1"), text)) },
            "title" to { text -> itemQueue(track("b", title = text)) },
            "artist of an item" to { text -> itemQueue(track("b", artist = text)) },
            "album of an item" to { text -> itemQueue(track("b", album = text)) },
            "album owner" to { text -> itemQueue(track("b", owner = text)) },
            "file address" to { text -> itemQueue(QueueItem(AudioSource.LocalFile(text), "b")) },
            "stream address" to { text -> itemQueue(QueueItem(AudioSource.Remote(text), "b")) },
            "online track" to { text -> itemQueue(QueueItem(AudioSource.Online(text), "b")) },
        )

    @Test
    fun `a string over 64 KB is cut to what fits in every place it can stand`() =
        runTest {
            val refused =
                places.filter { (_, queueWith) ->
                    store.saveQueue(queueWith("x".repeat(70_000)))
                    store.load()?.queue != queueWith("x".repeat(65_535))
                }

            assertThat(refused.keys).isEmpty()
        }

    /** Кириллица — два байта на символ: помещается 32 767 символов (65 534 байта), следующий дал бы 65 536. */
    @Test
    fun `the cut keeps the longest prefix that fits`() =
        runTest {
            store.saveQueue(contextQueue(QueueContext.Playlist(PlaylistId("playlist-1"), "я".repeat(40_000))))
            store.savePosition(5.seconds)

            assertThat(store.load())
                .isEqualTo(
                    SavedQueue(
                        contextQueue(QueueContext.Playlist(PlaylistId("playlist-1"), "я".repeat(32_767))),
                        5.seconds,
                    ),
                )
        }

    /** `writeUTF` кодирует `\u0000` двумя байтами: считать надо модифицированный UTF-8, а не обычный. */
    @Test
    fun `a null character counts as two bytes`() =
        runTest {
            store.saveQueue(contextQueue(QueueContext.Search("\u0000".repeat(40_000))))

            assertThat(store.load()?.queue).isEqualTo(contextQueue(QueueContext.Search("\u0000".repeat(32_767))))
        }

    /** 😀 — две половины по три байта: первая ещё помещается ровно в границу, но одна она — не символ. */
    @Test
    fun `the cut never leaves half of a surrogate pair`() =
        runTest {
            store.saveQueue(contextQueue(QueueContext.Search("a".repeat(65_532) + "😀")))

            assertThat(store.load()?.queue).isEqualTo(contextQueue(QueueContext.Search("a".repeat(65_532))))
        }

    /** Охранный: строка ровно в границу и всё прежнее не меняются ни на байт. */
    @Test
    fun `a string that fits is kept whole`() =
        runTest {
            val queue = contextQueue(QueueContext.Search("x".repeat(65_535)))
            store.saveQueue(queue)

            assertThat(store.load()?.queue).isEqualTo(queue)
        }

    /** Сценарий находки: запущен плейлист с огромным именем — после перезапуска встаёт он, а не прежняя очередь. */
    @Test
    fun `the new queue takes the place of the old one and brings its position`() =
        runTest {
            store.saveQueue(old)
            store.savePosition(83.seconds)
            val long = contextQueue(QueueContext.Playlist(PlaylistId("playlist-1"), "x".repeat(70_000)))

            store.saveQueue(long)
            store.savePosition(5.seconds)

            assertThat(store.load()?.position).isEqualTo(5.seconds)
            assertThat(store.load()?.queue?.context)
                .isEqualTo(QueueContext.Playlist(PlaylistId("playlist-1"), "x".repeat(65_535)))
        }

    /**
     * Новую очередь не записать (временный файл не создать — как на полном диске): прежняя остаётся
     * на диске, и позиция новой очереди ей не принадлежит.
     */
    @Test
    fun `a queue that could not be written does not take the position of the next one`() =
        runTest {
            store.saveQueue(old)
            store.savePosition(83.seconds)
            directory.resolve("queue.bin.tmp").mkdirs()

            store.saveQueue(new)
            store.savePosition(90.seconds)

            assertThat(store.load()).isEqualTo(SavedQueue(old, 83.seconds))
        }

    @Test
    fun `positions are written again once a queue is written`() =
        runTest {
            store.saveQueue(old)
            store.savePosition(83.seconds)
            directory.resolve("queue.bin.tmp").mkdirs()
            store.saveQueue(new)
            store.savePosition(90.seconds)
            directory.resolve("queue.bin.tmp").delete()

            store.saveQueue(new)
            store.savePosition(7.seconds)

            assertThat(store.load()).isEqualTo(SavedQueue(new, 7.seconds))
        }

    /** Очередь записалась, а позицию `0` записать не вышло: старая позиция чужой очереди не остаётся. */
    @Test
    fun `a new queue whose position could not be reset reads from the start`() =
        runTest {
            store.saveQueue(old)
            store.savePosition(83.seconds)
            directory.resolve("position.bin.tmp").mkdirs()

            store.saveQueue(new)

            assertThat(store.load()).isEqualTo(SavedQueue(new, Duration.ZERO))
        }
}
