package io.github.puflik.plinth.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.startup.DataStoreOnboardingSettings
import io.github.puflik.plinth.startup.OnboardingSettings

/**
 * Первый запуск (F): мастер пишет в тот же файл DataStore, что и настройки
 * библиотеки, — второй файл ради пары ключей не нужен.
 */
@Module
@InstallIn(SingletonComponent::class)
object StartupModule {
    @Provides
    fun provideOnboardingSettings(store: DataStore<Preferences>): OnboardingSettings =
        DataStoreOnboardingSettings(store)
}
