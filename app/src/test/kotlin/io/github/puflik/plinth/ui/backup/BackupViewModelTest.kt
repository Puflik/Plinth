package io.github.puflik.plinth.ui.backup

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.backup.FakeJournalMirror
import io.github.puflik.plinth.backup.FakeMirrorFolder
import io.github.puflik.plinth.backup.FakeMirrorSettings
import io.github.puflik.plinth.backup.MirrorWriter
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
 * Копия данных на экране (C4): выбрали папку — копия пишется сразу; нашлись
 * данные прошлой установки — вопрос «Восстановить?» с числами; ответ «да» —
 * слияние, «нет» — ничего не трогается.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
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
    fun `a picked folder is kept and gets a copy at once`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.onFolderPicked(TREE)

            assertThat(settings.folder.first()).isEqualTo(TREE)
            assertThat(folder.files(TREE)).hasSize(1)
            val state = viewModel.uiState.value
            assertThat(state.folder).isEqualTo("Music")
            assertThat(state.found).isNull()
        }

    /** Папка, куда писать нельзя, не выглядит принятой (ревью v0.2, №3). */
    @Test
    fun `a folder that cannot be written is not kept and says so`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.failNextWrite = true

            viewModel.onFolderPicked(TREE)

            assertThat(settings.folder.first()).isNull()
            val state = viewModel.uiState.value
            assertThat(state.outcome).isEqualTo(BackupOutcome.NOT_WRITABLE)
            assertThat(state.folder).isNull()
            assertThat(state.found).isNull()
        }

    @Test
    fun `data of a previous installation is offered with its counts`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, previousInstallation())

            viewModel.onFolderPicked(TREE)

            assertThat(viewModel.uiState.value.found)
                .isEqualTo(FoundData(likes = 1, playlists = 1, writtenAt = Instant.fromEpochMilliseconds(WRITTEN_MS)))
        }

    @Test
    fun `restore merges the data and writes a fresh copy`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, previousInstallation())
            viewModel.onFolderPicked(TREE)
            val writes = folder.writes

            viewModel.onRestore()

            assertThat(mirror.liked()).containsExactly(SONG)
            assertThat(mirror.playlists()).containsKey("Road")
            val state = viewModel.uiState.value
            assertThat(state.found).isNull()
            assertThat(state.outcome).isEqualTo(BackupOutcome.RESTORED)
            assertThat(folder.writes).isEqualTo(writes + 1)
        }

    @Test
    fun `declining leaves both the journal and the folder as they were`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            val previous = previousInstallation()
            folder.put(TREE, previous)
            viewModel.onFolderPicked(TREE)

            viewModel.onDecline()

            assertThat(viewModel.uiState.value.found).isNull()
            assertThat(mirror.liked()).isEmpty()
            assertThat(folder.files(TREE).map { it.name }).contains(previous.name)
        }

    /** Выбрали ту же папку ещё раз после восстановления — спрашивать нечего. */
    @Test
    fun `restored data is not offered again`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.put(TREE, previousInstallation())
            viewModel.onFolderPicked(TREE)
            viewModel.onRestore()
            viewModel.onOutcomeShown()

            viewModel.onFolderPicked(TREE)

            assertThat(viewModel.uiState.value.found).isNull()
            assertThat(viewModel.uiState.value.outcome).isNull()
        }

    @Test
    fun `a folder without access is not kept`() =
        runTest(main) {
            backgroundScope.launch { viewModel.uiState.collect {} }
            folder.refused += TREE

            viewModel.onFolderPicked(TREE)

            assertThat(settings.folder.first()).isNull()
            assertThat(viewModel.uiState.value.outcome).isEqualTo(BackupOutcome.NO_ACCESS)
        }

    @Test
    fun `a folder chosen earlier is shown`() =
        runTest(main) {
            settings.setFolder(TREE)
            backgroundScope.launch { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.first { it.folder != null }.folder).isEqualTo("Music")
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
