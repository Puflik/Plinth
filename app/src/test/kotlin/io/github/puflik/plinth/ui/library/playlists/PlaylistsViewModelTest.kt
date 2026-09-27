package io.github.puflik.plinth.ui.library.playlists

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.PlaylistFile
import io.github.puflik.plinth.library.PlaylistFiles
import io.github.puflik.plinth.library.TaggedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Вкладка «Плейлисты» (D4b): свои плейлисты по имени; создать, переименовать,
 * удалить. Импорт и экспорт M3U (D4c) — с коротким итогом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val library = FakeLibraryRepository()
    private val repository = FakePlaylistRepository(library)
    private val files = MemoryFiles()
    private val viewModel by lazy { PlaylistsViewModel(repository, files) }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `playlists go by name`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }

            viewModel.create("road")
            viewModel.create("Ambient")

            assertThat(names()).containsExactly("Ambient", "road").inOrder()
        }

    @Test
    fun `a name is trimmed and a blank one makes nothing`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }

            viewModel.create("  Mix  ")
            viewModel.create("   ")

            assertThat(names()).containsExactly("Mix")
        }

    @Test
    fun `a playlist is renamed and deleted`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }
            viewModel.create("Mix")
            viewModel.create("Road")

            viewModel.rename(viewModel.playlists.value.first(), " Jazz ")
            viewModel.rename(viewModel.playlists.value.first(), " ")
            val renamed = names()
            viewModel.delete(viewModel.playlists.value.first { it.name == "Road" })

            assertThat(renamed).containsExactly("Jazz", "Road").inOrder()
            assertThat(names()).containsExactly("Jazz")
        }

    /** Имя плейлиста — имя файла без пробелов по краям; итог — сколько нашлось и сколько нет. */
    @Test
    fun `an imported file becomes a playlist and tells what was found`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }
            library.add(listOf(TaggedFile(SONG)))
            val text = listOf(SONG, "/gone.mp3").joinToString("\n")
            files.stored[PICKED] = PlaylistFile(" Road ", text.toByteArray(), folder = null)
            val notice = backgroundScope.async { viewModel.notices.first() }

            viewModel.import(PICKED)

            assertThat(notice.await()).isEqualTo(PlaylistNotice.Imported(added = 1, notFound = 1))
            assertThat(names()).containsExactly("Road")
        }

    @Test
    fun `an unreadable file says so and makes nothing`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }
            val notice = backgroundScope.async { viewModel.notices.first() }

            viewModel.import("content://nowhere")

            assertThat(notice.await()).isEqualTo(PlaylistNotice.ImportFailed)
            assertThat(names()).isEmpty()
        }

    @Test
    fun `an exported playlist is written to the chosen file`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }
            val (song) = library.add(listOf(TaggedFile(SONG)))
            val mix = checkNotNull(repository.create("Mix"))
            repository.add(mix, song.id)
            val notice = backgroundScope.async { viewModel.notices.first() }

            viewModel.export(viewModel.playlists.value.single(), CREATED)

            assertThat(notice.await()).isEqualTo(PlaylistNotice.Exported("Mix"))
            assertThat(files.written[CREATED]?.lines()).containsAtLeast("#EXTM3U", SONG).inOrder()
        }

    @Test
    fun `an export that cannot be written says so`() =
        runTest(main) {
            backgroundScope.launch { viewModel.playlists.collect {} }
            repository.create("Mix")
            files.writable = false
            val notice = backgroundScope.async { viewModel.notices.first() }

            viewModel.export(viewModel.playlists.value.single(), CREATED)

            assertThat(notice.await()).isEqualTo(PlaylistNotice.ExportFailed)
        }

    private fun names() = viewModel.playlists.value.map { it.name }

    /** Документы SAF в памяти: [stored] читаются, записанное попадает в [written]. */
    private class MemoryFiles : PlaylistFiles {
        val stored = mutableMapOf<String, PlaylistFile>()
        val written = mutableMapOf<String, String>()
        var writable = true

        override suspend fun read(uri: String): PlaylistFile? = stored[uri]

        override suspend fun write(
            uri: String,
            text: String,
        ): Boolean {
            if (writable) written[uri] = text
            return writable
        }
    }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        const val PICKED = "content://picked/road.m3u"
        const val CREATED = "content://created/mix.m3u8"
    }
}
