package io.github.puflik.plinth.queue

import kotlin.random.Random

/**
 * Порядок обхода контекста (D1.3). Shuffle перемешивает не саму очередь, а
 * порядок, в котором её проходят: выключил — вернулся к исходному порядку
 * на том же треке.
 */
object ShuffleOrder {
    fun plain(size: Int): List<Int> = List(size) { it }

    /** Случайный порядок из [size] индексов, который начинается с [first] — с того, что уже играет. */
    fun shuffled(
        size: Int,
        first: Int,
        random: Random,
    ): List<Int> {
        require(first in 0 until size) { "первый индекс $first вне 0 until $size" }
        return listOf(first) + (plain(size) - first).shuffled(random)
    }
}
