package io.github.puflik.plinth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.audio.engine.StreamResolver
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.online.CoreOnlineRepository
import io.github.puflik.plinth.online.DataStoreOnlineSettings
import io.github.puflik.plinth.online.DeviceDecoders
import io.github.puflik.plinth.online.HttpUrlTransport
import io.github.puflik.plinth.online.MeteredNetwork
import io.github.puflik.plinth.online.OnlineRepository
import io.github.puflik.plinth.online.OnlineSettings
import io.github.puflik.plinth.online.OnlineStreams
import io.github.puflik.plinth.online.OnlineSwitch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** Онлайн-источники (E3): ядро за сетью `HttpURLConnection`, переключатель и адрес потока для плеера. */
@Module
@InstallIn(SingletonComponent::class)
object OnlineModule {
    @Provides
    @Singleton
    fun provideOnlineRepository(
        core: PlinthCore,
        @IoDispatcher io: CoroutineDispatcher,
    ): OnlineRepository = CoreOnlineRepository(core, HttpUrlTransport(), io)

    @Provides
    fun provideOnlineSettings(store: DataStore<Preferences>): OnlineSettings = DataStoreOnlineSettings(store)

    @Provides
    @Singleton
    fun provideOnlineSwitch(
        settings: OnlineSettings,
        online: OnlineRepository,
        @ApplicationScope scope: CoroutineScope,
    ): OnlineSwitch = OnlineSwitch(settings, online, scope)

    @Provides
    @Singleton
    fun provideStreamResolver(
        online: OnlineRepository,
        @ApplicationContext context: Context,
    ): StreamResolver = OnlineStreams(online, DeviceDecoders::missing, MeteredNetwork(context))
}
