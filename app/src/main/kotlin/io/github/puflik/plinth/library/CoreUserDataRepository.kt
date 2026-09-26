package io.github.puflik.plinth.library

import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.PlayEvent
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

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
    override suspend fun trackAt(uri: String): TrackId? = attempt("track lookup") { core.library.trackAt(uri) }

    override fun liked(track: TrackId): Flow<Boolean> =
        core.userDataChanges
            .mapNotNull { quietly("like read") { core.library.userData(track).liked } }
            .distinctUntilChanged()
            .flowOn(io)

    override suspend fun setLiked(
        track: TrackId,
        liked: Boolean,
    ) {
        attempt("like") { if (liked) core.journal.like(track) else core.journal.unlike(track) }
    }

    override suspend fun recordPlay(play: NewPlay) {
        attempt("play record") { core.journal.recordPlay(play) }
    }

    override suspend fun recentPlays(limit: Int): List<PlayEvent> =
        attempt("history read") { core.journal.recentPlays(limit) }.orEmpty()

    private suspend fun <T : Any> attempt(
        what: String,
        call: () -> T?,
    ): T? = withContext(io) { quietly(what, call) }

    private fun <T : Any> quietly(
        what: String,
        call: () -> T?,
    ): T? =
        try {
            call()
        } catch (failure: CoreFailure) {
            AppLog.w(TAG, "$what failed", failure)
            null
        }

    private companion object {
        const val TAG = "UserData"
    }
}
