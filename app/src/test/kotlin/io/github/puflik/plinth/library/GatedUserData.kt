package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.CompletableDeferred

/**
 * Хранилище лайков, у которого поиск трека по пути можно придержать: каждый поиск,
 * начатый после установки [gate], ждёт, пока его завершат. Для проверок «ID берётся в
 * момент записи» и «запись не зависит от области плеера» (Р9а).
 */
class GatedUserData(
    private val inner: FakeUserDataRepository,
) : UserDataRepository by inner {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun trackAt(uri: String): TrackId? {
        gate?.await()
        return inner.trackAt(uri)
    }
}
