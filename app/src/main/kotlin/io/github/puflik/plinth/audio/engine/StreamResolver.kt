package io.github.puflik.plinth.audio.engine

/**
 * Адрес сетевого трека ([AudioSource.Online]) в момент загрузки (E3):
 * движок спрашивает его, когда начинает читать поток, — по типу сети и
 * здоровью провайдеров на эту минуту, а не на ту, когда трек встал в очередь.
 * Зовётся из потока загрузки движка и может блокировать.
 */
fun interface StreamResolver {
    /** @param track ID трека фонотеки — [AudioSource.Online.track]. */
    fun resolve(track: String): StreamLookup
}

/** Что ответил [StreamResolver]; от ответа зависит, пропустит ли очередь трек (G3). */
sealed interface StreamLookup {
    data class Found(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : StreamLookup

    /** Играть нечем: источники выключены или у трека нет сетевых вариантов — как пропавший файл. */
    data object Unavailable : StreamLookup

    /** Нет сети. */
    data object NoNetwork : StreamLookup

    /** Всё остальное: ответ провайдера не понять, ядро отказало. */
    data object Failed : StreamLookup
}
