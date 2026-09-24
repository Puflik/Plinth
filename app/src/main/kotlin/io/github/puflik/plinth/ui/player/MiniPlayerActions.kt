package io.github.puflik.plinth.ui.player

/** Что мини-плеер умеет сделать: четыре команды одним значением. */
class MiniPlayerActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onExpand: () -> Unit,
)
