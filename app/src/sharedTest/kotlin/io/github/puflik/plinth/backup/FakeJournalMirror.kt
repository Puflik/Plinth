package io.github.puflik.plinth.backup

import io.github.puflik.plinth.ffi.MirrorFile
import io.github.puflik.plinth.ffi.MirrorFound
import io.github.puflik.plinth.ffi.MirrorRestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * Установка в памяти с копией журнала (C4) — для тестов писателя копии и
 * экранов восстановления. Проходит `JournalMirrorContractTest`, как ядро на
 * эмуляторе.
 *
 * Фонотека — «файл → ID», ID у каждой установки свои; журнал — лайки и
 * плейлисты по ID и паспорта «ID → файл». Копия — текст; слияние —
 * объединение; трек без ID в фонотеке узнаётся по файлу паспорта.
 */
class FakeJournalMirror(
    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(CLOCK_MS) },
) : JournalMirror {
    private val device = "fake-${installations.incrementAndGet()}"
    private val tracks = LinkedHashMap<String, String>()
    private val likes = LinkedHashSet<String>()
    private val playlists = LinkedHashMap<String, List<String>>()
    private val passports = LinkedHashMap<String, String>()
    private val counter = MutableStateFlow(0L)
    private var ids = 0

    /** Сколько копий отдано. */
    var copies = 0
        private set

    /** Следующая копия не получится — как отказ ядра. */
    var failNextCopy = false

    override val changes: StateFlow<Long> = counter.asStateFlow()

    /** Файлы в фонотеку; знакомый паспорту файл получает ID из журнала. */
    @Synchronized
    fun scan(paths: List<String>) {
        paths.filter { it !in tracks }.forEach { tracks[it] = "$device-track-${++ids}" }
        relink()
    }

    @Synchronized
    fun like(path: String) {
        likes += describe(path)
        edited()
    }

    @Synchronized
    fun playlist(
        name: String,
        paths: List<String>,
    ) {
        playlists[name] = paths.map(::describe)
        edited()
    }

    @Synchronized
    fun liked(): Set<String> = likes.mapNotNull(::pathOf).toSet()

    @Synchronized
    fun playlists(): Map<String, List<String>> = playlists.mapValues { (_, list) -> list.mapNotNull(::pathOf) }

    override suspend fun copy(): MirrorFile? = synchronized(this) { copyNow() }

    override suspend fun inspect(files: List<MirrorFile>): MirrorFound = synchronized(this) { inspectNow(files) }

    override suspend fun restore(files: List<MirrorFile>): MirrorRestore = synchronized(this) { restoreNow(files) }

    private fun copyNow(): MirrorFile? {
        if (failNextCopy) {
            failNextCopy = false
            return null
        }
        copies++
        val lines =
            listOf(MAGIC, "at\t${clock().toEpochMilliseconds()}") +
                likes.map { "like\t$it" } +
                playlists.map { (name, list) -> "playlist\t$name\t${list.joinToString(",")}" } +
                passports.map { (id, path) -> "passport\t$id\t$path" }
        return MirrorFile("$device.journal", lines.joinToString("\n").toByteArray())
    }

    private fun inspectNow(files: List<MirrorFile>): MirrorFound {
        val (copies, unreadable) = read(files)
        return MirrorFound(
            likes = copies.flatMap { it.likes }.toSet().size,
            playlists = copies.flatMap { it.playlists.keys }.toSet().size,
            plays = 0,
            writtenAt = copies.maxOfOrNull { it.at },
            news = copies.any { it.isNewsTo(this) },
            unreadable = unreadable,
        )
    }

    private fun restoreNow(files: List<MirrorFile>): MirrorRestore {
        val (copies, unreadable) = read(files)
        val merged = copies.count { it.isNewsTo(this) }
        for (copy in copies) {
            likes += copy.likes
            copy.playlists.forEach { (name, list) -> playlists.putIfAbsent(name, list) }
            copy.passports.forEach { (id, path) -> passports.putIfAbsent(id, path) }
        }
        val relinked = relink()
        edited()
        return MirrorRestore(merged, unreadable, relinked)
    }

    private fun describe(path: String): String =
        checkNotNull(tracks[path]) { "not in the library: $path" }.also { passports[it] = path }

    private fun pathOf(id: String): String? = tracks.entries.find { it.value == id }?.key

    /** Файлы с ID, на которые у журнала нет данных, берут ID из паспортов. */
    private fun relink(): Int {
        var moved = 0
        for ((id, path) in passports) {
            val current = tracks[path] ?: continue
            if (current != id && current !in passports && id !in tracks.values) {
                tracks[path] = id
                moved++
            }
        }
        return moved
    }

    private fun edited() = counter.update { it + 1 }

    private fun read(files: List<MirrorFile>): Pair<List<Copy>, Int> {
        val others = files.filterNot { it.name.startsWith(device) }
        val copies = others.mapNotNull { Copy.parse(it.content.decodeToString()) }
        return copies to others.size - copies.size
    }

    private class Copy(
        val at: Instant,
        val likes: Set<String>,
        val playlists: Map<String, List<String>>,
        val passports: Map<String, String>,
    ) {
        fun isNewsTo(mirror: FakeJournalMirror): Boolean =
            !mirror.likes.containsAll(likes) ||
                !mirror.playlists.keys.containsAll(playlists.keys) ||
                !mirror.passports.keys.containsAll(passports.keys)

        companion object {
            fun parse(text: String): Copy? {
                val lines = text.lines()
                val fields = lines.drop(1).map { it.split('\t') }
                val at = fields.find { it[0] == "at" }?.get(1)?.toLongOrNull()
                if (lines.firstOrNull() != MAGIC || at == null) return null
                return Copy(
                    at = Instant.fromEpochMilliseconds(at),
                    likes = fields.filter { it[0] == "like" }.map { it[1] }.toSet(),
                    playlists = fields.filter { it[0] == "playlist" }.associate { it[1] to it[2].split(',') },
                    passports = fields.filter { it[0] == "passport" }.associate { it[1] to it[2] },
                )
            }
        }
    }

    private companion object {
        const val MAGIC = "PLNM-FAKE"
        const val CLOCK_MS = 1_790_307_000_000L
        val installations = AtomicInteger()
    }
}
