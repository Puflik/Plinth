package io.github.puflik.plinth.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.R
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreTestFile
import io.github.puflik.plinth.ffi.NewPlay
import io.github.puflik.plinth.ffi.PlinthCore
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.online.TestConcert
import org.junit.After
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * F1: переустановка, насколько её можно автоматизировать. Удалить и
 * поставить приложение из инструментального теста нельзя, поэтому установка
 * здесь — свой каталог `files/` с ядром в `files/core`, как у `CoreModule`, а
 * переустановка — новый каталог рядом: новое ядро, новый `device`, пустая
 * база. Через переустановку проходит только то, что берут правила Auto
 * Backup (`data_extraction_rules.xml` из ресурсов приложения), или копия
 * журнала в папке человека (C4).
 *
 * Лайки и плейлисты через копию проверяет и контракт [JournalMirrorContractTest];
 * здесь — всё вместе: история и сетевой трек тоже. Настоящее удаление
 * приложения — ручная проверка, `docs/testing/v0.2-checklist.md`.
 */
class ReinstallRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val installations = mutableListOf<Installation>()

    @After
    fun cleanUp() = installations.forEach { it.remove() }

    @Test
    fun a_backup_brings_likes_playlists_history_and_online_tracks_back() {
        val old = oldInstallation()
        old.close()
        val new = install()

        restoreBackup(from = old, to = new)

        assertThat(new.core.open().restoredFromJournal).isTrue()
        assertThat(new.liked()).containsExactly(ONLINE)
        new.scan()
        assertCameBack(new)
    }

    @Test
    fun a_copy_in_the_folder_brings_likes_playlists_history_and_online_tracks_back() {
        val old = oldInstallation()
        val copy = old.core.mirror.copy()
        old.close()
        val new = install()
        new.scan()

        val found = new.core.mirror.inspect(listOf(copy))
        new.core.mirror.restore(listOf(copy))

        assertThat(listOf(found.likes, found.playlists, found.plays)).containsExactly(2, 1, 1).inOrder()
        assertCameBack(new)
    }

    /** Прошлая установка: лайк и прослушивание «Кукушки», «Дорога», лайк сетевого трека. */
    private fun oldInstallation(): Installation {
        val old = install()
        old.scan()
        val (kino, queen) = old.track(KINO) to old.track(QUEEN)
        val journal = old.core.journal
        journal.like(kino)
        val road = journal.createPlaylist(ROAD)
        journal.addToPlaylist(road.id, queen)
        journal.addToPlaylist(road.id, kino)
        journal.recordPlay(
            NewPlay(
                track = kino,
                startedAt = Instant.fromEpochMilliseconds(1_790_500_000_000),
                utcOffsetMinutes = 180,
                listened = 5.minutes,
                trackLength = KINO_LENGTH,
            ),
        )
        journal.like(
            old.core.online
                .add(PROVIDER, listOf(TestConcert.tracks[0]))
                .single(),
        )
        return old
    }

    private fun assertCameBack(new: Installation) {
        assertThat(new.liked()).containsExactly(KINO_TITLE, ONLINE)
        val road =
            new.core.journal
                .playlists()
                .single()
        assertThat(road.name).isEqualTo(ROAD)
        val order =
            new.core.library
                .playlistTracks(road.id)
                .map { it.track.title }
        assertThat(order).containsExactly(QUEEN_TITLE, KINO_TITLE).inOrder()
        assertThat(
            new.core.library
                .recentTracks(10)
                .map { it.title },
        ).containsExactly(KINO_TITLE)
    }

    /** То, что Android вернул бы из облачной копии: пути `<include domain="file">`. */
    private fun restoreBackup(
        from: Installation,
        to: Installation,
    ) {
        val kept = backedUpFiles()
        assertThat(kept).isNotEmpty()
        kept.forEach { path -> File(from.files, path).copyRecursively(File(to.files, path)) }
    }

    private fun backedUpFiles(): List<String> {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val paths = mutableListOf<String>()
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when {
                parser.depth == 2 -> section = parser.name
                parser.name == "include" && section == "cloud-backup" -> {
                    assertThat(parser.getAttributeValue(null, "domain")).isEqualTo("file")
                    paths += parser.getAttributeValue(null, "path")
                }
            }
        }
        parser.close()
        return paths
    }

    private fun install() =
        Installation(File(context.cacheDir, "reinstall-" + UUID.randomUUID())).also {
            installations +=
                it
        }

    /** Установка: [files] — её `filesDir`, ядро — в `files/core`, как у `CoreModule`. */
    private class Installation(
        val files: File,
    ) {
        val core = PlinthCore(LogLevel.INFO, File(files, "core"), CoreErrors())

        /** Скан той же музыки — на новой установке у файлов новые ID. */
        fun scan() = core.seedForTest(SONGS)

        fun track(song: CoreTestFile): TrackId = checkNotNull(core.library.trackAt(song.uri)) { song.uri }

        fun liked(): List<String> = core.library.likedTracks().map { it.title }

        private var closed = false

        fun close() {
            if (!closed) core.close()
            closed = true
        }

        fun remove() {
            close()
            files.deleteRecursively()
        }
    }

    private companion object {
        const val PROVIDER = "archive.org"
        const val ROAD = "Дорога"
        const val KINO_TITLE = "Кукушка"
        const val QUEEN_TITLE = "Bohemian Rhapsody"
        val ONLINE = TestConcert.tracks[0].title
        val KINO_LENGTH = 398.seconds

        val KINO =
            CoreTestFile(
                uri = "/storage/emulated/0/Music/Кино/Кукушка.mp3",
                folder = "Music/",
                title = KINO_TITLE,
                artist = "Кино",
                duration = KINO_LENGTH,
            )
        val QUEEN =
            CoreTestFile(
                uri = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3",
                folder = "Music/",
                title = QUEEN_TITLE,
                artist = "Queen",
                duration = 354.seconds,
            )
        val SONGS = listOf(KINO, QUEEN)
    }
}
