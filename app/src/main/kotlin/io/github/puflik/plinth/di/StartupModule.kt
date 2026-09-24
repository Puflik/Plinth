package io.github.puflik.plinth.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.audio.QueueKeeper
import io.github.puflik.plinth.settings.DataStoreStartSettings
import io.github.puflik.plinth.settings.StartSettings
import io.github.puflik.plinth.startup.DataStoreOnboardingSettings
import io.github.puflik.plinth.startup.OnboardingSettings
import io.github.puflik.plinth.startup.PlayHistory
import io.github.puflik.plinth.startup.StartLog
import javax.inject.Singleton

/**
 * Первый запуск и стартовый экран (F): мастер и настройка стартового экрана
 * пишут в тот же файл DataStore, что и настройки библиотеки, — второй файл
 * ради пары ключей не нужен. Когда последний раз играло, знает `QueueKeeper`.
 */
@Module
@InstallIn(SingletonComponent::class)
object StartupModule {
    @Provides
    fun provideOnboardingSettings(store: DataStore<Preferences>): OnboardingSettings =
        DataStoreOnboardingSettings(store)

    @Provides
    fun provideStartSettings(store: DataStore<Preferences>): StartSettings = DataStoreStartSettings(store)

    @Provides
    fun providePlayHistory(keeper: QueueKeeper): PlayHistory = keeper

    /** Решение последнего запуска живёт, пока жив процесс: его объясняют и из настроек. */
    @Provides
    @Singleton
    fun provideStartLog(): StartLog = StartLog()
}
