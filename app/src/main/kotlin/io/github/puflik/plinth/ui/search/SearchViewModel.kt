package io.github.puflik.plinth.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.PlaylistId
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Что видит экран поиска под полем. Текста поля здесь нет: поле держит его
 * само (Н3).
 *
 * @property searched результаты относятся к непустому запросу: пустой список
 *   значит «ничего не нашлось», а не «ещё не искали».
 * @property online секции провайдеров под библиотекой (E3).
 * @property songs песни секций, с которыми работали, по [songKey] (Ю5а); у остальных — `SongState()`.
 */
data class SearchUiState(
    val results: List<LibraryTrack> = emptyList(),
    val searched: Boolean = false,
    val online: OnlineResults = OnlineResults.None,
    val songs: Map<String, SongState> = emptyMap(),
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
 *
 * Последний ответ сети помнится здесь же (R1 №14): пока текст тот же, сеть не спрашивают
 * заново — ни при возврате с экрана альбома, ни после ухода дольше пяти секунд, когда
 * `WhileSubscribed` уже остановил поток. Вернувшийся экран видит прежнее состояние первым
 * кадром, а не пустое: иначе список уходит из композиции и теряет прокрутку. Ответ с бедой
 * помнится так же — заменить его может «Повторить» ([onRetryOnline]) или новый текст. Уход
 * дольше пяти секунд по-прежнему отменяет идущий запрос (Р1.11а).
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

        // Сколько раз нажали «Повторить»: новое число — тот же текст спрашивается заново.
        private val retries = MutableStateFlow(0)

        // Что показано сейчас: поток, начатый заново, стартует с этого, а не с пустого.
        private var shownFound = NOTHING
        private var shownOnline: OnlineResults = OnlineResults.None

        // Последний ответ сети — одна запись; viewModelScope и все потоки ниже живут на одном потоке.
        private var answer: Answer? = null

        private val settled: Flow<String> =
            query
                .debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }
                .map(String::trim)
                .distinctUntilChanged()

        private val found =
            settled
                .flatMapLatest { text ->
                    if (text.isEmpty()) flowOf(NOTHING) else library.search(text).map { Found(it, searched = true) }
                }.onEach { shownFound = it }
                // Экран готов сразу, ещё до первого поиска; вернувшийся — с тем, что видел.
                .onStart { emit(shownFound) }

        private val inNetwork: Flow<OnlineResults> =
            combine(settled, onlineSettings.enabled, retries) { text, enabled, attempt ->
                text.takeIf { enabled && it.isNotEmpty() }?.let { Asked(it, attempt) }
            }.distinctUntilChanged()
                .flatMapLatest(::answerTo)
                .onEach { shownOnline = it }
                .onStart { emit(shownOnline) }

        // Песни секций (Ю5а): `resolve` один раз на песню, ход и сердце строки.
        private val songs = OnlineSongs(online, actions)

        val uiState: StateFlow<SearchUiState> =
            combine(found, inNetwork, songs.states) { found, online, songs ->
                SearchUiState(found.results, found.searched, online, songs)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

        /** Чем кончились действия над песнями секций: экран открывает плеер, говорит «добавлено» или беду. */
        val songEvents: SharedFlow<SongEvent> = songs.events

        /** Текст поля — как набран; обратно в поле он не возвращается (Н3). */
        fun onQuery(text: String) {
            query.value = text
        }

        /**
         * «Повторить» в секции сети: тот же устоявшийся текст спрашивается заново (`Searching`, потом
         * ответ). Текста нет или источники выключены — ничего: спрашивать нечего.
         */
        fun onRetryOnline() {
            retries.update { it + 1 }
        }

        /** Найденный трек: контекст — результаты поиска. */
        fun onTrack(
            track: LibraryTrack,
            action: TrackAction,
        ) {
            actions.act(action, QueueContext.Search(query.value.trim()), uiState.value.results, track)
        }

        /**
         * [action] над песней [result] секции провайдера [provider] (Ю5а): сначала песня попадает в каталог
         * (`resolve` у провайдера — в первый раз), потом действие, как над треком списка: контекст — поиск, в нём
         * одна песня. Кончилось — [songEvents]. Альбом — не песня: ничего. Над песней, что уже получают, — ничего.
         */
        fun onSong(
            provider: String,
            result: OnlineResult,
            action: TrackAction,
        ) = withSong(provider, result, SongEvent.Done(action)) { track ->
            actions.act(action, QueueContext.Search(query.value.trim()), listOf(track), track)
        }

        /** «В плейлист» → [playlist] для песни [result] секции: в каталог и в конец плейлиста. */
        fun onSongToPlaylist(
            provider: String,
            result: OnlineResult,
            playlist: PlaylistId,
        ) = withSong(provider, result, done = null) { actions.addToPlaylist(it, playlist) }

        /** «В плейлист» → «Новый плейлист» [name] для песни [result] секции. */
        fun onSongToNewPlaylist(
            provider: String,
            result: OnlineResult,
            name: String,
        ) = withSong(provider, result, done = null) { actions.addToNewPlaylist(it, name) }

        private fun withSong(
            provider: String,
            result: OnlineResult,
            done: SongEvent?,
            block: (LibraryTrack) -> Unit,
        ) {
            val key = songKey(provider, result)
            if (result.kind != OnlineKind.TRACK || !songs.begin(key)) return
            viewModelScope.launch {
                try {
                    songs.track(provider, result)?.let { track ->
                        block(track)
                        done?.let(songs::tell)
                    }
                } finally {
                    songs.end(key)
                }
            }
        }

        /**
         * Ответ сети на [asked]: из памяти, если на этот же вопрос уже отвечали, иначе `Searching` и
         * запрос. Нет вопроса (пустой текст или источники выключены) — секций нет, и память пуста:
         * потом тот же текст спросят заново.
         */
        private fun answerTo(asked: Asked?): Flow<OnlineResults> {
            if (asked == null) {
                answer = null
                return flowOf(OnlineResults.None)
            }
            val known = answer?.takeIf { it.asked == asked }
            return flow {
                if (known != null) {
                    emit(known.found)
                } else {
                    emit(OnlineResults.Searching)
                    val found = OnlineResults.Found(online.search(asked.text))
                    answer = Answer(asked, found)
                    emit(found)
                }
            }
        }

        private data class Found(
            val results: List<LibraryTrack>,
            val searched: Boolean,
        )

        // О чём спросили сеть: устоявшийся текст и номер «Повторить».
        private data class Asked(
            val text: String,
            val attempt: Int,
        )

        private class Answer(
            val asked: Asked,
            val found: OnlineResults.Found,
        )

        companion object {
            /** Сколько набор должен простоять, чтобы запрос ушёл в библиотеку и в сеть. */
            const val DEBOUNCE_MS = 300L

            // Переживает поворот экрана, не держит подписку в фоне.
            private const val STOP_TIMEOUT_MS = 5_000L
            private val NOTHING = Found(emptyList(), searched = false)
        }
    }
