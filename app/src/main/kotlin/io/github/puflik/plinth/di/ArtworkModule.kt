package io.github.puflik.plinth.di

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.artwork.ArtworkCache
import io.github.puflik.plinth.artwork.ArtworkLoader
import io.github.puflik.plinth.artwork.embedded.EmbeddedArtworkSource
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * Обложки (E2): один загрузчик и один кэш на процесс — иначе каждый экран
 * разбирал бы одни и те же файлы заново.
 */
@Module
@InstallIn(SingletonComponent::class)
object ArtworkModule {
    @Provides
    @Singleton
    fun provideArtworkLoader(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): ArtworkLoader<ImageBitmap> =
        ArtworkLoader(
            source = EmbeddedArtworkSource(context, io),
            cache = ArtworkCache(Runtime.getRuntime().maxMemory() / CACHE_SHARE, EmbeddedArtworkSource::bytesOf),
        )

    /** Кэшу — восьмая часть памяти процесса: обычная доля для картинок на Android. */
    private const val CACHE_SHARE = 8
}
