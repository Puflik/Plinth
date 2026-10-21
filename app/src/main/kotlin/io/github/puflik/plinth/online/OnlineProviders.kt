package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.CoreHttpStatusTable
import io.github.puflik.plinth.ffi.OnlineProvider
import io.github.puflik.plinth.online.youtube.HttpStatusTable
import io.github.puflik.plinth.online.youtube.NewPipeYoutubeSource

/**
 * Провайдеры, которые приложение отдаёт ядру (Ю3в): пока только YouTube; выбор по сборке и по переключателю — Ю3г.
 * Источник YouTube держит память между вызовами (заготовка потоков, которую `resolve` кладёт, а `stream` забирает),
 * поэтому провайдер — один на приложение, а не на вызов: репозиторий берёт список один раз.
 */
internal fun onlineProviders(statusTable: HttpStatusTable = CoreHttpStatusTable): List<OnlineProvider> =
    listOf(YoutubeProvider(NewPipeYoutubeSource(statusTable)))
