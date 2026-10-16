package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.PlayEvent
import io.github.puflik.plinth.ffi.PlayEventId
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Лайки и история в памяти (D4) — для тестов экранов и записи истории.
 * Проходит `UserDataRepositoryContractTest`, как ядро на эмуляторе.
 *
 * Фонотека здесь — путь → трек. Как в приложении, перемену фонотеки ещё и
 * объявляют (`PlinthCore.catalogChanged()`): [add] и [rekey] делают это сами,
 * [catalogChanged] — вручную.
 */
class FakeUserDataRepository : UserDataRepository {
    private val tracks = ConcurrentHashMap<String, TrackId>()
    private val catalog = MutableStateFlow(0L)
    private val likes = MutableStateFlow(emptySet<TrackId>())
    private val plays = MutableStateFlow(emptyList<NewPlay>())

    /** Записанные прослушивания в порядке записи. */
    val recorded: List<NewPlay> get() = plays.value

    /** Кладёт в фонотеку файлы [uris]; знакомый файл остаётся тем же треком. */
    fun add(vararg uris: String): List<TrackId> {
        val added = uris.map { uri -> tracks.getOrPut(uri) { TrackId("track-${tracks.size + 1}") } }
        catalogChanged()
        return added
    }

    /**
     * Перепривязка (C4, ADR 0007): трек файла [uri] получает ID журнала [to], прежний ID
     * из фонотеки уходит. Перемену объявляют, если не сказано иначе: [announce] = `false` —
     * в фонотеке она уже есть (поиск по пути её видит), а сигнал ещё в пути.
     */
    fun rekey(
        uri: String,
        to: TrackId,
        announce: Boolean = true,
    ) {
        check(tracks.containsKey(uri)) { "файла нет в фонотеке: $uri" }
        tracks[uri] = to
        if (announce) catalogChanged()
    }

    /** Фонотека объявила перемену — потоки по пути перечитывают её. */
    fun catalogChanged() = catalog.update { it + 1 }

    override suspend fun trackAt(uri: String): TrackId? = tracks[uri]

    override fun trackAtChanges(uri: String): Flow<TrackId?> = catalog.map { tracks[uri] }.distinctUntilChanged()

    override fun liked(track: TrackId): Flow<Boolean> = likes.map { track in it }.distinctUntilChanged()

    override suspend fun setLiked(
        track: TrackId,
        liked: Boolean,
    ) = likes.update { if (liked) it + track else it - track }

    override suspend fun recordPlay(play: NewPlay) = plays.update { it + play }

    // Как в ядре: по времени начала, новые первыми; равные — позже записанное первым.
    override suspend fun recentPlays(limit: Int): List<PlayEvent> =
        plays.value
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<NewPlay>> { it.value.startedAt }.thenByDescending { it.index })
            .take(limit)
            .map { (index, play) -> play.toEvent(PlayEventId("play-$index")) }

    private fun NewPlay.toEvent(id: PlayEventId) =
        PlayEvent(
            id = id,
            track = track,
            startedAt = startedAt,
            utcOffsetMinutes = utcOffsetMinutes,
            listened = listened,
            trackLength = trackLength,
            skippedAt = skippedAt,
            output = output,
            previousTrack = previousTrack,
            version = version,
            source = source,
        )
}
