package io.github.puflik.plinth.ui.search

import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.online.OnlineAlbum
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.TrackActions
import io.github.puflik.plinth.ui.online.toLibraryTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Что экран знает о песне секции поиска (Ю5а), пока человек с ней работает. В `SearchUiState.songs` есть только
 * песни, с которыми работали: у остальных — `SongState()`.
 *
 * @property liked лайк стоит; известен, как только песня заведена в каталог, — до этого «нет».
 * @property busy песню получают у провайдера (`resolve`) — строка показывает ход, лишнее касание игнорируется.
 */
data class SongState(
    val liked: Boolean = false,
    val busy: Boolean = false,
)

/** Чем кончилось действие над песней секции; экран отвечает человеку (плеер, «добавлено», беда). */
sealed interface SongEvent {
    /** Действие сделано — экран открывает плеер или говорит «добавлено», как после касания трека списка. */
    data class Done(
        val action: TrackAction,
    ) : SongEvent

    /** Песню не получили у [provider] ([problem]); в каталоге, очереди и журнале ничего не изменено. */
    data class Failed(
        val provider: String,
        val problem: OnlineProblem,
    ) : SongEvent
}

/** Ключ песни в `SearchUiState.songs` — тот же, что у строки секции в списке. */
fun songKey(
    provider: String,
    result: OnlineResult,
): String = "$provider/${result.external}"

/**
 * Песни секции поиска (Ю5а): превращает результат поиска (без вариантов) в трек каталога — `resolve` один раз, потом
 * из памяти — и держит то, что показывает строка. `resolve` видео кладёт потоки в заготовку источника, поэтому
 * первый `stream()` после него не стоит второго извлечения.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OnlineSongs(
    private val online: OnlineRepository,
    private val actions: TrackActions,
) {
    private val working = MutableStateFlow(emptySet<String>())
    private val known = MutableStateFlow(emptyMap<String, LibraryTrack>())
    private val mutableEvents = MutableSharedFlow<SongEvent>(extraBufferCapacity = EVENT_BUFFER)

    val events: SharedFlow<SongEvent> = mutableEvents.asSharedFlow()

    /** Строки песен: сердце — из журнала, ход — пока идёт `resolve`. */
    val states: Flow<Map<String, SongState>> =
        known
            .flatMapLatest { tracks ->
                if (tracks.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(tracks.map { (key, track) -> actions.liked(track.id).map { key to it } }) { it.toMap() }
                }
            }.combine(working) { hearts, busy ->
                (hearts.keys + busy).associateWith { SongState(liked = hearts[it] ?: false, busy = it in busy) }
            }

    /** Начинает работу над песней [key]; `false` — над ней уже работают, касание лишнее. */
    fun begin(key: String): Boolean {
        if (key in working.value) return false
        working.value += key
        return true
    }

    fun end(key: String) = working.update { it - key }

    fun tell(event: SongEvent) {
        mutableEvents.tryEmit(event)
    }

    /**
     * Песня [result] провайдера [provider] как трек каталога: из памяти, иначе `resolve` и `add`. Не вышло — `null`,
     * экран узнаёт об этом из [SongEvent.Failed]; неудача в памяти не остаётся.
     */
    suspend fun track(
        provider: String,
        result: OnlineResult,
    ): LibraryTrack? {
        val key = songKey(provider, result)
        known.value[key]?.let { return it }
        return when (val answer = online.album(provider, result.external)) {
            is OnlineAlbum.Failed -> failed(provider, answer.problem)
            is OnlineAlbum.Tracks -> {
                val song = answer.tracks.firstOrNull()
                val id = song?.let { online.add(provider, listOf(it)) }?.singleOrNull()
                if (song == null || id == null) {
                    failed(provider, OnlineProblem.BROKEN)
                } else {
                    song.toLibraryTrack(id).also { found -> known.update { it + (key to found) } }
                }
            }
        }
    }

    private fun failed(
        provider: String,
        problem: OnlineProblem,
    ): LibraryTrack? {
        tell(SongEvent.Failed(provider, problem))
        return null
    }

    private companion object {
        const val EVENT_BUFFER = 8
    }
}
