package io.github.puflik.plinth.audio.engine

/**
 * Типизированные ошибки воспроизведения (B1.2).
 *
 * Тип ошибки определяет реакцию приложения: недоступный файл — пропустить
 * трек и пометить его в библиотеке, неподдержанный формат — сказать об этом
 * прямо, сеть — предложить повтор. Поэтому ошибки перечислены типами, а не
 * кодами: `when` по ним проверяется компилятором.
 *
 * [detail] — техническая подробность для лога (код ошибки декодера, имя
 * файла). В интерфейсе пользователя она не показывается как есть.
 */
sealed interface PlaybackError {
    val detail: String?

    /** Файла нет, доступ к нему отозван, устройство отключено. */
    data class SourceUnavailable(
        override val detail: String? = null,
    ) : PlaybackError

    /** Контейнер или кодек движку неизвестен. */
    data class UnsupportedFormat(
        override val detail: String? = null,
    ) : PlaybackError

    /** Сеть недоступна или поток оборвался. */
    data class Network(
        override val detail: String? = null,
    ) : PlaybackError

    /** Всё остальное: движок не смог объяснить причину. */
    data class Unknown(
        override val detail: String? = null,
    ) : PlaybackError
}
