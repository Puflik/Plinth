package io.github.puflik.plinth.core

/**
 * Ошибки приложения (G3) — что случилось по сути, а не где: показывать ли
 * это человеку, решает [ErrorPresenter], в лог оно уходит в любом случае.
 *
 * Пока это только воспроизведение: очередь пропустила треки или
 * остановилась. Отзыв разрешения на музыку сюда не входит — Android при
 * этом завершает процесс, а экран библиотеки сам объясняет, чего не хватает.
 */
sealed interface AppError {
    /**
     * Очередь пропустила [tracks] — подряд, в порядке очереди — и играет
     * дальше. [whileRestoring] — это случилось при возврате сохранённой
     * очереди на старте, человек ничего не включал.
     */
    data class TracksSkipped(
        val tracks: List<FailedTrack>,
        val whileRestoring: Boolean = false,
    ) : AppError

    /**
     * Игра остановилась на [track]: он не сыграл, а дальше идти незачем —
     * ошибка не из тех, что пропускают, очередь кончилась или все её треки
     * уже не сыграли. [skipped] — что очередь пропустила подряд перед ним.
     */
    data class PlaybackStopped(
        val track: FailedTrack,
        val skipped: List<FailedTrack> = emptyList(),
        val whileRestoring: Boolean = false,
    ) : AppError
}

/**
 * Трек, который не сыграл.
 *
 * [title] — для сообщения на экране; в лог названия треков не передаются.
 * [file] — адрес источника: по нему «один раз на файл» и пересканирование.
 */
data class FailedTrack(
    val title: String?,
    val file: String,
    val problem: TrackProblem,
)

/** Почему трек не сыграл — крупно, как это нужно человеку. */
enum class TrackProblem {
    /** Файла нет, доступ к нему отозван, носитель отключён. */
    UNAVAILABLE,

    /** Файл повреждён или его формат не поддерживается. */
    UNPLAYABLE,

    /** Сеть недоступна или поток оборвался. */
    NETWORK,

    /** Движок не смог объяснить причину. */
    UNKNOWN,
}
