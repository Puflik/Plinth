package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.PlayEvent
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.flow.Flow

/**
 * Пользовательское о треках (D4): лайки и история прослушиваний.
 *
 * Пишется только в журнал ядра, мимо журнала — никогда
 * (docs/adr/0007-journal-as-source-of-truth.md). Реализация —
 * `CoreUserDataRepository`; требования — `UserDataRepositoryContractTest`,
 * тот же, что проходит `FakeUserDataRepository`.
 *
 * Отказ ядра фасад сам отдаёт в `CoreErrors` и не бросает: лайк, который не
 * записался, не должен ронять экран.
 */
interface UserDataRepository {
    /**
     * Трек фонотеки, который играет из файла [uri]. Плеер знает только файл,
     * история и лайки — трек. Файл не из фонотеки (открыт через SAF) — `null`.
     */
    suspend fun trackAt(uri: String): TrackId?

    /** Лайк [track]; поток меняется, когда лайк ставят или снимают. */
    fun liked(track: TrackId): Flow<Boolean>

    suspend fun setLiked(
        track: TrackId,
        liked: Boolean,
    )

    /** Записывает прослушивание; засчитать ли его в счётчик, решает ядро (правило Last.fm). */
    suspend fun recordPlay(play: NewPlay)

    /** Последние [limit] прослушиваний, новые первыми. */
    suspend fun recentPlays(limit: Int): List<PlayEvent>
}
