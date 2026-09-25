package io.github.puflik.plinth.audio.engine

/**
 * Откуда движок берёт звук (B1.1).
 *
 * Источник описывается строкой, а не `android.net.Uri`: тип из Android
 * сделал бы абстракцию непереносимой — см. [AudioEngine].
 * Источник ничего не знает о том, кто его выдал: SAF, сканер `MediaStore`,
 * будущий сетевой провайдер. Разбор прав доступа и поиск файла — дело
 * реализации движка.
 */
sealed interface AudioSource {
    /** Устойчивый идентификатор: по нему источники сравниваются и пишутся в лог. */
    val key: String

    /**
     * Локальный файл: `content://` от SAF, `file://` или путь к файлу —
     * так трек хранит фонотека ядра (D3b).
     */
    data class LocalFile(
        val uri: String,
    ) : AudioSource {
        init {
            require(uri.isNotBlank()) { "uri локального файла не может быть пустым" }
        }

        override val key: String get() = uri
    }

    /**
     * Поток по сети. Заголовки нужны для авторизации у провайдеров,
     * которые появятся в v0.2; движку они — просто пары строк.
     */
    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : AudioSource {
        init {
            require(url.isNotBlank()) { "url потока не может быть пустым" }
        }

        override val key: String get() = url
    }
}
