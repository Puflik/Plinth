package io.github.puflik.plinth.ui.backup

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.backup.FakeJournalMirror
import io.github.puflik.plinth.backup.FakeMirrorFolder
import io.github.puflik.plinth.backup.FakeMirrorSettings
import io.github.puflik.plinth.backup.MirrorWriter
import io.github.puflik.plinth.ffi.MirrorFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * Р9е (`docs/work/r9f.md`, ревью R1 №8 и №9) на экране копии данных:
 *
 * - копия в папке, которую ядро не читает (новее приложения, обрезана, слишком велика), не
 *   выглядит как «данных нет»: человек получает сообщение;
 * - журнал начат заново пустым — на экране остаётся напоминание, пока данные не вернулись из
 *   копии или человек от них не отказался; пустая папка при напоминании тоже говорит о себе.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R9fBackupPendingTest {
    private val main = UnconfinedTestDispatcher()
    private val mirror = FakeJournalMirror().apply { scan(listOf(SONG, OTHER)) }
    private val folder = FakeMirrorFolder()
    private val settings = FakeMirrorSettings()
    private val writerScope = CoroutineScope(SupervisorJob() + main)
    private val viewModel by lazy {
        BackupViewModel(settings, folder, mirror, MirrorWriter(mirror, folder, settings, writerScope))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a copy that cannot be read is reported instead of staying silent`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, MirrorFile("newer.journal", "written by a newer version".toByteArray()))

            viewModel.onFolderPicked(TREE)

            val state = viewModel.uiState.value
            assertThat(state.outcome).isEqualTo(BackupOutcome.UNREADABLE)
            assertThat(state.found).isNull()
            assertThat(settings.folder.first()).isEqualTo(TREE)
        }

    @Test
    fun `unreadable files do not hide data that can be restored`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, previousInstallation())
            folder.put(TREE, MirrorFile("broken.journal", "garbage".toByteArray()))

            viewModel.onFolderPicked(TREE)

            val state = viewModel.uiState.value
            assertThat(state.found).isNotNull()
            assertThat(state.outcome).isNull()
        }

    @Test
    fun `an empty folder stays silent when nothing was lost`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onFolderPicked(TREE)

            val state = viewModel.uiState.value
            assertThat(state.outcome).isNull()
            assertThat(state.restorePending).isFalse()
        }

    @Test
    fun `an empty folder says so when a restore is pending`() =
        runTest(main) {
            mirror.markRestorePending()
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onFolderPicked(TREE)

            val state = viewModel.uiState.value
            assertThat(state.outcome).isEqualTo(BackupOutcome.NOTHING_TO_RESTORE)
            assertThat(state.restorePending).isTrue()
        }

    @Test
    fun `a pending restore is shown on the screen`() =
        runTest(main) {
            mirror.markRestorePending()
            backgroundScope.launch { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.restorePending).isTrue()
        }

    @Test
    fun `restoring brings the data back and ends the reminder`() =
        runTest(main) {
            mirror.markRestorePending()
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, previousInstallation())
            viewModel.onFolderPicked(TREE)

            viewModel.onRestore()

            assertThat(mirror.liked()).containsExactly(SONG)
            val state = viewModel.uiState.value
            assertThat(state.outcome).isEqualTo(BackupOutcome.RESTORED)
            assertThat(state.restorePending).isFalse()
        }

    @Test
    fun `declining the question ends the reminder`() =
        runTest(main) {
            mirror.markRestorePending()
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, previousInstallation())
            viewModel.onFolderPicked(TREE)

            viewModel.onDecline()

            assertThat(viewModel.uiState.value.restorePending).isFalse()
            assertThat(mirror.liked()).isEmpty()
        }

    @Test
    fun `dismissing the reminder changes nothing else`() =
        runTest(main) {
            mirror.markRestorePending()
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onDismissPending()

            val state = viewModel.uiState.value
            assertThat(state.restorePending).isFalse()
            assertThat(state.folder).isNull()
            assertThat(mirror.liked()).isEmpty()
        }

    /** Прошлая установка: лайк и плейлист; её копия. */
    private suspend fun previousInstallation() =
        FakeJournalMirror(clock = { Instant.fromEpochMilliseconds(WRITTEN_MS) }).run {
            scan(listOf(SONG, OTHER))
            like(SONG)
            playlist("Road", listOf(OTHER))
            checkNotNull(copy())
        }

    private companion object {
        const val TREE = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        const val SONG = "/storage/emulated/0/Music/a.mp3"
        const val OTHER = "/storage/emulated/0/Music/b.mp3"
        const val WRITTEN_MS = 1_790_000_000_000L
    }
}
