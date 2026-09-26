package io.github.puflik.plinth.queue

import kotlin.random.Random

/**
 * Очередь воспроизведения (D1.1) — неизменяемое значение; каждое действие
 * возвращает новую очередь.
 *
 * Очередь = контекст (альбом, папка, поиск…) плюс ручной блок. Ручные треки
 * играют сразу после текущего, потом контекст продолжается; новый контекст
 * заменяет старый, но ручной блок переживает его. Сыгравший ручной трек из
 * очереди уходит. Shuffle — [порядок обхода][order] контекста, сами
 * [contextItems] не переставляются.
 *
 * @property order порядок обхода: индексы [contextItems].
 * @property position место текущего (или последнего сыгравшего, пока играет
 *   ручной трек) трека контекста в [order].
 * @property playingManual ручной трек, который играет сейчас.
 * @property upNext ручной блок — ещё не сыгравшие ручные треки.
 */
data class PlaybackQueue(
    val context: QueueContext? = null,
    val contextItems: List<QueueItem> = emptyList(),
    val order: List<Int> = emptyList(),
    val position: Int = 0,
    val playingManual: QueueItem? = null,
    val upNext: List<QueueItem> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    init {
        require(
            order.sorted() == ShuffleOrder.plain(contextItems.size),
        ) { "порядок обхода — не перестановка контекста" }
        require(contextItems.isEmpty() || position in order.indices) { "позиция $position вне контекста" }
    }

    /** Что играет; `null` — очередь ещё не начата. */
    val current: QueueItem? get() = playingManual ?: order.getOrNull(position)?.let(contextItems::get)

    /**
     * Место того, что играет: ручной трек или место в контексте. Равные
     * значения — играет то же самое: shuffle меняет порядок обхода, но не
     * место, а тот же трек дальше по контексту — уже другое место.
     */
    val playing: Any? get() = playingManual ?: order.getOrNull(position)?.let { contextItems to it }

    /** Что сыграет дальше без повтора: ручной блок, затем остаток контекста. */
    val upcoming: List<QueueItem> get() = upNext + order.drop(position + 1).map(contextItems::get)

    /** Играть [items] контекстом [context] с трека [start]; ручной блок сохраняется. */
    fun play(
        context: QueueContext,
        items: List<QueueItem>,
        start: Int,
        random: Random = Random,
    ): PlaybackQueue {
        require(start in items.indices) { "стартовый трек $start вне контекста из ${items.size}" }
        val order = if (shuffle) ShuffleOrder.shuffled(items.size, start, random) else ShuffleOrder.plain(items.size)
        return copy(
            context = context,
            contextItems = items,
            order = order,
            position = order.indexOf(start),
            playingManual = null,
        )
    }

    /** «Заменить очередь»: как [play], но ручной блок пропадает. */
    fun replace(
        context: QueueContext,
        items: List<QueueItem>,
        start: Int,
        random: Random = Random,
    ): PlaybackQueue = copy(upNext = emptyList()).play(context, items, start, random)

    fun perform(
        action: QueueAction,
        item: QueueItem,
    ): PlaybackQueue =
        when (action) {
            QueueAction.PLAY_NEXT -> copy(upNext = listOf(item) + upNext)
            QueueAction.ADD_TO_QUEUE -> copy(upNext = upNext + item)
        }

    /**
     * Следующий трек. [auto] — прошлый доиграл сам: тогда repeat one играет
     * его заново, а пропуск пользователем идёт дальше. `null` — играть
     * больше нечего.
     */
    fun next(auto: Boolean = false): PlaybackQueue? {
        val next = position + 1
        return when {
            auto && repeat == RepeatMode.ONE && current != null -> this
            upNext.isNotEmpty() -> copy(playingManual = upNext.first(), upNext = upNext.drop(1))
            next < order.size -> copy(position = next, playingManual = null)
            repeat != RepeatMode.OFF && order.isNotEmpty() -> copy(position = 0, playingManual = null)
            else -> null
        }
    }

    /**
     * Предыдущий трек контекста. С ручного трека — к треку контекста, что
     * играл перед ним; с первого — на нём же, а с повтором — к последнему.
     */
    fun previous(): PlaybackQueue =
        when {
            playingManual != null -> copy(playingManual = null)
            position > 0 -> copy(position = position - 1)
            repeat != RepeatMode.OFF && order.isNotEmpty() -> copy(position = order.lastIndex)
            else -> this
        }

    /** Включить или выключить shuffle, не сходя с текущего трека контекста. */
    fun withShuffle(
        on: Boolean,
        random: Random = Random,
    ): PlaybackQueue {
        val playing = order.getOrNull(position) ?: return copy(shuffle = on)
        val order = if (on) ShuffleOrder.shuffled(order.size, playing, random) else ShuffleOrder.plain(order.size)
        return copy(shuffle = on, order = order, position = order.indexOf(playing))
    }

    fun withRepeat(mode: RepeatMode): PlaybackQueue = copy(repeat = mode)

    /**
     * Убрать трек с места [index] в [upcoming] (E4). Трек контекста уходит из
     * контекста совсем: «назад» к нему уже не вернёт.
     */
    fun remove(index: Int): PlaybackQueue {
        require(index in upcoming.indices) { "места $index нет среди ${upcoming.size} следующих треков" }
        return if (index < upNext.size) copy(upNext = upNext.withoutAt(index)) else withoutContextAt(orderIndex(index))
    }

    /**
     * Переставить трек с места [from] на место [to] в [upcoming] (E4). Место
     * внутри ручного блока (меньше его длины) делает трек ручным, место за ним
     * — треком контекста. Перестановка внутри контекста меняет порядок обхода,
     * а не сами треки: без shuffle альбом вернётся к своему порядку.
     */
    fun move(
        from: Int,
        to: Int,
    ): PlaybackQueue {
        require(from in upcoming.indices && to in upcoming.indices) {
            "перенос $from → $to вне ${upcoming.size} следующих треков"
        }
        val fromManual = from < upNext.size
        val toManual = to < upNext.size
        val item = upcoming[from]
        return when {
            fromManual && toManual -> copy(upNext = upNext.withoutAt(from).withAt(to, item))
            !fromManual && !toManual -> {
                val moved = order[orderIndex(from)]
                copy(order = order.withoutAt(orderIndex(from)).withAt(orderIndex(to), moved))
            }
            toManual -> withoutContextAt(orderIndex(from)).let { it.copy(upNext = it.upNext.withAt(to, item)) }
            // Ручной трек уходит из ручного блока: блок стал на один короче.
            else ->
                copy(
                    upNext = upNext.withoutAt(from),
                    contextItems = contextItems + item,
                    order = order.withAt(position + 1 + to - (upNext.size - 1), contextItems.size),
                )
        }
    }

    /** Место в [order] трека контекста, стоящего на месте [index] в [upcoming]. */
    private fun orderIndex(index: Int): Int = position + 1 + index - upNext.size

    /** Без трека контекста, что стоит в [order] на месте [orderIndex]; индексы после него сдвигаются. */
    private fun withoutContextAt(orderIndex: Int): PlaybackQueue {
        val removed = order[orderIndex]
        return copy(
            contextItems = contextItems.withoutAt(removed),
            order = order.withoutAt(orderIndex).map { if (it > removed) it - 1 else it },
        )
    }

    private fun <T> List<T>.withoutAt(index: Int): List<T> = toMutableList().apply { removeAt(index) }

    private fun <T> List<T>.withAt(
        index: Int,
        element: T,
    ): List<T> = toMutableList().apply { add(index, element) }

    companion object {
        val EMPTY = PlaybackQueue()
    }
}
