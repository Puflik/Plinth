package io.github.puflik.plinth.online.youtube

import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Сроки вызовов источника: [search] — поиск, [extract] — `resolve` и `stream` с извлечением. */
internal data class YoutubeBudgets(
    val search: Duration = 10.seconds,
    val extract: Duration = 25.seconds,
)

/**
 * Срок вызова на потоке. Ядро ждёт [YoutubeSource] на своём потоке и прервать вызов не может, а экстрактор за один
 * вызов спрашивает сеть не раз: срок проверяется перед каждым запросом ([failIfSpent]), а таймауты соединения и чтения
 * загрузчика не больше остатка ([leftMillis]). Один блок чтения может пережить срок, но не дольше самого таймаута.
 * Срок живёт только свой вызов: [within] возвращает прежний (или снимает).
 */
internal object CallBudget {
    private val current = ThreadLocal<Span>()

    /** Выполняет [block] со сроком [budget] на этом потоке. */
    fun <T> within(
        budget: Duration,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(Span(budget.inWholeNanoseconds))
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** Сколько осталось, в миллисекундах вверх: `null` — срока нет, 0 — срок вышел. */
    fun leftMillis(): Long? = current.get()?.leftMillis()

    /** Срок вышел — [SocketTimeoutException], как у таймаута чтения; срока нет — ничего. */
    fun failIfSpent() {
        if (leftMillis() == 0L) throw SocketTimeoutException()
    }

    /** Срок от создания, по монотонным часам. */
    private class Span(
        private val budgetNanos: Long,
    ) {
        private val started = System.nanoTime()

        fun leftMillis(): Long {
            val nanos = (budgetNanos - (System.nanoTime() - started)).coerceAtLeast(0L)
            return nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI == 0L) 0 else 1
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
