package io.github.puflik.plinth

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Точка входа процесса (A2.1).
 *
 * Аннотация запускает кодогенерацию Hilt: без неё `@AndroidEntryPoint`
 * не к чему подключаться. Инициализация логгера появится в эпике G1.
 */
@HiltAndroidApp
class PlinthApplication : Application()
