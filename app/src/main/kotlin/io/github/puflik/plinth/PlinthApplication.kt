package io.github.puflik.plinth

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.puflik.plinth.audio.QueueKeeper
import io.github.puflik.plinth.diagnostics.AppInfo
import io.github.puflik.plinth.diagnostics.CrashHandler
import io.github.puflik.plinth.diagnostics.CrashStore
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.diagnostics.log.LogRedactor
import io.github.puflik.plinth.diagnostics.log.Logger
import io.github.puflik.plinth.diagnostics.vendor.KillReport
import io.github.puflik.plinth.diagnostics.vendor.KillWatch
import javax.inject.Inject
import kotlin.time.Clock

/**
 * Точка входа процесса (A2.1).
 *
 * Аннотация запускает кодогенерацию Hilt: без неё `@AndroidEntryPoint`
 * не к чему подключаться. Инициализация логгера появится в эпике G1.
 *
 * WorkManager настраивается отсюда, по первому обращению (C2.4): воркеры
 * строит Hilt, иначе `ScanWorker` не получит сканер. Автоматическая
 * инициализация WorkManager поэтому выключена в манифесте.
 */
@HiltAndroidApp
class PlinthApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var queueKeeper: QueueKeeper

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var appInfo: AppInfo

    @Inject
    lateinit var crashes: CrashStore

    @Inject
    lateinit var clock: Clock

    // Ленивый: проверка «убили ли прошлую игру» пишет в лог — только после установки логгера.
    @Inject
    lateinit var killReport: dagger.Lazy<KillReport>

    @Inject
    lateinit var killWatch: KillWatch

    override fun onCreate() {
        super.onCreate()
        // Лог — первым: всё, что случится дальше при старте, уже в нём (G1).
        AppLog.install(logger)
        AppLog.i(TAG, appInfo.line)
        // Сбой — отчётом на диск, дальше системе; при следующем запуске его предложат сохранить (G1.3).
        CrashHandler.install(crashes, LogRedactor(), clock, appInfo)
        // Убили ли прошлую игру — до того, как метка начнёт следить за новой (G2).
        killReport.get()
        killWatch.start()
        // Очередь возвращается при старте процесса, а не экрана: её ждёт и служба воспроизведения.
        queueKeeper.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private companion object {
        const val TAG = "App"
    }
}
