package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Запуск ядра при старте приложения (A3.2), в фоне: загрузка .so, открытие
 * базы и журнала не задерживают первый кадр. Первая запись ядра — «Plinth
 * core … ready» в `files/logs/plinth.log`, следом — «core opened».
 *
 * Несостоявшийся запуск — ошибка в логе, а не падение. Экраны зовут ядро
 * сами (D3c): первый же вызов попробует открыть его снова, а отказ уйдёт в
 * `CoreErrors`, где его покажет `ErrorPresenter`.
 * Восстановление при открытии — база испорчена или собрана из журнала —
 * тоже пока только в логе; «Восстанавливаю библиотеку» на экране — D3.
 */
class CoreInitializer(
    private val core: PlinthCore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    fun start() {
        scope.launch(io) {
            runCatching { core.open() }
                .onSuccess { report ->
                    if (report.databaseRecovered || report.restoredFromJournal) AppLog.w(TAG, "core restored: $report")
                }.onFailure { AppLog.e(TAG, "core did not open", it) }
        }
    }

    private companion object {
        const val TAG = "Core"
    }
}
