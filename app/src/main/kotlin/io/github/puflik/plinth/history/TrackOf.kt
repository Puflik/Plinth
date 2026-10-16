package io.github.puflik.plinth.history

import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.UserDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Трек фонотеки, который играет из [source]: сетевой (E3) — своим ID, файл —
 * тот, что фонотека знает по пути. Файл не из фонотеки и готовый адрес —
 * `null`: у них нет ни истории, ни лайка.
 */
suspend fun UserDataRepository.trackOf(source: AudioSource): TrackId? =
    when (source) {
        is AudioSource.Online -> TrackId(source.track)
        is AudioSource.LocalFile -> trackAt(source.uri)
        is AudioSource.Remote -> null
    }

/**
 * Как [trackOf], но поток: файл фонотеки приходит новым треком, когда перепривязка
 * (скан после переустановки, «Восстановить») перевела его на другой ID. Сетевой трек
 * и файл не из фонотеки не меняются.
 */
fun UserDataRepository.trackChangesOf(source: AudioSource): Flow<TrackId?> =
    when (source) {
        is AudioSource.Online -> flowOf(TrackId(source.track))
        is AudioSource.LocalFile -> trackAtChanges(source.uri)
        is AudioSource.Remote -> flowOf(null)
    }
