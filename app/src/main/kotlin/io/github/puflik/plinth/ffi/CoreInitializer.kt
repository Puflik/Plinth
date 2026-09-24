package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Запуск ядра при старте приложения (A3.2), в фоне: загрузка .so не
 * задерживает первый кадр. Первая запись ядра — «Plinth core … ready» в
 * `files/logs/plinth.log`.
 *
 * Пока ни одна функция приложения от ядра не зависит, несостоявшийся запуск —
 * ошибка в логе, а не падение: v0.1 должен работать как работал. Кто позовёт
 * ядро, получит ту же ошибку исключением ([PlinthCore] пробует снова).
 */
class CoreInitializer(
    private val core: PlinthCore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    fun start() {
        scope.launch(io) {
            runCatching { core.start() }.onFailure { AppLog.e(TAG, "core did not start", it) }
        }
    }

    private companion object {
        const val TAG = "Core"
    }
}
