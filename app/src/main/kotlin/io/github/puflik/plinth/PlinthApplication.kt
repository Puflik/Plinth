package io.github.puflik.plinth

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.puflik.plinth.audio.QueueKeeper
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.diagnostics.log.Logger
import io.github.puflik.plinth.flavor.FlavorConfig
import javax.inject.Inject

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

    override fun onCreate() {
        super.onCreate()
        // Лог — первым: всё, что случится дальше при старте, уже в нём (G1).
        AppLog.install(logger)
        AppLog.i(TAG, startLine())
        // Очередь возвращается при старте процесса, а не экрана: её ждёт и служба воспроизведения.
        queueKeeper.start()
    }

    /** Версия, сборка и устройство — без чего разбирать лог из issue бессмысленно. */
    private fun startLine(): String =
        "Plinth ${BuildConfig.VERSION_NAME} (${FlavorConfig.NAME}), " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private companion object {
        const val TAG = "App"
    }
}
