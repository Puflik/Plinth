package io.github.puflik.plinth.ui.library.playlists

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.FakePlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Вкладка «Плейлисты» (D4b): свои плейлисты по имени; создать, переименовать, удалить. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val repository = FakePlaylistRepository()
    private val viewModel by lazy { PlaylistsViewModel(repository) }

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

    private fun names() = viewModel.playlists.value.map { it.name }
}
