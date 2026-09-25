package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.CoreProblem
import io.github.puflik.plinth.di.CoreEntryPoint
import io.github.puflik.plinth.diagnostics.log.LogLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Ядро на устройстве (DoD эпика A, A3): настоящая libplinth_ffi.so под ABI
 * устройства, настоящий логгер приложения, база и журнал на диске телефона.
 * Ядро приложения живёт в `files/core`; тесты API открывают свои — в
 * отдельных каталогах, чтобы не делить с ним замок и данные.
 */
class PlinthCoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appCore = EntryPointAccessors.fromApplication<CoreEntryPoint>(context).plinthCore()
    private val dirs = mutableListOf<File>()
    private val opened = mutableListOf<PlinthCore>()

    @After
    fun tearDown() {
        opened.forEach(PlinthCore::close)
        dirs.forEach(File::deleteRecursively)
    }

    private fun newDir() = File(context.cacheDir, "core-test-" + UUID.randomUUID()).also(dirs::add)

    private fun core(
        dir: File,
        errors: CoreErrors = CoreErrors(),
    ) = PlinthCore(LogLevel.INFO, dir, errors).also(opened::add)

    private fun track() = TrackId(UUID.randomUUID().toString())

    @Test
    fun a_fresh_core_opens_empty() {
        val core = core(newDir())

        assertThat(core.open()).isEqualTo(StartupReport(databaseRecovered = false, restoredFromJournal = false))
        assertThat(core.library.tracks()).isEmpty()
        assertThat(core.journal.playlists()).isEmpty()
    }

    /** Строка с кириллицей уходит в Rust и возвращается без потерь. */
    @Test
    fun likes_ratings_and_names_go_through_the_journal_and_back() {
        val core = core(newDir())
        val track = track()

        core.journal.like(track)
        core.journal.rate(track, stars = 4)
        val playlist = core.journal.createPlaylist("Дорога — ночь 🌙")

        assertThat(core.library.userData(track).liked).isTrue()
        assertThat(core.library.userData(track).rating).isEqualTo(4)
        assertThat(
            core.journal
                .playlists()
                .single()
                .name,
        ).isEqualTo("Дорога — ночь 🌙")
        assertThat(
            core.journal
                .playlists()
                .single()
                .id,
        ).isEqualTo(playlist.id)
    }

    @Test
    fun playlist_order_follows_indexes() {
        val core = core(newDir())
        val (a, b, c) = List(3) { track() }
        val playlist = core.journal.createPlaylist("Mix").id

        listOf(a, b).forEach { core.journal.addToPlaylist(playlist, it) }
        core.journal.addToPlaylist(playlist, c, index = 0)
        core.journal.moveInPlaylist(
            playlist,
            core.journal
                .playlistItems(playlist)
                .first()
                .id,
            index = 2,
        )

        assertThat(core.journal.playlistItems(playlist).map { it.track }).containsExactly(a, b, c).inOrder()
    }

    /** Правило Last.fm: в историю — все прослушивания, в счётчик — половина трека или 4 минуты. */
    @Test
    fun plays_are_history_and_count_by_the_rule() {
        val core = core(newDir())
        val track = track()
        val now = Clock.System.now()

        core.journal.recordPlay(NewPlay(track, now, 180, listened = 130.seconds, trackLength = 240.seconds))
        core.journal.recordPlay(NewPlay(track, now, 180, listened = 10.seconds, trackLength = 240.seconds))

        assertThat(core.journal.recentPlays(10)).hasSize(2)
        assertThat(core.library.userData(track).playCount).isEqualTo(1)
    }

    @Test
    fun user_data_survives_a_reopen() {
        val dir = newDir()
        val track = track()
        core(dir).apply { journal.like(track) }.close()

        val again = core(dir)

        assertThat(again.library.userData(track).liked).isTrue()
        assertThat(again.open().restoredFromJournal).isFalse()
    }

    /** DoD эпика C на телефоне: база пропала — лайки и плейлисты вернул журнал. */
    @Test
    fun a_deleted_database_comes_back_from_the_journal() {
        val dir = newDir()
        val track = track()
        core(dir)
            .apply {
                journal.like(track)
                journal.addToPlaylist(journal.createPlaylist("Mix").id, track)
            }.close()
        dir.listFiles { file -> file.name.startsWith("library.db") }!!.forEach(File::delete)

        val again = core(dir)

        assertThat(again.open().restoredFromJournal).isTrue()
        assertThat(again.library.userData(track).liked).isTrue()
        assertThat(
            again.journal
                .playlistItems(
                    again.journal
                        .playlists()
                        .single()
                        .id,
                ).map { it.track },
        ).containsExactly(track)
    }

    /** Два ядра на одних файлах — два писателя журнала: второе не откроется, отказ уходит в поток ошибок. */
    @Test
    fun the_same_files_open_once_and_the_refusal_is_reported() {
        val dir = newDir()
        core(dir).open()
        val errors = CoreErrors()
        val second = core(dir, errors)

        // Поток без памяти: подписка — до вызова, иначе отказ пройдёт мимо.
        val (failure, reported) =
            runBlocking {
                val reported =
                    async(start = CoroutineStart.UNDISPATCHED) { withTimeout(WAIT_MILLIS) { errors.errors.first() } }
                assertThrows(CoreFailure::class.java) { second.journal.like(track()) } to reported.await()
            }

        assertThat(failure.error).isEqualTo(AppError.CoreFailed(CoreProblem.UNAVAILABLE))
        assertThat(reported).isEqualTo(failure.error)
    }

    @Test
    fun panic_in_core_is_a_kotlin_exception() {
        val error = assertThrows(CoreFailure::class.java) { appCore.panicForTest("on purpose") }

        assertThat(error.error).isEqualTo(AppError.CoreFailed(CoreProblem.INTERNAL))
        assertThat(error.message).startsWith("internal: panicked at ")
        assertThat(error.message).endsWith(": on purpose")
    }

    @Test
    fun core_keeps_working_after_a_panic() {
        val core = core(newDir())
        val track = track()
        assertThrows(CoreFailure::class.java) { core.panicForTest("first") }

        core.journal.like(track)

        assertThat(core.library.userData(track).liked).isTrue()
    }

    @Test
    fun rust_log_reaches_the_app_log_file() {
        val marker = "marker-" + UUID.randomUUID().toString().take(MARKER_LENGTH)

        assertThrows(CoreFailure::class.java) { appCore.panicForTest(marker) }

        // Файл пишется в своём потоке приложения — ждём, а не читаем сразу.
        val log = File(context.filesDir, "logs/plinth.log")
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var line: String? = null
        while (line == null && System.currentTimeMillis() < deadline) {
            line = log.takeIf(File::exists)?.readLines()?.lastOrNull { marker in it }
            if (line == null) Thread.sleep(POLL_MILLIS)
        }
        assertThat(line).isNotNull()
        assertThat(line).contains(" E plinth_ffi::panic: panicked at ")
    }

    private companion object {
        // Короче 32 знаков: длинную смесь букв и цифр лог вырезает как токен.
        const val MARKER_LENGTH = 8
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
