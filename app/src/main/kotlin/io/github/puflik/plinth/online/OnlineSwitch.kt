package io.github.puflik.plinth.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Переключатель «Онлайн-источники» и выбор провайдеров ведут ядро (E3, Ю3г): при запуске и при каждой
 * смене источники включаются или выключаются, а набор провайдеров — меняется. Запускает `PlinthApplication`.
 */
class OnlineSwitch(
    private val settings: OnlineSettings,
    private val online: OnlineRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            combine(settings.enabled, settings.disabledProviders) { enabled, disabled ->
                // Пока главный выключен, выбор провайдеров ядру ни к чему: его смена не должна дёргать подключение.
                if (enabled) Applied(true, disabled) else Applied(false, emptySet())
            }.distinctUntilChanged().collect { online.setEnabled(it.enabled, it.disabled) }
        }
    }

    private data class Applied(
        val enabled: Boolean,
        val disabled: Set<String>,
    )
}
