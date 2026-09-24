package io.github.puflik.plinth.di

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.diagnostics.log.Logger
import io.github.puflik.plinth.ffi.CoreInitializer
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** Rust-ядро (A3): один фасад на процесс — ядро внутри тоже одно. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun providePlinthCore(logger: Logger): PlinthCore = PlinthCore(logger.minLevel)

    @Provides
    fun provideCoreInitializer(
        core: PlinthCore,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): CoreInitializer = CoreInitializer(core, scope, io)
}

/** Ядро для инструментальных тестов, куда Hilt не внедряет. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoreEntryPoint {
    fun plinthCore(): PlinthCore
}
