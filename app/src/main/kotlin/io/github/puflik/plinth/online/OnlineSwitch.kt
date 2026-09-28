package io.github.puflik.plinth.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Переключатель «Онлайн-источники» ведёт ядро (E3): при запуске и при каждой
 * смене источники включаются или выключаются. Запускает `PlinthApplication`.
 */
class OnlineSwitch(
    private val settings: OnlineSettings,
    private val online: OnlineRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { settings.enabled.distinctUntilChanged().collect { online.setEnabled(it) } }
    }
}
