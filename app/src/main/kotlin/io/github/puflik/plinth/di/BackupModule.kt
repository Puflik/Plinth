package io.github.puflik.plinth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.backup.CoreJournalMirror
import io.github.puflik.plinth.backup.DataStoreMirrorSettings
import io.github.puflik.plinth.backup.JournalMirror
import io.github.puflik.plinth.backup.MirrorFolder
import io.github.puflik.plinth.backup.MirrorSettings
import io.github.puflik.plinth.backup.MirrorWriter
import io.github.puflik.plinth.backup.SafMirrorFolder
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** Копия журнала в папке человека (C4): фасад ядра, папка SAF, выбор папки и писатель. */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideJournalMirror(
        core: PlinthCore,
        @IoDispatcher io: CoroutineDispatcher,
    ): JournalMirror = CoreJournalMirror(core, io)

    @Provides
    @Singleton
    fun provideMirrorFolder(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): MirrorFolder = SafMirrorFolder(context.contentResolver, io)

    @Provides
    fun provideMirrorSettings(store: DataStore<Preferences>): MirrorSettings = DataStoreMirrorSettings(store)

    /** Один на процесс: он помнит, что уже записано. */
    @Provides
    @Singleton
    fun provideMirrorWriter(
        mirror: JournalMirror,
        folder: MirrorFolder,
        settings: MirrorSettings,
        @ApplicationScope scope: CoroutineScope,
    ): MirrorWriter = MirrorWriter(mirror, folder, settings, scope)
}
