package io.github.puflik.plinth.ui.common

import kotlin.time.Duration

/** `3:07`: минуты и секунды; час остаётся минутами — `61:05`. */
fun formatTime(time: Duration): String {
    val seconds = time.inWholeSeconds
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

/** Сколько осталось до конца трека: `-3:13`; дальше конца не уходит. */
fun formatRemaining(
    position: Duration,
    duration: Duration,
): String = "-" + formatTime((duration - position).coerceAtLeast(Duration.ZERO))

private const val SECONDS_PER_MINUTE = 60
