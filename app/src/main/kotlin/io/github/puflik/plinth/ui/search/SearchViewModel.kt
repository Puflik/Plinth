package io.github.puflik.plinth.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.act
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Что видит экран поиска.
 *
 * @property query набранный текст — сразу, без задержки поиска.
 * @property searched результаты относятся к непустому запросу: пустой список
 *   значит «ничего не нашлось», а не «ещё не искали».
 */
data class SearchUiState(
    val query: String = "",
    val results: List<LibraryTrack> = emptyList(),
    val searched: Boolean = false,
)

/**
 * Мгновенный поиск (C4.4): запрос уходит в библиотеку, когда набор замер на
 * [DEBOUNCE_MS]; стёртый запрос очищает результаты сразу. Касание результата
 * включает трек, как в библиотеке.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        private val playback: PlaybackController,
    ) : ViewModel() {
        private val query = MutableStateFlow("")

        private val found =
            query
                .debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }
                .map(String::trim)
                .distinctUntilChanged()
                .flatMapLatest { text ->
                    if (text.isEmpty()) flowOf(NOTHING) else library.search(text).map { Found(it, searched = true) }
                }
                // Набранный текст виден сразу, ещё до первого поиска.
                .onStart { emit(NOTHING) }

        val uiState: StateFlow<SearchUiState> =
            combine(query, found) { query, found -> SearchUiState(query, found.results, found.searched) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

        fun onQuery(text: String) {
            query.value = text
        }

        /** Найденный трек: контекст — результаты поиска. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) {
            val state = uiState.value
            playback.act(action, QueueContext.Search(state.query.trim()), state.results, track)
        }

        private data class Found(
            val results: List<LibraryTrack>,
            val searched: Boolean,
        )

        companion object {
            /** Сколько набор должен простоять, чтобы запрос ушёл в библиотеку. */
            const val DEBOUNCE_MS = 300L

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L
            private val NOTHING = Found(emptyList(), searched = false)
        }
    }
