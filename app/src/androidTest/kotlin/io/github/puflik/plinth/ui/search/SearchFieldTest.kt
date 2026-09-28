package io.github.puflik.plinth.ui.search

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewModelScope
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.audio.engine.FakeAudioEngine
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.FakeLibraryRepository
import io.github.puflik.plinth.library.FakePlaylistRepository
import io.github.puflik.plinth.library.FakeUserDataRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.FakeOnlineRepository
import io.github.puflik.plinth.online.FakeOnlineSettings
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.minutes

/**
 * Н3 приёмки v0.2: при быстром вводе (`adb input text`) поле поиска теряло
 * буквы, а курсор вставал не в конец. Текст поля шёл кругом через
 * `combine(…).stateIn(…)` ViewModel, и буква, набранная раньше, чем круг
 * замкнулся, стиралась старым текстом. Здесь ViewModel стоит, пока идёт
 * ввод, — круг не замыкается вовсе.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private val main = HoldingMain()
    private val library = FakeLibraryRepository()
    private val viewModel by lazy {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val playback = PlaybackController(FakeAudioEngine(), scope)
        val actions = TrackActions(playback, FakeUserDataRepository(), FakePlaylistRepository(), scope)
        SearchViewModel(library, FakeOnlineRepository(), FakeOnlineSettings(), actions)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun letters_typed_faster_than_the_search_stay_in_the_field_with_the_cursor_at_the_end() {
        runBlocking { library.upsert(listOf(YESTERDAY)) }
        compose.setContent { SearchScreen(onOpenPlayer = {}, onOpenAlbum = {}, viewModel = viewModel) }
        val field = compose.onNode(hasSetTextAction())

        main.hold(viewModel.viewModelScope)
        var typed = ""
        for (letter in "beat") {
            field.performTextInput(letter.toString())
            typed += letter
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(typed)))
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(typed.length)))
        }

        // ViewModel догоняет ввод: запрос — весь набранный текст.
        main.release()
        compose.waitUntil(SEARCH_TIMEOUT_MS) {
            compose.onAllNodes(hasText(YESTERDAY.title)).fetchSemanticsNodes().isNotEmpty()
        }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("beat")))
    }

    /**
     * Главный поток, который по команде придерживает корутины одной области —
     * здесь ViewModel. Остальное (Compose, опрос простоя в ui-test) идёт как
     * обычно: заморозить весь `Dispatchers.Main` значит заморозить и тест.
     */
    private class HoldingMain : CoroutineDispatcher() {
        private val real = Handler(Looper.getMainLooper()).asCoroutineDispatcher()
        private val held = mutableListOf<Runnable>()
        private var owner: Job? = null

        @Synchronized
        fun hold(scope: CoroutineScope) {
            owner = scope.coroutineContext.job
        }

        fun release() {
            val queued =
                synchronized(this) {
                    owner = null
                    held.toList().also { held.clear() }
                }
            queued.forEach { real.dispatch(EmptyCoroutineContext, it) }
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(this) {
                val owner = owner
                if (owner != null && generateSequence(context[Job]) { it.parent }.any { it == owner }) {
                    held += block
                    return
                }
            }
            real.dispatch(context, block)
        }
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 5_000L

        val YESTERDAY =
            LibraryTrack(
                id = TrackId("01a0da9e-761f-7073-bc9b-e5437326adc1"),
                uri = "/storage/emulated/0/Music/track-1.mp3",
                title = "Yesterday",
                artist = "The Beatles",
                duration = 3.minutes,
                folder = "Music/",
            )
    }
}
