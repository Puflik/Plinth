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
 * не от нашего сбоя, не из-за обновления и не из-за перезагрузки или разряда
 * ([rebooted], ревью №9 — после перезагрузки причины смерти процесса нет ни
 * на одной версии Android). Где причина неизвестна, свой сбой видно по
 * оставленному отчёту о нём ([crashed]).
 */
object KillVerdict {
    fun killed(
        wasPlaying: Boolean,
        exit: ExitReason,
        crashed: Boolean,
        rebooted: Boolean = false,
    ): Boolean =
        wasPlaying &&
            !rebooted &&
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
