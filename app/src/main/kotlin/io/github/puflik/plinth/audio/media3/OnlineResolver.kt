package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.audio.engine.StreamResolver

/**
 * Ссылка на сетевой трек ([MediaItemMapper.onlineTrack]) → адрес потока в
 * момент загрузки (E3): вариант выбирается по сети этой минуты. Файлы и
 * готовые адреса проходят как есть.
 *
 * Загрузчик ExoPlayer открывает поток заново после перемотки и на повторе —
 * с байта уже выбранного файла. Поэтому адрес держится на всю загрузку (одну
 * ссылку [MediaItemMapper]): сменись посреди трека Wi-Fi на сотовую, FLAC не
 * превратится в MP3 с чужого места. Следующая подготовка трека — новая
 * ссылка, адрес выбирается заново. Неудача не запоминается: повтор
 * загрузчика спросит снова.
 *
 * Беда — [DataSourceException] с кодом, по которому очередь решает (G3):
 * играть нечем — как пропавший файл (пропустить), нет сети — встать.
 *
 * Зовётся из потоков загрузки — отсюда замок.
 */
@OptIn(UnstableApi::class)
internal class OnlineResolver(
    private val streams: StreamResolver,
) : ResolvingDataSource.Resolver {
    private val loads =
        object : LinkedHashMap<Uri, StreamLookup.Found>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, StreamLookup.Found>?) =
                size > KEPT_LOADS
        }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val link = dataSpec.uri
        val track = MediaItemMapper.onlineTrack(link) ?: return dataSpec
        val reason =
            when (val found = synchronized(loads) { loads[link] } ?: streams.resolve(track)) {
                is StreamLookup.Found -> {
                    synchronized(loads) { loads[link] = found }
                    return dataSpec
                        .buildUpon()
                        .setUri(found.url)
                        .setHttpRequestHeaders(dataSpec.httpRequestHeaders + found.headers)
                        .build()
                }
                StreamLookup.Unavailable -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                StreamLookup.NoNetwork -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                StreamLookup.Failed -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            }
        throw DataSourceException(reason)
    }

    private companion object {
        /** Сколько загрузок помнить: текущая и те, что плеер готовит заранее. */
        const val KEPT_LOADS = 4
    }
}
