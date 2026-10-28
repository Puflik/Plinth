package io.github.puflik.plinth.radio

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.core.RadioProblem
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.queue.PlaybackQueue
import io.github.puflik.plinth.queue.QueueAction
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.queue.QueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Пункт «Радио» (Ю4в): затравка — источник YouTube трека из каталога или, если его нет, первая песня из поиска YouTube
 * по «исполнитель название»; очередь не меняется, пока первая страница не пришла; всё, что идёт не так, — одна из
 * четырёх вестей, а очередь остаётся прежней.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4cRadioStarterTest {
    @Test
    fun `a youtube track of the catalog opens the radio from its own source`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            rig.script("vidA/251", null, rig.page(listOf(rig.track("v1", "One"), rig.track("v2", "Two")), next = "k2"))
            rig.script("vidA/251", "k2", rig.page(listOf(rig.track("v3", "Three")), next = null))

            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            val queue = rig.controller.queue.value
            assertThat(queue.context).isEqualTo(QueueContext.Radio("youtube", "vidA/251", "Get Lucky"))
            assertThat(queue.contextItems.map { it.title })
                .containsExactly("Get Lucky", "One", "Two", "Three")
                .inOrder()
            assertThat(queue.current).isEqualTo(rig.item(id, seed))
            assertThat(rig.engine.preparedSources.last()).isEqualTo(AudioSource.Online(id.value))
            assertThat(rig.engine.state.value).isEqualTo(PlaybackState.Playing)
            // Ключ первой страницы дошёл до подгрузки: после затравки следующий запрос — с ним, а не с начала.
            assertThat(rig.calls).containsExactly("vidA/251" to null, "vidA/251" to "k2").inOrder()
            assertThat(rig.online.queries).isEmpty()
            assertThat(rig.told).isEmpty()
        }

    @Test
    fun `a file of the disk finds its seed by a search among the youtube results`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.online.sections = listOf(OnlineSection("archive.org", emptyList()), rig.youtubeSection("vidX", "vidY"))
            rig.script("vidX", null, rig.page(listOf(rig.track("v1", "One"), rig.track("v2", "Two"))))
            val file = QueueItem(AudioSource.LocalFile("/music/b.flac"), "Bohemian Rhapsody", "Queen")

            rig.starter.start(RadioSeed(file, TrackId("local-1")))

            val queue = rig.controller.queue.value
            assertThat(rig.online.queries).containsExactly("Queen Bohemian Rhapsody")
            assertThat(queue.context).isEqualTo(QueueContext.Radio("youtube", "vidX", "Bohemian Rhapsody"))
            assertThat(queue.contextItems.map { it.title }).containsExactly("Bohemian Rhapsody", "One", "Two").inOrder()
            assertThat(queue.contextItems.first()).isEqualTo(file)
            assertThat(rig.engine.preparedSources.last()).isEqualTo(AudioSource.LocalFile("/music/b.flac"))
            assertThat(rig.calls).containsExactly("vidX" to null)
            assertThat(rig.told).isEmpty()
        }

    @Test
    fun `a track without an artist is searched by its title`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.online.sections = listOf(rig.youtubeSection("vidX"))
            rig.script("vidX", null, rig.page(listOf(rig.track("v1", "One"))))

            rig.starter.start(RadioSeed(QueueItem(AudioSource.LocalFile("/music/c.mp3"), "Untitled Demo"), null))

            assertThat(rig.online.queries).containsExactly("Untitled Demo")
            assertThat((rig.controller.queue.value.context as QueueContext.Radio).seed).isEqualTo("vidX")
        }

    @Test
    fun `an internet archive track is searched like a file`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val concert = rig.track("concert", "Opening", "Plinth Band")
            val (id) = rig.repository.add("archive.org", listOf(concert))
            rig.online.sections = listOf(rig.youtubeSection("vidX"))
            rig.script("vidX", null, rig.page(listOf(rig.track("v1", "One"))))

            rig.starter.start(RadioSeed(rig.item(id, concert), id))

            assertThat(rig.online.queries).containsExactly("Plinth Band Opening")
            assertThat((rig.controller.queue.value.context as QueueContext.Radio).seed).isEqualTo("vidX")
            assertThat(rig.calls.map { it.first }).containsExactly("vidX")
        }

    @Test
    fun `with youtube turned off the person is told and nothing is asked`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            rig.settings.setProviderEnabled("youtube", false)

            rig.starter.start(RadioSeed(rig.item(id, seed), id))
            rig.starter.start(RadioSeed(QueueItem(AudioSource.LocalFile("/music/b.flac"), "Song", "Band"), null))

            assertThat(rig.told).containsExactly(RadioProblem.NEEDS_YOUTUBE, RadioProblem.NEEDS_YOUTUBE)
            assertThat(rig.calls).isEmpty()
            assertThat(rig.online.queries).isEmpty()
            assertThat(rig.controller.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        }

    @Test
    fun `with the main switch off the person is told the same`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            rig.settings.setEnabled(false)

            rig.starter.start(RadioSeed(QueueItem(AudioSource.LocalFile("/music/b.flac"), "Song", "Band"), null))

            assertThat(rig.told).containsExactly(RadioProblem.NEEDS_YOUTUBE)
            assertThat(rig.online.queries).isEmpty()
        }

    @Test
    fun `no network is told whether it fails in the search or in the radio`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            rig.repository.networkUp = false
            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            rig.online.sections = listOf(OnlineSection("youtube", emptyList(), OnlineProblem.NO_NETWORK))
            rig.starter.start(RadioSeed(QueueItem(AudioSource.LocalFile("/music/b.flac"), "Song", "Band"), null))

            assertThat(rig.told).containsExactly(RadioProblem.NO_NETWORK, RadioProblem.NO_NETWORK)
            assertThat(rig.controller.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        }

    @Test
    fun `nothing found by the search is told as not found`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val file = RadioSeed(QueueItem(AudioSource.LocalFile("/music/b.flac"), "Song", "Band"), null)
            // Секция YouTube пуста, секции YouTube нет, у YouTube беда, не связанная с сетью.
            rig.online.sections = listOf(rig.youtubeSection())
            rig.starter.start(file)
            rig.online.sections = listOf(OnlineSection("archive.org", emptyList()))
            rig.starter.start(file)
            rig.online.sections = listOf(OnlineSection("youtube", emptyList(), OnlineProblem.PROVIDER_DOWN))
            rig.starter.start(file)

            assertThat(rig.told).containsExactly(RadioProblem.NOT_FOUND, RadioProblem.NOT_FOUND, RadioProblem.NOT_FOUND)
            assertThat(rig.calls).isEmpty()
            assertThat(rig.controller.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        }

    @Test
    fun `a provider that does not answer or answers nothing new is told as not found`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            // Сценария нет: провайдер не отвечает.
            rig.starter.start(RadioSeed(rig.item(id, seed), id))
            // Отвечает, но на странице только двойник затравки.
            rig.script("vidA/251", null, rig.page(listOf(rig.track("vidB", "get lucky", "DAFT PUNK"))))
            rig.starter.start(RadioSeed(rig.item(id, seed), id))
            // Отвечает пустой страницей.
            rig.script("vidA/251", null, rig.page(emptyList()))
            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            assertThat(rig.told).containsExactly(RadioProblem.NOT_FOUND, RadioProblem.NOT_FOUND, RadioProblem.NOT_FOUND)
            assertThat(rig.controller.queue.value).isEqualTo(PlaybackQueue.EMPTY)
        }

    @Test
    fun `a track without a title has no radio`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()

            val untitled = QueueItem(AudioSource.LocalFile("/music/b.flac"), title = null, artist = "Band")

            rig.starter.start(RadioSeed(untitled, null))

            assertThat(rig.told).containsExactly(RadioProblem.NOT_FOUND)
            assertThat(rig.online.queries).isEmpty()
            assertThat(rig.calls).isEmpty()
        }

    @Test
    fun `the queue stays as it was until the first page comes`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val songs = listOf(local("/m/a.mp3", "A"), local("/m/b.mp3", "B"))
            rig.controller.play(QueueContext.Album("Album", null), songs, start = 0)
            val before = rig.controller.queue.value
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            val gate = CompletableDeferred<Unit>()
            rig.online.radioGate = gate
            rig.script("vidA/251", null, rig.page(rig.tracks(8, "n")))

            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            assertThat(rig.controller.queue.value).isSameInstanceAs(before)
            assertThat(rig.engine.preparedSources).hasSize(1)

            gate.complete(Unit)

            val radio = QueueContext.Radio("youtube", "vidA/251", "Get Lucky")
            assertThat(rig.controller.queue.value.context).isEqualTo(radio)
            assertThat(rig.engine.preparedSources).hasSize(2)
        }

    @Test
    fun `the last start wins`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val alpha = rig.track("vidA", "Alpha")
            val beta = rig.track("vidB", "Beta")
            val (idA, idB) = rig.catalog(listOf(alpha, beta))
            val gate = CompletableDeferred<Unit>()
            rig.online.radioGate = gate
            rig.script("vidA/251", null, rig.page(rig.tracks(8, "a")))
            rig.script("vidB/251", null, rig.page(rig.tracks(8, "b")))

            rig.starter.start(RadioSeed(rig.item(idA, alpha), idA))
            rig.starter.start(RadioSeed(rig.item(idB, beta), idB))
            gate.complete(Unit)

            val queue = rig.controller.queue.value
            assertThat((queue.context as QueueContext.Radio).seed).isEqualTo("vidB/251")
            assertThat(queue.contextItems.first().title).isEqualTo("Beta")
            assertThat(rig.engine.preparedSources).hasSize(1)
        }

    @Test
    fun `the manual block survives a new radio`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val manual = local("/m/manual.mp3", "Manual")
            rig.controller.play(QueueContext.Album("Album", null), listOf(local("/m/a.mp3", "A")), start = 0)
            rig.controller.perform(QueueAction.ADD_TO_QUEUE, manual)
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            rig.script("vidA/251", null, rig.page(rig.tracks(8, "n")))

            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            assertThat(rig.controller.queue.value.upNext).containsExactly(manual)
            assertThat(rig.controller.queue.value.context).isInstanceOf(QueueContext.Radio::class.java)
        }

    @Test
    fun `only what is queued reaches the catalog`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            val lookalike = rig.track("vidL", "GET LUCKY!", "Daft Punk")
            val fresh = rig.track("vidF", "Lose Yourself to Dance", "Daft Punk")
            rig.script("vidA/251", null, rig.page(listOf(lookalike, fresh)))

            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            val queue = rig.controller.queue.value
            assertThat(queue.contextItems.map { it.title })
                .containsExactly("Get Lucky", "Lose Yourself to Dance")
                .inOrder()
            val known = rig.repository.known("youtube", listOf(lookalike, fresh))
            assertThat(known[0]).isNull()
            assertThat(known[1]).isNotNull()
        }

    @Test
    fun `starting the radio again begins it afresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = rig()
            val seed = rig.track("vidA", "Get Lucky", "Daft Punk")
            val (id) = rig.catalog(listOf(seed))
            rig.script("vidA/251", null, rig.page(rig.tracks(8, "n")))
            rig.starter.start(RadioSeed(rig.item(id, seed), id))
            repeat(3) { rig.controller.next() }

            // Второй раз от того же трека: радио начинается заново, с затравки.
            rig.starter.start(RadioSeed(rig.item(id, seed), id))

            val queue = rig.controller.queue.value
            assertThat(queue.contextItems.map { it.title }.first()).isEqualTo("Get Lucky")
            assertThat(queue.position).isEqualTo(0)
            assertThat(queue.contextItems).hasSize(9)
        }

    private suspend fun TestScope.rig() = RadioRig(this).also { it.enable() }

    private fun local(
        uri: String,
        title: String,
    ) = QueueItem(AudioSource.LocalFile(uri), title)
}
