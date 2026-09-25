package io.github.puflik.plinth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.library.CoreLibraryRepository
import io.github.puflik.plinth.library.FolderSettings
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.OldLibraryCleanup
import io.github.puflik.plinth.library.permission.MediaPermission
import io.github.puflik.plinth.library.scan.AndroidStorageVolumes
import io.github.puflik.plinth.library.scan.DataStoreFolderSettings
import io.github.puflik.plinth.library.scan.LibraryScanner
import io.github.puflik.plinth.library.scan.WorkManagerLibraryScan
import io.github.puflik.plinth.settings.DataStoreSortSettings
import io.github.puflik.plinth.settings.SortSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Граф фонотеки (C2, C3, D3c): фасад поверх ядра на Rust, скан ядром, настройки.
 * Экраны получают только [LibraryRepository] и [LibraryScan]; сам сканер
 * нужен лишь `ScanWorker`.
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides
    @Singleton
    fun provideSettingsStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("library") }

    @Provides
    fun provideSortSettings(store: DataStore<Preferences>): SortSettings = DataStoreSortSettings(store)

    @Provides
    fun provideFolderSettings(store: DataStore<Preferences>): FolderSettings = DataStoreFolderSettings(store)

    /** Фонотека — ядро на Rust (D3): одна на процесс, как и само ядро. */
    @Provides
    @Singleton
    fun provideLibraryRepository(
        core: PlinthCore,
        @IoDispatcher io: CoroutineDispatcher,
    ): LibraryRepository = CoreLibraryRepository(core, io)

    @Provides
    fun provideLibraryScanner(
        @ApplicationContext context: Context,
        core: PlinthCore,
        @IoDispatcher io: CoroutineDispatcher,
    ): LibraryScanner =
        LibraryScanner(core, AndroidStorageVolumes(context), io, canRead = { MediaPermission.isGranted(context) })

    @Provides
    fun provideOldLibraryCleanup(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): OldLibraryCleanup = OldLibraryCleanup(context, scope, io)

    /** Конфигурацию WorkManager даёт `PlinthApplication`: воркеры строит Hilt. */
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideLibraryScan(workManager: WorkManager): LibraryScan = WorkManagerLibraryScan(workManager)
}

/**
 * Доступ к фонотеке оттуда, куда Hilt не внедряет: инструментальные тесты,
 * в будущем — дерево обзора `MediaSession` для Android Auto.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryEntryPoint {
    fun libraryRepository(): LibraryRepository

    fun libraryScan(): LibraryScan
}
