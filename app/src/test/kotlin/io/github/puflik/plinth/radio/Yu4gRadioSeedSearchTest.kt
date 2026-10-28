package io.github.puflik.plinth.radio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ю4г (`docs/work/yu4g.md`): затравку радио ищут только у YouTube. Запрос «исполнитель название» не уходит ни в
 * Internet Archive, ни к другому провайдеру, и запуск не ждёт чужих ответов: он просит у репозитория секцию одного
 * провайдера (`searchIn`), а не поиск по всем.
 *
 * `OnlineRepository.searchIn` — новый API (сигнатура в файле задачи): пока его нет, тесты приложения не собираются.
 * Двойник `RadioOnline` отдаёт секцию названного провайдера из `sections` и записывает, кого спросили.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4gRadioSeedSearchTest {
    @Test
    fun `the seed of a file of the disk is searched at youtube alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            // Секция Internet Archive с «песней» — приманка: кто возьмёт первый трек из первой секции, возьмёт её.
            rig.online.sections = listOf(archiveSection(), rig.youtubeSection("vidX", "vidY"))
            rig.script("vidX", null, rig.page(listOf(rig.track("v1", "One"), rig.track("v2", "Two"))))
            val file = QueueItem(AudioSource.LocalFile("/music/b.flac"), "Bohemian Rhapsody", "Queen")

            rig.starter.start(RadioSeed(file, TrackId("local-1")))

            assertThat(rig.online.searchedIn).containsExactly("youtube")
            assertThat(rig.online.wide).isEmpty()
            assertThat(rig.online.queries).containsExactly("Queen Bohemian Rhapsody")
            val queue = rig.controller.queue.value
            assertThat(queue.context).isEqualTo(QueueContext.Radio("youtube", "vidX", "Bohemian Rhapsody"))
            assertThat(queue.contextItems.map { it.title }).containsExactly("Bohemian Rhapsody", "One", "Two").inOrder()
            assertThat(rig.calls).containsExactly("vidX" to null)
            assertThat(rig.told).isEmpty()
        }

    @Test
    fun `a track of the internet archive and a track with no artist are searched at youtube alone too`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val concert = rig.track("concert", "Opening", "Plinth Band")
            val (id) = rig.repository.add("archive.org", listOf(concert))
            rig.online.sections = listOf(archiveSection(), rig.youtubeSection("vidX"))
            rig.script("vidX", null, rig.page(listOf(rig.track("v1", "One"))))

            rig.starter.start(RadioSeed(rig.item(id, concert), id))
            rig.starter.start(RadioSeed(QueueItem(AudioSource.LocalFile("/music/c.mp3"), "Untitled Demo"), null))

            assertThat(rig.online.searchedIn).containsExactly("youtube", "youtube")
            assertThat(rig.online.wide).isEmpty()
            assertThat(rig.online.queries).containsExactly("Plinth Band Opening", "Untitled Demo").inOrder()
            assertThat((rig.controller.queue.value.context as QueueContext.Radio).seed).isEqualTo("vidX")
        }

    @Test
    fun `the answer of youtube alone decides how the search ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val file = RadioSeed(QueueItem(AudioSource.LocalFile("/music/b.flac"), "Song", "Band"), null)

            // У Internet Archive есть что показать, у YouTube нет сети: говорится про сеть, радио не начинается.
            val offline = OnlineSection("youtube", emptyList(), OnlineProblem.NO_NETWORK)
            rig.online.sections = listOf(archiveSection(), offline)
            rig.starter.start(file)
            // У Internet Archive есть «песня», секции YouTube нет вовсе: «не нашлось», а не затравка из архива.
            rig.online.sections = listOf(archiveSection())
            rig.starter.start(file)

            assertThat(rig.told).containsExactly(RadioProblem.NO_NETWORK, RadioProblem.NOT_FOUND).inOrder()
            assertThat(rig.online.searchedIn).containsExactly("youtube", "youtube")
            assertThat(rig.calls).isEmpty()
            assertThat(rig.controller.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        }

    @Test
    fun `a youtube track of the catalog still asks nobody`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            rig.script("vidA/251", null, rig.page(listOf(rig.track("v1", "One"))))

            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            assertThat(rig.online.searchedIn).isEmpty()
            assertThat(rig.online.wide).isEmpty()
            assertThat(rig.calls.first()).isEqualTo("vidA/251" to null)
        }

    /** Секция Internet Archive с треком, какого запуск брать не должен. */
    private fun archiveSection() =
        OnlineSection(
            provider = "archive.org",
            results = listOf(OnlineResult("ia-song", OnlineKind.TRACK, "Title of ia-song", "Artist of ia-song")),
        )

    private suspend fun TestScope.rig() = RadioRig(this).also { it.enable() }
}
