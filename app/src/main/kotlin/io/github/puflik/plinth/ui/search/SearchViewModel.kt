package io.github.puflik.plinth.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.online.OnlineSettings
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Что видит экран поиска под полем. Текста поля здесь нет: поле держит его
 * само (Н3).
 *
 * @property searched результаты относятся к непустому запросу: пустой список
 *   значит «ничего не нашлось», а не «ещё не искали».
 * @property online секции провайдеров под библиотекой (E3).
 */
data class SearchUiState(
    val results: List<LibraryTrack> = emptyList(),
    val searched: Boolean = false,
    val online: OnlineResults = OnlineResults.None,
)

/** Поиск в сети под библиотекой (E3): ответ сети приходит позже библиотеки. */
sealed interface OnlineResults {
    /** Источники выключены или запроса нет — секций нет. */
    data object None : OnlineResults

    /** Запрос ушёл, сеть ещё не ответила. */
    data object Searching : OnlineResults

    /** Секция на провайдера — с результатами или с бедой. */
    data class Found(
        val sections: List<OnlineSection>,
    ) : OnlineResults
}

/**
 * Мгновенный поиск (C4.4, E3): запрос уходит в библиотеку и к провайдерам,
 * когда набор замер на [DEBOUNCE_MS]; стёртый запрос очищает результаты
 * сразу. Библиотека отвечает сразу, провайдеры — своей секцией ниже, когда
 * ответит сеть (ответ автора E3). Касание трека включает его, как в
 * библиотеке; альбом провайдера открывается своим экраном.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        private val online: OnlineRepository,
        onlineSettings: OnlineSettings,
        private val actions: TrackActions,
    ) : ViewModel() {
        private val query = MutableStateFlow("")

        private val settled: Flow<String> =
            query
                .debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }
                .map(String::trim)
                .distinctUntilChanged()

        private val found =
            settled
                .flatMapLatest { text ->
                    if (text.isEmpty()) flowOf(NOTHING) else library.search(text).map { Found(it, searched = true) }
                }
                // Экран готов сразу, ещё до первого поиска.
                .onStart { emit(NOTHING) }

        private val inNetwork: Flow<OnlineResults> =
            combine(settled, onlineSettings.enabled) { text, enabled -> text.takeIf { enabled } }
                .distinctUntilChanged()
                .flatMapLatest { text ->
                    if (text.isNullOrEmpty()) {
                        flowOf<OnlineResults>(OnlineResults.None)
                    } else {
                        flow {
                            emit(OnlineResults.Searching)
                            emit(OnlineResults.Found(online.search(text)))
                        }
                    }
                }.onStart { emit(OnlineResults.None) }

        val uiState: StateFlow<SearchUiState> =
            combine(found, inNetwork) { found, online ->
                SearchUiState(found.results, found.searched, online)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

        /** Текст поля — как набран; обратно в поле он не возвращается (Н3). */
        fun onQuery(text: String) {
            query.value = text
        }

        /** Найденный трек: контекст — результаты поиска. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) {
            actions.act(action, QueueContext.Search(query.value.trim()), uiState.value.results, track)
        }

        private data class Found(
            val results: List<LibraryTrack>,
            val searched: Boolean,
        )

        companion object {
            /** Сколько набор должен простоять, чтобы запрос ушёл в библиотеку и в сеть. */
            const val DEBOUNCE_MS = 300L

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L
            private val NOTHING = Found(emptyList(), searched = false)
        }
    }
