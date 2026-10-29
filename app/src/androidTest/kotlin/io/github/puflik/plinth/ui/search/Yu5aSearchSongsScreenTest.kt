package io.github.puflik.plinth.ui.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.online.TestSong
import io.github.puflik.plinth.online.TestYoutubeOnline
import io.github.puflik.plinth.ui.common.formatTime
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.online.OnlineAlbumHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Секция YouTube в поиске (Ю5а): песня — строка трека (длительность, облако «From YouTube», кнопка ⋮ с меню,
 * касание играет), альбом — строка альбома (касание открывает его экран). Экран собран на `TestYoutubeOnline`:
 * поиск отдаёт одну песню и один альбом, сеть YouTube не нужна.
 */
class Yu5aSearchSongsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val online = TestYoutubeOnline()
    private val engine = FakeAudioEngine()
    private val userData = FakeUserDataRepository()
    private val opened = AtomicInteger()
    private val album = AtomicReference<OnlineAlbumHeader?>()
    private val viewModel by lazy {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val playback = PlaybackController(engine, scope)
        val actions = TrackActions(playback, userData, FakePlaylistRepository(), scope)
        SearchViewModel(FakeLibraryRepository(), online, FakeOnlineSettings(), actions)
    }

    private fun show() {
        compose.setContent {
            SearchScreen(
                onOpenPlayer = { opened.incrementAndGet() },
                onOpenAlbum = { album.set(it) },
                viewModel = viewModel,
            )
        }
        compose.onNode(hasSetTextAction()).performTextInput("lucky")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(TestSong.TITLE).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun text(id: Int) = context.getString(id)

    @Test
    fun a_song_is_a_track_row_with_a_menu_and_an_album_is_not() {
        show()

        compose.onAllNodesWithContentDescription(text(R.string.library_more)).assertCountEquals(1)
        compose.onNodeWithText(formatTime(TestSong.DURATION)).assertIsDisplayed()
        compose.onNodeWithText(TestSong.ALBUM_TITLE).assertIsDisplayed()
    }

    @Test
    fun the_cloud_of_a_song_says_where_it_is_from() {
        show()

        compose
            .onAllNodesWithContentDescription(context.getString(R.string.track_online_from, "YouTube"))
            .assertCountEquals(1)
    }

    @Test
    fun a_tap_on_a_song_plays_it_and_opens_the_player_not_an_album() {
        show()

        compose.onNodeWithText(TestSong.TITLE).performClick()

        compose.waitUntil(WAIT_MS) { opened.get() == 1 }
        assertThat(engine.preparedSources).hasSize(1)
        assertThat(album.get()).isNull()
    }

    @Test
    fun a_tap_on_an_album_opens_its_screen_and_plays_nothing() {
        show()

        compose.onNodeWithText(TestSong.ALBUM_TITLE).performClick()

        compose.waitUntil(WAIT_MS) { album.get() != null }
        assertThat(album.get())
            .isEqualTo(
                OnlineAlbumHeader(
                    provider = TestSong.PROVIDER,
                    item = TestSong.ALBUM_ID,
                    title = TestSong.ALBUM_TITLE,
                    artist = TestSong.ARTIST,
                    year = null,
                ),
            )
        assertThat(opened.get()).isEqualTo(0)
        assertThat(engine.preparedSources).isEmpty()
    }

    @Test
    fun the_menu_of_a_song_likes_it_and_then_offers_unlike() {
        show()

        compose.onNodeWithContentDescription(text(R.string.library_more)).performClick()
        compose.onNodeWithText(text(R.string.track_radio)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.track_add_to_playlist)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.track_like)).performClick()

        compose.waitUntil(WAIT_MS) { online.albumCalls.isNotEmpty() && liked() }
        compose.onNodeWithContentDescription(text(R.string.library_more)).performClick()
        compose.onNodeWithText(text(R.string.track_unlike)).assertIsDisplayed()
        assertThat(engine.preparedSources).isEmpty()
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun a_song_being_fetched_shows_progress_until_the_provider_answers() {
        val gate = CompletableDeferred<Unit>()
        online.gate = gate
        show()
        val progress = text(R.string.online_song_getting)

        compose.onNodeWithText(TestSong.TITLE).performClick()

        compose.waitUntil(WAIT_MS) { progressShown(progress) }
        gate.complete(Unit)
        compose.waitUntil(WAIT_MS) { !progressShown(progress) }
        compose.waitUntil(WAIT_MS) { opened.get() == 1 }
    }

    private fun progressShown(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    /** Лайк песни в журнале: песни может не быть в каталоге — тогда нет. */
    private fun liked(): Boolean =
        runBlocking {
            val id = online.known(TestSong.PROVIDER, listOf(TestSong.track)).single()
            id != null && userData.liked(id).first()
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
