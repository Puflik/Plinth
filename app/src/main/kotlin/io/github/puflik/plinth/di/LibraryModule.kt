package io.github.puflik.plinth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.library.FolderSettings
import io.github.puflik.plinth.library.LibraryRepository
import io.github.puflik.plinth.library.LibraryScan
import io.github.puflik.plinth.library.RoomLibraryRepository
import io.github.puflik.plinth.library.db.PlinthDatabase
import io.github.puflik.plinth.library.db.dao.TrackDao
import io.github.puflik.plinth.library.scan.DataStoreFolderSettings
import io.github.puflik.plinth.library.scan.LibraryScanner
import io.github.puflik.plinth.library.scan.MediaStoreSource
import io.github.puflik.plinth.library.scan.ScanSource
import io.github.puflik.plinth.library.scan.WorkManagerLibraryScan
import io.github.puflik.plinth.library.sort.SortKeys
import io.github.puflik.plinth.settings.DataStoreSortSettings
import io.github.puflik.plinth.settings.SortSettings
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * Граф фонотеки (C2, C3): одна база на процесс, фасад поверх неё, сканер.
 *
 * База — синглтон: Room держит пул соединений и следит за изменениями
 * таблиц, чтобы списки-потоки обновлялись сами; вторая копия на тот же файл
 * не увидела бы записей первой. Экраны получают только [LibraryRepository]
 * и [LibraryScan]; сам сканер нужен лишь `ScanWorker`.
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): PlinthDatabase = Room.databaseBuilder(context, PlinthDatabase::class.java, PlinthDatabase.NAME).build()

    @Provides
    @Singleton
    fun provideSettingsStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("library") }

    @Provides
    fun provideSortSettings(store: DataStore<Preferences>): SortSettings = DataStoreSortSettings(store)

    @Provides
    fun provideFolderSettings(store: DataStore<Preferences>): FolderSettings = DataStoreFolderSettings(store)

    @Provides
    fun provideTrackDao(database: PlinthDatabase): TrackDao = database.trackDao()

    /** Артикли по умолчанию; настраиваемый список придёт вместе с экраном настроек. */
    @Provides
    fun provideSortKeys(): SortKeys = SortKeys()

    @Provides
    @Singleton
    fun provideLibraryRepository(
        dao: TrackDao,
        keys: SortKeys,
    ): LibraryRepository = RoomLibraryRepository(dao, keys)

    @Provides
    fun provideScanSource(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): ScanSource = MediaStoreSource(context.contentResolver, io)

    @Provides
    fun provideLibraryScanner(
        source: ScanSource,
        repository: LibraryRepository,
    ): LibraryScanner = LibraryScanner(source, repository)

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
