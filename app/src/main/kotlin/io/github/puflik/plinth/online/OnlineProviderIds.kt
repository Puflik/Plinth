package io.github.puflik.plinth.online

/** ID провайдеров ядра в порядке секций поиска и списка в настройках; те же ID — в каталоге пользователя. */
object OnlineProviderIds {
    const val ARCHIVE: String = "archive.org"
    const val YOUTUBE: String = "youtube"

    /** Internet Archive — в ядре; остальные — провайдеры приложения (`onlineProviders()`). */
    val ALL: List<String> = listOf(ARCHIVE, YOUTUBE)
}
