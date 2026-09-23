package io.github.puflik.plinth.di

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.media3.ExoPlayerFactory
import io.github.puflik.plinth.audio.media3.Media3Engine
import javax.inject.Singleton

/**
 * Аудиограф (B3): один плеер и один движок на процесс.
 *
 * Плеер делят движок (через него управляет UI) и `PlaybackService` (через
 * его сессию — уведомление, гарнитура, экран блокировки), поэтому он
 * синглтон. Живёт на главном looper: так требует `MediaSessionService`.
 * Отсюда же ограничение — впервые получить движок или плеер можно только
 * в главном потоке; Activity, службы и `ViewModel` получают их там.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    @Provides
    @Singleton
    fun providePlayer(
        @ApplicationContext context: Context,
    ): ExoPlayer = ExoPlayerFactory(context).create(Looper.getMainLooper())

    @Provides
    @Singleton
    fun provideAudioEngine(player: ExoPlayer): AudioEngine = Media3Engine(player)
}

/**
 * Доступ к движку оттуда, куда Hilt не внедряет: инструментальные тесты,
 * в будущем — виджеты и приёмники.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioEntryPoint {
    fun audioEngine(): AudioEngine
}
