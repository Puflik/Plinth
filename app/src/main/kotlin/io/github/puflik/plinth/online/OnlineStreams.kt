package io.github.puflik.plinth.online

import android.content.Context
import android.net.ConnectivityManager
import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.audio.engine.StreamResolver
import io.github.puflik.plinth.ffi.TrackId

/**
 * Адрес сетевого трека для плеера (E3): у ядра, в момент загрузки, по сети
 * этой минуты — ответ автора «FLAC по Wi-Fi, MP3 по сотовой».
 */
class OnlineStreams(
    private val online: OnlineRepository,
    private val metered: () -> Boolean,
) : StreamResolver {
    override fun resolve(track: String): StreamLookup = online.stream(TrackId(track), metered())
}

/**
 * Лимитная ли сеть сейчас: сотовая, точка доступа с телефона, Wi-Fi,
 * помеченный лимитным. Сети нет — тоже «лимитная»: осторожный выбор ничего
 * не стоит, а сеть может появиться уже сотовой.
 */
class MeteredNetwork(
    context: Context,
) : () -> Boolean {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun invoke(): Boolean = connectivity?.isActiveNetworkMetered ?: true
}
