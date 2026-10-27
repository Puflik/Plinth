package io.github.puflik.plinth.online

import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineOrigin
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.TrackId

/**
 * Онлайн-источники (E3) — единственный путь экранов и плеера к провайдерам.
 *
 * Реализация — `CoreOnlineRepository` поверх ядра; требования —
 * `OnlineRepositoryContractTest`, тот же, что проходит `FakeOnlineRepository`.
 *
 * Результаты поиска и треки альбома живут в памяти экрана. В каталог трек
 * попадает при действии ([add]): сыграли, лайкнули, в плейлист — дальше он
 * живёт как локальный («Любимое», плейлисты, история). Сеть и провайдера
 * объясняют экраны, поэтому их беды — значения, а не исключения.
 */
interface OnlineRepository {
    /**
     * Главный переключатель «Онлайн-источники» и выбор провайдеров: [enabled] выключено — поиска, альбомов
     * и потоков нет; иначе подключаются все провайдеры, кроме [disabled] (ID из [OnlineProviderIds]).
     * Включены, но выключены все провайдеры — то же, что выключено.
     */
    suspend fun setEnabled(
        enabled: Boolean,
        disabled: Set<String> = emptySet(),
    )

    /**
     * Секция на каждого провайдера — с результатами или с бедой. Выключено
     * или запрос без слов — секций нет. Ждёт ответа сети.
     */
    suspend fun search(query: String): List<OnlineSection>

    /** Треки собрания [item] провайдера [provider] — по номерам. */
    suspend fun album(
        provider: String,
        item: String,
    ): OnlineAlbum

    /**
     * Радио от [seed] провайдера [provider] (id трека или его источника); [page] — ключ прошлой страницы,
     * `null` — первая.
     */
    suspend fun radio(
        provider: String,
        seed: String,
        page: String? = null,
    ): OnlineRadio

    /** Где трек каталога играется; файл с диска и неизвестный трек — пусто. */
    suspend fun origins(track: TrackId): List<OnlineOrigin>

    /** Какие из [tracks] уже в каталоге — ID или `null`, в том же порядке. Каталог не меняется. */
    suspend fun known(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId?>

    /** Заводит [tracks] в каталоге — ID в том же порядке; уже заведённые находятся. Не вышло — `null`. */
    suspend fun add(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId>?

    /**
     * Адрес потока [track] в момент загрузки: [metered] — сотовая сеть (MP3),
     * иначе лучший вариант. Форматы из [undecodable] устройство не декодирует
     * (FLAC на Android 8.0) — они берутся, только если другого нет.
     * Блокирует — зовёт загрузчик плеера.
     */
    fun stream(
        track: TrackId,
        metered: Boolean,
        undecodable: Set<AudioFormat> = emptySet(),
    ): StreamLookup

    companion object {
        /** Сколько результатов просить у провайдера. */
        const val SEARCH_LIMIT = 20
    }
}

/** Страница радио: треки и ключ следующей страницы — или беда, которую объясняет экран. */
sealed interface OnlineRadio {
    data class Page(
        val tracks: List<OnlineTrack>,
        val next: String?,
    ) : OnlineRadio

    data class Failed(
        val problem: OnlineProblem,
    ) : OnlineRadio
}

/** Альбом провайдера: треки — или беда, которую объясняет экран. */
sealed interface OnlineAlbum {
    data class Tracks(
        val tracks: List<OnlineTrack>,
    ) : OnlineAlbum

    data class Failed(
        val problem: OnlineProblem,
    ) : OnlineAlbum
}
