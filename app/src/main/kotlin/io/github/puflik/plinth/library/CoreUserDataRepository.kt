package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.PlayEvent
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

/**
 * Лайки и история поверх журнала ядра (D4a). Требования —
 * `UserDataRepositoryContractTest`, тот же, что проходит `FakeUserDataRepository`.
 *
 * Лайк перечитывается по сигналу [PlinthCore.userDataChanges] — его двигает
 * каждая запись в журнал. Вызовы ядра блокирующие и идут в [io]. Отказ ядра
 * уже ушёл в `CoreErrors`; здесь он только пишется в лог.
 */
class CoreUserDataRepository(
    private val core: PlinthCore,
    private val io: CoroutineDispatcher,
) : UserDataRepository {
    override suspend fun trackAt(uri: String): TrackId? = attempt(io, TAG, "track lookup") { core.library.trackAt(uri) }

    override fun liked(track: TrackId): Flow<Boolean> =
        core.userDataChanges
            .mapNotNull { quietly(TAG, "like read") { core.library.userData(track).liked } }
            .distinctUntilChanged()
            .flowOn(io)

    override suspend fun setLiked(
        track: TrackId,
        liked: Boolean,
    ) {
        attempt(io, TAG, "like") { if (liked) core.journal.like(track) else core.journal.unlike(track) }
    }

    override suspend fun recordPlay(play: NewPlay) {
        attempt(io, TAG, "play record") { core.journal.recordPlay(play) }
    }

    override suspend fun recentPlays(limit: Int): List<PlayEvent> =
        attempt(io, TAG, "history read") { core.journal.recentPlays(limit) }.orEmpty()

    private companion object {
        const val TAG = "UserData"
    }
}
