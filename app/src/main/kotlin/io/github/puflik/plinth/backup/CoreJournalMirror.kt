package io.github.puflik.plinth.backup

import io.github.puflik.plinth.ffi.MirrorFile
import io.github.puflik.plinth.ffi.MirrorFound
import io.github.puflik.plinth.ffi.MirrorRestore
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.attempt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * Копия журнала поверх ядра (C4). Требования — `JournalMirrorContractTest`.
 * Вызовы ядра блокирующие и идут в [io].
 */
class CoreJournalMirror(
    private val core: PlinthCore,
    private val io: CoroutineDispatcher,
) : JournalMirror {
    override val changes: Flow<Long> = core.userDataChanges

    override suspend fun copy(): MirrorFile? = attempt(io, TAG, "copy") { core.mirror.copy() }

    override suspend fun inspect(files: List<MirrorFile>): MirrorFound? =
        attempt(io, TAG, "inspection") { core.mirror.inspect(files) }

    override suspend fun restore(files: List<MirrorFile>): MirrorRestore? =
        attempt(io, TAG, "restore") { core.mirror.restore(files) }

    private companion object {
        const val TAG = "Mirror"
    }
}
