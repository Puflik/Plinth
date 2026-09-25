package io.github.puflik.plinth.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.diagnostics.log.Logger
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreInitializer
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/** Rust-ядро (A3): один фасад на процесс — ядро внутри тоже одно. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun provideCoreErrors(): CoreErrors = CoreErrors()

    /**
     * Данные ядра — `files/core`: база, журнал и идентификатор установки.
     * Журнал — `files/core/journal`, его одного сохраняет Auto Backup (C4).
     */
    @Provides
    @Singleton
    fun providePlinthCore(
        @ApplicationContext context: Context,
        logger: Logger,
        errors: CoreErrors,
    ): PlinthCore = PlinthCore(logger.minLevel, File(context.filesDir, "core"), errors)

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
