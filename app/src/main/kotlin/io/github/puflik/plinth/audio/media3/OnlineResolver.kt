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
 * Адрес живёт не дольше срока, который назвал провайдер (`validFor`, Ю3в): ссылка YouTube
 * годится часы, и перемотка через три часа не должна идти по мёртвой. Срок идёт от получения
 * адреса по [clock] (наносекунды); пока он не вышел, адрес держится, на границе — спрашивается
 * заново. Адрес без срока (Internet Archive) не стареет.
 *
 * Отказ сервера (403) — [refused]: первый в загрузке забывает адрес, и следующее открытие
 * спросит новый; повторный адрес не трогает. Метка «отказ был» живёт столько же, сколько
 * загрузка, и переживает замену адреса и сбой вопроса; срок её не расходует.
 *
 * Беда — [DataSourceException] с кодом, по которому очередь решает (G3):
 * играть нечем — как пропавший файл (пропустить), нет сети — встать.
 *
 * Зовётся из потоков загрузки — отсюда замок.
 */
@OptIn(UnstableApi::class)
internal class OnlineResolver(
    private val clock: () -> Long = System::nanoTime,
    private val streams: StreamResolver,
) : ResolvingDataSource.Resolver {
    private val loads =
        object : LinkedHashMap<Uri, Load>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Load>?) = size > KEPT_LOADS
        }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val link = dataSpec.uri
        val track = MediaItemMapper.onlineTrack(link) ?: return dataSpec
        val found = kept(link) ?: asked(link, track)
        return dataSpec
            .buildUpon()
            .setUri(found.url)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + found.headers)
            .build()
    }

    /**
     * Сервер ответил 403 на адрес загрузки [link]. Первый отказ загрузки (даже если адреса в памяти уже нет) —
     * адрес забывается, и можно просить новый: `true`. Повторный — `false`, адрес остаётся. Не сетевой трек — `false`.
     * Сам ничего не спрашивает: новый адрес спросит следующее открытие.
     */
    fun refused(link: Uri): Boolean {
        if (MediaItemMapper.onlineTrack(link) == null) return false
        return synchronized(loads) {
            val load = loads.getOrPut(link, ::Load)
            val first = !load.refused
            if (first) {
                load.refused = true
                load.found = null
            }
            first
        }
    }

    /** Адрес, который загрузка ещё держит: он есть и не прожил свой срок. */
    private fun kept(link: Uri): StreamLookup.Found? =
        synchronized(loads) { loads[link]?.takeIf { it.fresh(clock()) }?.found }

    /** Новый адрес у [streams]; удача запоминается с моментом получения, неудача — нет. */
    private fun asked(
        link: Uri,
        track: String,
    ): StreamLookup.Found {
        val reason =
            when (val answer = streams.resolve(track)) {
                is StreamLookup.Found -> {
                    val received = clock()
                    synchronized(loads) { loads.getOrPut(link, ::Load).remember(answer, received) }
                    return answer
                }
                StreamLookup.Unavailable -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                StreamLookup.NoNetwork -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                StreamLookup.Failed -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            }
        throw DataSourceException(reason)
    }

    /** Что помнит одна загрузка: адрес, когда он получен (наносекунды [clock]) и был ли отказ. */
    private class Load {
        var found: StreamLookup.Found? = null
        var refused = false
        private var receivedAt = 0L

        fun remember(
            address: StreamLookup.Found,
            at: Long,
        ) {
            found = address
            receivedAt = at
        }

        /** Адрес годен, пока `validFor` не вышел (на границе — уже нет); без срока не стареет. */
        fun fresh(now: Long): Boolean {
            val address = found ?: return false
            val validFor = address.validFor
            return validFor == null || now - receivedAt < validFor.inWholeNanoseconds
        }
    }

    private companion object {
        /** Сколько загрузок помнить: текущая и те, что плеер готовит заранее. */
        const val KEPT_LOADS = 4
    }
}
