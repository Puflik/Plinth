package io.github.puflik.plinth.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.radio.RadioLauncher
import io.github.puflik.plinth.radio.RadioStarter

/** Пункт «Радио» (Ю4в): экраны просят [RadioLauncher], а запускает его [RadioStarter]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RadioModule {
    @Binds
    abstract fun launcher(starter: RadioStarter): RadioLauncher
}
