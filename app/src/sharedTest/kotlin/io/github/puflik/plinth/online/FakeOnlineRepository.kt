package io.github.puflik.plinth.online

import io.github.puflik.plinth.audio.engine.StreamLookup
import io.github.puflik.plinth.ffi.AudioFormat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/**
 * [OnlineRepository] в памяти: один провайдер «archive.org» с альбомами
 * [albums]. Проходит `OnlineRepositoryContractTest`; на нём тестируются
 * экраны поиска и альбома.
 *
 * Как ядро, начинает выключенным. Каталог — по вариантам: трек узнаётся по
 * любому из них. Адрес потока строится без сети, как у Internet Archive.
 */
class FakeOnlineRepository(
    private val albums: List<Album> = listOf(Album.CONCERT),
) : OnlineRepository {
    data class Album(
        val item: String,
        val title: String,
        val artist: String?,
        val year: Int?,
        val tracks: List<OnlineTrack>,
    ) {
        companion object {
            val CONCERT =
                Album(TestConcert.ITEM, TestConcert.TITLE, TestConcert.ARTIST, TestConcert.YEAR, TestConcert.tracks)
        }
    }

    var enabled = false
        private set

    /** Есть ли сеть; нет — поиск и альбомы говорят «нет сети». */
    var networkUp = true

    /** Провайдер отдыхает после отказов подряд. */
    var providerDown = false

    /** Запросы поиска по порядку — сколько раз экран спросил сеть. */
    val searches = mutableListOf<String>()

    /** Есть — поиск ждёт его, как ответа сети: экран успевает сказать «Ищем…». */
    var gate: CompletableDeferred<Unit>? = null

    private val catalog = mutableMapOf<String, TrackId>()
    private val variants = mutableMapOf<TrackId, OnlineTrack>()

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override suspend fun search(query: String): List<OnlineSection> {
        val words = query.lowercase().split(' ').filter(String::isNotBlank)
        if (!enabled || words.isEmpty()) return emptyList()
        searches += query
        gate?.await()
        val problem = problem()
        val found =
            albums
                .filter { album -> words.all { word -> "${album.title} ${album.artist}".lowercase().contains(word) } }
                .map { OnlineResult(it.item, OnlineKind.ALBUM, it.title, it.artist, it.year) }
                .takeIf { problem == null }
                .orEmpty()
        return listOf(OnlineSection(PROVIDER, found, problem))
    }

    override suspend fun album(
        provider: String,
        item: String,
    ): OnlineAlbum {
        val album = albums.find { it.item == item && provider == PROVIDER }
        val problem = if (enabled) problem() else OnlineProblem.PROVIDER_DOWN
        return when {
            problem != null -> OnlineAlbum.Failed(problem)
            album == null -> OnlineAlbum.Failed(OnlineProblem.PROVIDER_DOWN)
            else -> OnlineAlbum.Tracks(album.tracks)
        }
    }

    override suspend fun known(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId?> = tracks.map(::idOf)

    override suspend fun add(
        provider: String,
        tracks: List<OnlineTrack>,
    ): List<TrackId> =
        tracks.map { track ->
            val id = idOf(track) ?: TrackId(UUID.randomUUID().toString())
            track.variants.forEach { catalog[it.external] = id }
            variants[id] = track
            id
        }

    override fun stream(
        track: TrackId,
        metered: Boolean,
    ): StreamLookup {
        val options = variants[track]?.variants.orEmpty()
        if (!enabled || options.isEmpty()) return StreamLookup.Unavailable
        val chosen =
            if (metered) {
                options.firstOrNull { it.format == AudioFormat.MP3 }
            } else {
                options.firstOrNull { it.format in LOSSLESS }
            } ?: options.first()
        return StreamLookup.Found("https://archive.org/download/${chosen.external}")
    }

    private fun idOf(track: OnlineTrack): TrackId? = track.variants.firstNotNullOfOrNull { catalog[it.external] }

    private fun problem(): OnlineProblem? =
        when {
            !networkUp -> OnlineProblem.NO_NETWORK
            providerDown -> OnlineProblem.PROVIDER_DOWN
            else -> null
        }

    companion object {
        const val PROVIDER = "archive.org"
        private val LOSSLESS = setOf(AudioFormat.FLAC, AudioFormat.ALAC, AudioFormat.WAV, AudioFormat.AIFF)
    }
}
