package io.github.puflik.plinth.acceptance

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.library.RoomLibraryRepository
import io.github.puflik.plinth.library.db.PlinthDatabase
import io.github.puflik.plinth.library.model.FolderConfig
import io.github.puflik.plinth.library.scan.LibraryScanner
import io.github.puflik.plinth.library.scan.MediaStoreSource
import io.github.puflik.plinth.library.scan.ScanResult
import io.github.puflik.plinth.library.sort.SortKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/**
 * Замер скана (H3, план 25): 5 000 треков — меньше 30 секунд.
 *
 * Приёмочный тест, только по запросу — файлы создаются минуты; без
 * `-Pacceptance` пакет `acceptance` из прогона исключён (`app/build.gradle.kts`):
 * `./gradlew connectedGithubDebugAndroidTest -Pacceptance
 * -Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.acceptance.ScanPerformanceTest`.
 *
 * Библиотека как у живого человека: 50 исполнителей по 10 альбомов по 10
 * треков, у каждого файла свои теги и своя папка альбома. Файлы кладутся через
 * `MediaStore`, разбирает их системный сканер; замеряется наш скан целиком —
 * запрос к `MediaStore`, сверка, теги, запись в Room на диске. Без разрешения
 * на чтение тест видит только свои файлы: считаются ровно эти 5 000.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ScanPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val source = MediaStoreSource(resolver, Dispatchers.IO)
    private lateinit var database: PlinthDatabase

    @Before
    fun freshStart() {
        removeFiles()
        context.deleteDatabase(DATABASE)
        database = Room.databaseBuilder(context, PlinthDatabase::class.java, DATABASE).build()
    }

    @After
    fun cleanUp() {
        database.close()
        context.deleteDatabase(DATABASE)
        removeFiles()
    }

    @Test
    fun five_thousand_tracks_scan_in_under_thirty_seconds() =
        runBlocking {
            putLibrary()
            awaitSystemScanner()
            val scanner = LibraryScanner(source, RoomLibraryRepository(database.trackDao(), SortKeys()))
            val folders = FolderConfig(included = listOf(ROOT))

            val (first, firstTime) = measureTimedValue { scanner.scan(folders) }
            val (again, againTime) = measureTimedValue { scanner.scan(folders) }
            Log.i(TAG, "Скан $TRACKS треков: первый $firstTime, повторный без изменений $againTime")

            assertThat(first).isEqualTo(ScanResult(found = TRACKS, updated = TRACKS, missing = 0))
            assertThat(again).isEqualTo(ScanResult(found = TRACKS, updated = 0, missing = 0))
            assertThat(firstTime).isLessThan(LIMIT)
            assertThat(againTime).isLessThan(LIMIT)
            val repository = RoomLibraryRepository(database.trackDao(), SortKeys())
            assertThat(repository.albums().first()).hasSize(ARTISTS * ALBUMS)
            assertThat(repository.artists().first()).hasSize(ARTISTS)
        }

    /** Кладёт все файлы через `MediaStore`, по нескольку сразу — по одному это минуты. */
    private suspend fun putLibrary() {
        val audio = audioFrames()
        val start = TimeSource.Monotonic.markNow()
        val parallel = Semaphore(PARALLEL_WRITES)
        withContext(Dispatchers.IO) {
            (0 until TRACKS)
                .map { index -> async { parallel.withPermit { put(index, audio) } } }
                .awaitAll()
        }
        Log.i(TAG, "Файлы положены за ${start.elapsedNow()}")
    }

    private fun put(
        index: Int,
        audio: ByteArray,
    ) {
        val artist = index / (ALBUMS * TRACKS_PER_ALBUM)
        val album = index / TRACKS_PER_ALBUM
        val track = index % TRACKS_PER_ALBUM + 1
        val pending =
            ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, "%02d Track %d.mp3".format(track, index))
                put(MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaColumns.RELATIVE_PATH, "${ROOT}Artist $artist/Album $album/")
                put(MediaColumns.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(COLLECTION, pending)) { "MediaStore не принял файл $index" }
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            out.write(Id3.tag(title = "Track $index", artist = "Artist $artist", album = "Album $album", track = track))
            out.write(audio)
        }
        resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /** Ждёт, пока системный сканер разберёт все файлы: длительность есть только у разобранных. */
    private suspend fun awaitSystemScanner() {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val scanned = source.rows().count { it.folder.startsWith(ROOT) && (it.durationMs ?: 0) > 0 }
            if (scanned == TRACKS) break
            check(start.elapsedNow() < SYSTEM_SCAN_TIMEOUT) { "системный сканер разобрал $scanned из $TRACKS" }
            delay(POLL)
        }
        Log.i(TAG, "Системный сканер разобрал всё за ${start.elapsedNow()}")
    }

    /** Звук из фикстуры без тегов — без её пустого заголовка ID3. */
    private fun audioFrames(): ByteArray {
        val bytes =
            instrumentation.context.assets
                .open(FIXTURE)
                .use { it.readBytes() }
        return bytes.copyOfRange(Id3.headerLength(bytes), bytes.size)
    }

    private fun removeFiles() {
        resolver.delete(COLLECTION, "${MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$ROOT%"))
    }

    /** Заголовок ID3v2.3 с текстовыми кадрами в ISO-8859-1 — столько, сколько нужно сканеру. */
    private object Id3 {
        private const val HEADER = 10
        private const val SIZE_OFFSET = 6
        private const val SYNCSAFE_BITS = 7
        private const val SYNCSAFE_MASK = 0x7F
        private const val BYTE_MASK = 0xFF
        private const val VERSION = 3
        private const val LATIN_1: Byte = 0

        fun tag(
            title: String,
            artist: String,
            album: String,
            track: Int,
        ): ByteArray {
            val frames = ByteArrayOutputStream()
            listOf("TIT2" to title, "TPE1" to artist, "TALB" to album, "TRCK" to track.toString())
                .forEach { (id, text) -> frames.write(frame(id, text)) }
            val body = frames.toByteArray()
            return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), VERSION.toByte(), 0, 0) +
                syncsafe(body.size) + body
        }

        /** Длина заголовка ID3v2 в начале файла; нет заголовка — `0`. */
        fun headerLength(bytes: ByteArray): Int {
            if (bytes.size < HEADER || String(bytes, 0, 3, Charsets.ISO_8859_1) != "ID3") return 0
            val size =
                (SIZE_OFFSET until HEADER).fold(0) { acc, i ->
                    (acc shl SYNCSAFE_BITS) or (bytes[i].toInt() and SYNCSAFE_MASK)
                }
            return HEADER + size
        }

        private fun frame(
            id: String,
            text: String,
        ): ByteArray {
            val data = byteArrayOf(LATIN_1) + text.toByteArray(Charsets.ISO_8859_1)
            return id.toByteArray(Charsets.ISO_8859_1) + bigEndian(data.size) + byteArrayOf(0, 0) + data
        }

        private fun syncsafe(value: Int) =
            ByteArray(4) { i -> ((value shr (SYNCSAFE_BITS * (3 - i))) and SYNCSAFE_MASK).toByte() }

        private fun bigEndian(value: Int) =
            ByteArray(4) { i -> ((value shr (Byte.SIZE_BITS * (3 - i))) and BYTE_MASK).toByte() }
    }

    private companion object {
        const val TAG = "PlinthAcceptance"
        const val ROOT = "Music/PlinthPerf/"
        const val DATABASE = "scan-performance.db"
        const val FIXTURE = "tags/plinth-untagged.mp3"
        const val ARTISTS = 50
        const val ALBUMS = 10
        const val TRACKS_PER_ALBUM = 10
        const val TRACKS = ARTISTS * ALBUMS * TRACKS_PER_ALBUM
        const val PARALLEL_WRITES = 8
        val LIMIT: Duration = 30.seconds
        val SYSTEM_SCAN_TIMEOUT: Duration = 10.minutes
        val POLL: Duration = 2.seconds
        val COLLECTION: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
