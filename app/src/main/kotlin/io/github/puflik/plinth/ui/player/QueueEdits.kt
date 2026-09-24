package io.github.puflik.plinth.ui.player

import io.github.puflik.plinth.queue.QueueItem

/** Правка очереди из панели: место в списке и трек, который там должен стоять. */
class QueueEdits(
    val onRemove: (index: Int, item: QueueItem) -> Unit,
    val onMove: (from: Int, to: Int, item: QueueItem) -> Unit,
)
