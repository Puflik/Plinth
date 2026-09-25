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
 * [errors]. Базу, собранную заново — испорченную или отставшую от журнала, —
 * [errors] тоже узнают: «Библиотека восстановлена».
 */
class CoreInitializer(
    private val core: PlinthCore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val errors: CoreErrors,
) {
    fun start() {
        scope.launch(io) {
            runCatching { core.open() }
                .onSuccess { report ->
                    if (report.databaseRecovered || report.restoredFromJournal) {
                        AppLog.w(TAG, "core restored: $report")
                        errors.libraryRestored()
                    }
                }.onFailure { AppLog.e(TAG, "core did not open", it) }
        }
    }

    private companion object {
        const val TAG = "Core"
    }
}
