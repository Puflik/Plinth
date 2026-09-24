package io.github.puflik.plinth.diagnostics.vendor

/** Почему процесс закончился в прошлый раз — по `ApplicationExitInfo`, крупно (G2.1). */
enum class ExitReason {
    /** Остановил человек: «Остановить» в настройках, диспетчер задач. */
    USER,

    /** Наш сбой или «приложение не отвечает». */
    CRASH,

    /** Обновление приложения или смена разрешений. */
    UPDATE,

    /** Система: нехватка памяти, сигнал, прочее — то, чем убивают прошивки. */
    SYSTEM,

    /** Android 10 и старше причину не называет. */
    UNKNOWN,
}

/**
 * Убили ли игру (G2.1): процесс умер, пока звук шёл, и не по воле человека,
 * не от нашего сбоя и не из-за обновления. Где причина неизвестна, свой
 * сбой видно по оставленному отчёту о нём ([crashed]).
 */
object KillVerdict {
    fun killed(
        wasPlaying: Boolean,
        exit: ExitReason,
        crashed: Boolean,
    ): Boolean =
        wasPlaying &&
            when (exit) {
                ExitReason.SYSTEM -> true
                ExitReason.UNKNOWN -> !crashed
                ExitReason.USER, ExitReason.CRASH, ExitReason.UPDATE -> false
            }
}

/** Итог проверки при старте процесса: прошлую игру убили. */
class KillReport(
    val detected: Boolean,
)
