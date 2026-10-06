package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.measureTimedValue

/**
 * Запуск ядра при старте приложения (A3.2), в фоне: загрузка .so, открытие
 * базы и журнала не задерживают первый кадр. Первая запись ядра — «Plinth
 * core … ready» в `files/logs/plinth.log`, следом — «core opened» и наше
 * «core opened in N ms» — сколько шло открытие после запуска ядра.
 *
 * Открывшись, ядро в той же корутине проверяет целостность базы
 * ([PlinthCore.checkIntegrity], раз в 20 запусков): полный проход по файлу не
 * держит открытие. В лог — итог и время: «integrity check: healthy in N ms» или
 * «damage found»; не очередь этого запуска — молча. Порча человеку сейчас не
 * видна: следующее открытие соберёт базу заново из журнала, и [errors]
 * скажут «Библиотека восстановлена». Отказ проверки — предупреждение в логе,
 * не в [errors].
 *
 * Несостоявшийся запуск — ошибка в логе, а не падение. Экраны зовут ядро
 * сами (D3c): первый же вызов попробует открыть его снова, а отказ уйдёт в
 * [errors]. Базу, собранную заново — испорченную, с провалившейся миграцией
 * или отставшую от журнала, — [errors] тоже узнают: «Библиотека
 * восстановлена». Журнал, начатый заново (его снимок не читался, Р1.4), —
 * отдельная весть вместо этой: «собраны заново по библиотеке» или «верните
 * из копии в папке».
 */
class CoreInitializer(
    private val core: PlinthCore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val errors: CoreErrors,
) {
    fun start() {
        scope.launch(io) {
            runCatching {
                core.start()
                measureTimedValue { core.open() }
            }.onSuccess { (report, opening) ->
                AppLog.i(TAG, "core opened in ${opening.inWholeMilliseconds} ms")
                if (report.journalStartedOver) {
                    AppLog.w(TAG, "journal started over: $report")
                    errors.journalStartedOver(carried = report.restoredFromJournal)
                } else if (report.databaseRecovered || report.restoredFromJournal) {
                    AppLog.w(TAG, "core restored: $report")
                    errors.libraryRestored()
                }
                checkIntegrity()
            }.onFailure { AppLog.e(TAG, "core did not open", it) }
        }
    }

    private fun checkIntegrity() {
        val (outcome, checking) = measureTimedValue { runCatching { core.checkIntegrity() } }
        val millis = checking.inWholeMilliseconds
        outcome
            .onSuccess {
                when (it) {
                    CoreIntegrity.NOT_DUE -> Unit
                    CoreIntegrity.HEALTHY -> AppLog.i(TAG, "integrity check: healthy in $millis ms")
                    CoreIntegrity.DAMAGED ->
                        AppLog.w(
                            TAG,
                            "integrity check: damage found in $millis ms; the library is rebuilt on the next launch",
                        )
                }
            }.onFailure { AppLog.w(TAG, "integrity check did not run", it) }
    }

    private companion object {
        const val TAG = "Core"
    }
}
