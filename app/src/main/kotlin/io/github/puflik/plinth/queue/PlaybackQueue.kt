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

    companion object {
        val EMPTY = PlaybackQueue()
    }
}
