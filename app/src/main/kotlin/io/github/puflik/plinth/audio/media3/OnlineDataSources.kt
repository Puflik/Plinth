package io.github.puflik.plinth.audio.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource

/**
 * Источник данных плеера для сетевых треков (Ю3в): файлы и готовые адреса читает `DefaultDataSource`, ссылку на сетевой
 * трек `ResolvingDataSource` раскрывает в адрес ([resolver]), а [ReResolvingDataSource] на первый отказ (403) загрузки
 * просит новый.
 *
 * [userAgent] приложения — умолчание запроса, а не его последнее слово: `DefaultHttpDataSource` ставит `User-Agent`
 * фабрики в запрос после заголовков `DataSpec` и перекрыл бы им клиентский `User-Agent`, который выдал провайдер
 * (YouTube отдаёт поток тому клиенту, чью ссылку выдал). Умолчание запроса провайдер перекрывает; без его заголовка
 * представляется приложение.
 */
@OptIn(UnstableApi::class)
internal fun onlineDataSourceFactory(
    context: Context,
    userAgent: String,
    resolver: OnlineResolver,
): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("User-Agent" to userAgent))
    val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http), resolver)
    return DataSource.Factory { ReResolvingDataSource(resolving.createDataSource(), resolver) }
}
