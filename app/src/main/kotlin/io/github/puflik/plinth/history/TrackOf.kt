package io.github.puflik.plinth.history

import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.UserDataRepository

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
