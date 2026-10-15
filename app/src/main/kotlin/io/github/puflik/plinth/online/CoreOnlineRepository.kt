package io.github.puflik.plinth.online

import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.core.CoreProblem
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.NetTransport
import io.github.puflik.plinth.ffi.OnlineCancel
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.attempt
import io.github.puflik.plinth.library.quietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Онлайн-источники поверх ядра (E3b). Требования — `OnlineRepositoryContractTest`.
 * Сеть ядру даёт [transport]; вызовы ядра блокирующие и идут в [io], кроме
 * [stream] — его зовёт загрузчик плеера из своего потока.
 */
class CoreOnlineRepository(
    private val core: PlinthCore,
    private val transport: NetTransport,
    private val io: CoroutineDispatcher,
) : OnlineRepository {
    // Вызовы поиска, пережившие отмену своей корутины: их доживает ядро, а не вызывающий.
    private val calls = CoroutineScope(SupervisorJob() + io)

    override suspend fun setEnabled(enabled: Boolean) {
        attempt(io, TAG, if (enabled) "connect" else "disconnect") {
            if (enabled) core.online.connect(transport) else core.online.disconnect()
        }
    }

    /**
     * Блокирующий вызов ядра живёт вне корутины поиска: `withContext` и
     * `coroutineScope` не вернулись бы, пока сеть не ответит, а `interrupt`
     * ни JNI, ни сокет не слышат. Отмена корутины будит ядро флагом
     * ([OnlineCancel]) и возвращается сразу; сам вызов доживает не дольше
     * остатка срока поиска и ничего не пишет.
     */
    override suspend fun search(query: String): List<OnlineSection> {
        val cancel = OnlineCancel()
        val call =
            calls.async {
                quietly(TAG, "search") { core.online.search(query, OnlineRepository.SEARCH_LIMIT, cancel) }
            }
        return try {
            call.await().orEmpty()
        } catch (cancelled: CancellationException) {
            cancel.cancel()
            throw cancelled
        }
    }

    override suspend fun album(
        provider: String,
        item: String,
    ): OnlineAlbum =
        withContext(io) {
            try {
                OnlineAlbum.Tracks(core.online.album(provider, item))
            } catch (failure: CoreFailure) {
                AppLog.w(TAG, "album failed", failure)
                OnlineAlbum.Failed(failure.problem())
            }
        }

    override suspend fun known(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId?> = attempt(io, TAG, "lookup") { core.online.known(provider, tracks) } ?: tracks.map { null }

    override suspend fun add(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId>? = attempt(io, TAG, "adding") { core.online.add(provider, tracks) }

    override fun stream(
        track: TrackId,
        metered: Boolean,
        undecodable: Set<AudioFormat>,
    ): StreamLookup =
        try {
            val address = core.online.stream(track, metered, undecodable)
            StreamLookup.Found(address.url, address.headers.toMap())
        } catch (failure: CoreFailure) {
            when (failure.error.problem) {
                CoreProblem.UNAVAILABLE -> StreamLookup.Unavailable
                CoreProblem.NETWORK -> StreamLookup.NoNetwork
                else -> {
                    AppLog.w(TAG, "stream failed", failure)
                    StreamLookup.Failed
                }
            }
        }

    private fun CoreFailure.problem(): OnlineProblem =
        when (error.problem) {
            CoreProblem.NETWORK -> OnlineProblem.NO_NETWORK
            CoreProblem.UNAVAILABLE -> OnlineProblem.PROVIDER_DOWN
            else -> OnlineProblem.BROKEN
        }

    private companion object {
        const val TAG = "Online"
    }
}
