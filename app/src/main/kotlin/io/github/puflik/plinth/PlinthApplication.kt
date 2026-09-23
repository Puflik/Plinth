package io.github.puflik.plinth

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
