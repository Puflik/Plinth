package io.github.puflik.plinth.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.OnlineConfiguration
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineReadiness
import io.github.puflik.plinth.online.OnlineReady
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.online.OnlineSettings
import io.github.puflik.plinth.online.normalized
import io.github.puflik.plinth.queue.QueueContext
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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
        val loading: Set<String> = emptySet(),
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
        private val readiness: OnlineReadiness,
    ) : ViewModel() {
        private val query = MutableStateFlow("")

        private var shownFound = NOTHING
        private val network = SearchNetwork(online, readiness) { query.value.trim() }

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

        private val configurations =
            combine(onlineSettings.enabled, onlineSettings.disabledProviders) { enabled, disabled ->
                OnlineConfiguration(enabled, disabled).normalized()
            }.distinctUntilChanged()

        private val inNetwork: Flow<OnlineResults> =
            channelFlow {
                network.collect(this, viewModelScope, settled, configurations)
            }

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
            viewModelScope.launch { network.retry(null) }
        }

        /** Повторяет только указанную активную секцию, сохраняя соседей. */
        fun onRetryOnline(provider: String) {
            viewModelScope.launch { network.retry(provider) }
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

private data class SearchContext(
    val text: String,
    val configuration: OnlineConfiguration,
) {
    val activeProviders: List<String>
        get() =
            OnlineProviderIds.ALL.filter {
                text.isNotEmpty() && configuration.enabled && it !in configuration.disabledProviders
            }
}

/** Cache lives in the VM; each collection owns and explicitly cancels its attempt scope. */
private class SearchNetwork(
    private val online: OnlineRepository,
    private val readiness: OnlineReadiness,
    private val queryText: () -> String,
) {
    private class Attempt {
        var job: Job? = null
        var started = false
        var completed = false
        var section: OnlineSection? = null
        val waiting: Boolean
            get() = !completed && !started
    }

    private var context: SearchContext? = null
    private val attempts = mutableMapOf<String, Attempt>()
    private var generation = 0L
    private var scope: CoroutineScope? = null
    private var output: ProducerScope<OnlineResults>? = null
    private var snapshot: OnlineResults = OnlineResults.None
    private var reconciled = false
    private val pendingRetries = mutableMapOf<String, Attempt>()

    suspend fun collect(
        output: ProducerScope<OnlineResults>,
        owner: CoroutineScope,
        texts: Flow<String>,
        configurations: Flow<OnlineConfiguration>,
    ) {
        // A cancelled transport may finish late; it must not delay the next subscription.
        scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
        this.output = output
        reconciled = false
        output.trySend(snapshot)
        try {
            // Reuse only an unchanged settled query; settings must not await its restarted debounce.
            val resumedTexts = texts.onStart { emit(context?.text?.takeIf { it == queryText() }.orEmpty()) }
            combine(resumedTexts, configurations, ::SearchContext).collect(::reconcile)
        } finally {
            reconciled = false
            pendingRetries.clear()
            attempts.replaceAll { _, attempt -> if (attempt.completed) attempt else Attempt() }
            scope?.cancel()
            scope = null
            this.output = null
        }
    }

    private fun reconcile(next: SearchContext) {
        val previous = context
        context = next
        if (previous?.text != next.text || !next.configuration.enabled || next.text.isEmpty()) {
            generation++
            attempts.values.forEach { it.job?.cancel() }
            attempts.clear()
        }
        val active = next.activeProviders
        (attempts.keys - active.toSet()).forEach { attempts.remove(it)?.job?.cancel() }
        active.forEach { provider ->
            val attempt = attempts[provider]
            val retarget = attempt?.waiting == true && previous?.configuration != next.configuration
            if (attempt == null || retarget) {
                attempt?.job?.cancel()
                attempts[provider] = Attempt()
            }
        }
        reconciled = true
        consumeRetries(next.text)
        publish()
        active.forEach { provider ->
            val attempt = attempts.getValue(provider)
            if (!attempt.completed && attempt.job == null) start(provider, attempt)
        }
    }

    private fun consumeRetries(text: String) {
        val retries = pendingRetries.toMap()
        pendingRetries.clear()
        if (text != queryText()) return
        retries.forEach { (provider, attempt) ->
            if (attempts[provider] === attempt) replaceRetry(provider, attempt)
        }
    }

    fun retry(provider: String?) {
        val text = queryText()
        if (text.isEmpty() || text != context?.text || scope == null) return
        val selected = if (provider == null) attempts.keys.toList() else listOf(provider)
        selected.forEach { id ->
            val old = attempts[id]
            if (old != null && (provider == null || old.completed)) {
                if (reconciled) replaceRetry(id, old) else pendingRetries[id] = old
            }
        }
    }

    private fun replaceRetry(
        provider: String,
        old: Attempt,
    ) {
        old.job?.cancel()
        val fresh = Attempt()
        attempts[provider] = fresh
        publish()
        start(provider, fresh)
    }

    private fun start(
        provider: String,
        attempt: Attempt,
    ) {
        val expectedGeneration = generation
        attempt.job =
            scope?.launch {
                try {
                    val ready = await(provider, attempt, expectedGeneration) ?: return@launch
                    val section =
                        when (ready) {
                            is OnlineReady.Applied -> {
                                attempt.started = true
                                online.searchIn(provider, checkNotNull(context).text).let {
                                    if (it == null || it.provider == provider) it else broken(provider)
                                }
                            }
                            is OnlineReady.Failed -> OnlineSection(provider, emptyList(), ready.problem)
                            OnlineReady.TimedOut -> OnlineSection(provider, emptyList(), OnlineProblem.PROVIDER_DOWN)
                            OnlineReady.Superseded -> return@launch
                        }
                    finish(provider, attempt, expectedGeneration, section)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    finish(provider, attempt, expectedGeneration, broken(provider))
                }
            }
    }

    private suspend fun await(
        provider: String,
        attempt: Attempt,
        expectedGeneration: Long,
    ): OnlineReady? {
        var outcome: OnlineReady? = null
        while (current(provider, attempt, expectedGeneration) && outcome == null) {
            val configuration = checkNotNull(context).configuration
            val ready = readiness.awaitConfiguration(configuration)
            if (current(provider, attempt, expectedGeneration) && ready != OnlineReady.Superseded) outcome = ready
            // Each Superseded is consumed once; ABA also needs a fresh waiter.
        }
        return outcome
    }

    private fun current(
        provider: String,
        attempt: Attempt,
        expectedGeneration: Long,
    ): Boolean = scope != null && generation == expectedGeneration && attempts[provider] === attempt

    private fun finish(
        provider: String,
        attempt: Attempt,
        expectedGeneration: Long,
        section: OnlineSection?,
    ) {
        if (!current(provider, attempt, expectedGeneration)) return
        attempt.section = section
        attempt.completed = true
        publish()
    }

    private fun publish() {
        snapshot =
            if (attempts.isEmpty()) {
                OnlineResults.None
            } else {
                OnlineResults.Found(
                    OnlineProviderIds.ALL.mapNotNull { attempts[it]?.section },
                    attempts.filterValues { !it.completed }.keys.toSet(),
                )
            }
        output?.trySend(snapshot)
    }

    private fun broken(provider: String) = OnlineSection(provider, emptyList(), OnlineProblem.BROKEN)
}
