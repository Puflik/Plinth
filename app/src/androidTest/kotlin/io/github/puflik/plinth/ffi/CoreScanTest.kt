package io.github.puflik.plinth.ffi

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogLevel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Главный риск D1 на устройстве: ядро на Rust обходит общее хранилище по
 * прямым путям (`/storage/emulated/0/Music/…`), без `MediaStore`. Файл
 * кладётся через `MediaStore`, как его положил бы любой другой плеер;
 * своё приложение видит и без разрешения на музыку.
 *
 * D2: теги читает `lofty` в той же `.so` — на устройстве, а не только в
 * `cargo test` на ПК.
 */
class CoreScanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val dataDir = File(context.cacheDir, "core-scan-" + UUID.randomUUID())
    private lateinit var core: PlinthCore

    @Before
    fun setUp() {
        assumeTrue("MediaStore.RELATIVE_PATH — с Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        removeProbe()
        core = PlinthCore(LogLevel.INFO, dataDir, CoreErrors())
    }

    @After
    fun tearDown() {
        if (::core.isInitialized) core.close()
        dataDir.deleteRecursively()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) removeProbe()
    }

    @Test
    fun the_core_finds_a_file_in_shared_storage_and_notices_when_it_is_gone() {
        val uri = putProbe()

        val first = scan()
        val found = core.library.tracks().map { it.title }
        val second = scan()
        resolver.delete(uri, null, null)
        val gone = scan()

        assertThat(first.added).isEqualTo(1)
        assertThat(found).containsExactly(PROBE_TITLE)
        assertThat(second.added + second.changed + second.missing).isEqualTo(0)
        assertThat(gone.missing).isEqualTo(1)
        assertThat(core.library.tracks()).isEmpty() // пропавший трек из списков скрыт
    }

    @Test
    fun progress_arrives_and_a_scan_can_be_stopped() {
        putProbe()
        val phases = mutableListOf<CoreScanPhase>()

        val stopped =
            core.scan.scan(volumes(), listOf(FOLDER)) { progress ->
                phases += progress.phase
                progress.phase != CoreScanPhase.READING
            }

        assertThat(stopped.stopped).isTrue()
        assertThat(phases).containsAtLeast(CoreScanPhase.WALKING, CoreScanPhase.READING).inOrder()
        assertThat(core.library.tracks()).isEmpty()
    }

    @Test
    fun tags_are_read_on_the_device() {
        putProbe(asset = "tags/plinth-m4a.m4a", name = "tagged.m4a", mime = "audio/mp4")

        val report = scan()

        val track = core.library.tracks().single()
        assertThat(report.unreadableFiles).isEqualTo(0)
        assertThat(listOf(track.title, track.artistCredit, track.albumTitle))
            .containsExactly("M4A Silence", "The Plinth", "Fixtures")
            .inOrder()
        assertThat(track.duration).isNotNull()
    }

    private fun scan() = core.scan.scan(volumes(), listOf(FOLDER))

    @Suppress("DEPRECATION") // Корень основного тома; тома SD-карт в D3 даст StorageManager.
    private fun volumes() = listOf(Environment.getExternalStorageDirectory())

    private fun putProbe(
        asset: String = "formats/silence.mp3",
        name: String = "$PROBE_TITLE.mp3",
        mime: String = "audio/mpeg",
    ): android.net.Uri {
        val pending =
            ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, name)
                put(MediaColumns.MIME_TYPE, mime)
                put(MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaColumns.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(collection(), pending)) { "MediaStore не принял файл" }
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            assets.open(asset).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun removeProbe() {
        resolver.delete(collection(), "${MediaColumns.RELATIVE_PATH} = ?", arrayOf(FOLDER))
    }

    private fun collection() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private companion object {
        const val FOLDER = "Music/PlinthCoreScan/"
        const val PROBE_TITLE = "Core scan probe"
    }
}
