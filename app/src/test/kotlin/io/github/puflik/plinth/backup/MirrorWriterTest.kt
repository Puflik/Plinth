package io.github.puflik.plinth.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Писатель копии (C4, ответ автора): файл пишется через ~5 с после
 * последней правки — серия правок даёт одну запись — и когда приложение
 * уходит в фон.
 */
class MirrorWriterTest {
    private val mirror = FakeJournalMirror().apply { scan(listOf(SONG, OTHER)) }
    private val folder = FakeMirrorFolder()
    private val settings = FakeMirrorSettings(TREE)

    @Test
    fun `a burst of edits is one write after the pause`() =
        runTest {
            writer().start()
            runCurrent()

            mirror.like(SONG)
            advanceTimeBy(3.seconds)
            mirror.like(OTHER)
            advanceTimeBy(4.seconds)
            assertThat(folder.writes).isEqualTo(0)
            advanceTimeBy(2.seconds)

            assertThat(folder.writes).isEqualTo(1)
            assertThat(
                folder
                    .files(TREE)
                    .single()
                    .content
                    .decodeToString(),
            ).contains("like")
        }

    /** Папка, куда писать нельзя, видна человеку, а не только логу (ревью v0.2, №3). */
    @Test
    fun `a refused write is reported until the next one succeeds`() =
        runTest {
            val writer = writer().also { it.start() }
            runCurrent()
            folder.failNextWrite = true
            mirror.like(SONG)
            advanceTimeBy(6.seconds)

            assertThat(writer.status.value.failing).isTrue()
            assertThat(writer.status.value.lastWrittenAt).isNull()

            mirror.like(OTHER)
            advanceTimeBy(6.seconds)

            assertThat(writer.status.value.failing).isFalse()
            assertThat(writer.status.value.lastWrittenAt).isNotNull()
        }

    @Test
    fun `nothing is written while nothing changes`() =
        runTest {
            writer().start()
            advanceTimeBy(1.seconds * 60)

            assertThat(folder.writes).isEqualTo(0)
        }

    @Test
    fun `going to the background writes the pending edit at once`() =
        runTest {
            val writer = writer().also { it.start() }
            runCurrent()
            mirror.like(SONG)
            runCurrent()

            writer.flush()
            runCurrent()

            assertThat(folder.writes).isEqualTo(1)
            advanceTimeBy(10.seconds)
            assertThat(folder.writes).isEqualTo(1)
        }

    /** Первый уход в фон в процессе пишет копию: прошлый процесс мог не успеть. */
    @Test
    fun `background writes once per process and then only after edits`() =
        runTest {
            val writer = writer().also { it.start() }
            runCurrent()

            writer.flush()
            runCurrent()
            writer.flush()
            runCurrent()

            assertThat(folder.writes).isEqualTo(1)
        }

    @Test
    fun `without a folder nothing is written`() =
        runTest {
            val writer = writer(FakeMirrorSettings(folder = null)).also { it.start() }
            runCurrent()
            mirror.like(SONG)
            advanceTimeBy(10.seconds)

            writer.flush()
            runCurrent()

            assertThat(folder.writes).isEqualTo(0)
            assertThat(mirror.copies).isEqualTo(0)
        }

    @Test
    fun `a failed write is retried when the app goes to the background`() =
        runTest {
            val writer = writer().also { it.start() }
            runCurrent()
            folder.failNextWrite = true
            mirror.like(SONG)
            advanceTimeBy(10.seconds)
            assertThat(folder.files(TREE)).isEmpty()

            writer.flush()
            runCurrent()

            assertThat(folder.files(TREE)).hasSize(1)
        }

    @Test
    fun `a failed copy is retried too`() =
        runTest {
            val writer = writer().also { it.start() }
            runCurrent()
            mirror.failNextCopy = true
            mirror.like(SONG)
            advanceTimeBy(10.seconds)

            writer.flush()
            runCurrent()

            assertThat(folder.writes).isEqualTo(1)
        }

    /** Папку только что выбрали или данные восстановили — копия сразу, без правок. */
    @Test
    fun `write now does not wait`() =
        runTest {
            val writer = writer().also { it.start() }

            writer.writeNow()

            assertThat(folder.files(TREE).single().name).endsWith(".journal")
        }

    private fun TestScope.writer(settings: MirrorSettings = this@MirrorWriterTest.settings) =
        MirrorWriter(mirror, folder, settings, backgroundScope)

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        const val SONG = "/storage/emulated/0/Music/a.mp3"
        const val OTHER = "/storage/emulated/0/Music/b.mp3"
    }
}
