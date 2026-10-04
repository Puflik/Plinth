package io.github.puflik.plinth.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.backup.JournalMirror
import io.github.puflik.plinth.backup.MirrorFolder
import io.github.puflik.plinth.backup.MirrorSettings
import io.github.puflik.plinth.backup.MirrorWriter
import io.github.puflik.plinth.backup.TreeLabel
import io.github.puflik.plinth.ffi.MirrorFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Что показывает раздел «Копия ваших данных» (C4).
 *
 * @property folder папка копии для человека — «Music»; не выбрана — `null`.
 * @property found в папке данные прошлой установки — вопрос «Восстановить?».
 * @property busy идёт чтение папки или восстановление.
 * @property outcome итог последнего действия — для сообщения; показали — [BackupViewModel.onOutcomeShown].
 * @property copyFailing папка копии отказывает в записи: выбрана, но копия не пишется.
 * @property lastCopyAt когда копия записана в последний раз в этом запуске; не писалась — `null`.
 */
data class BackupUiState(
    val folder: String? = null,
    val found: FoundData? = null,
    val busy: Boolean = false,
    val outcome: BackupOutcome? = null,
    val copyFailing: Boolean = false,
    val lastCopyAt: Instant? = null,
)

/** Что нашлось в папке: «N лайков, M плейлистов, от даты». */
data class FoundData(
    val likes: Int,
    val playlists: Int,
    val writtenAt: Instant?,
)

enum class BackupOutcome {
    RESTORED,

    /** Система не дала доступ к папке — её не запомнили. */
    NO_ACCESS,

    /** Читать из папки можно, а писать нельзя — её не запомнили. */
    NOT_WRITABLE,

    /** Ядро не смогло прочесть или влить копии. */
    FAILED,
}

/**
 * Копия данных (C4): папку выбирает человек, копия пишется сразу. Нашлись
 * данные прошлой установки — вопрос «Восстановить?»; «Восстановить» сливает
 * журнал (CRDT), сделанное до ответа не теряется, а «Не нужно» ничего не
 * трогает — ни журнал, ни файлы в папке.
 *
 * Общий для шага мастера и настроек.
 */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val settings: MirrorSettings,
        private val folder: MirrorFolder,
        private val mirror: JournalMirror,
        private val writer: MirrorWriter,
    ) : ViewModel() {
        private val screen = MutableStateFlow(BackupUiState())

        /** Файлы папки, которые предложено восстановить. */
        private var offered: List<MirrorFile> = emptyList()

        val uiState: StateFlow<BackupUiState> =
            combine(settings.folder, screen, writer.status) { tree, state, status ->
                state.copy(
                    folder = tree?.let(TreeLabel::of),
                    copyFailing = tree != null && status.failing,
                    lastCopyAt = status.lastWrittenAt,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupUiState())

        /** Человек выбрал папку [tree] системным диалогом. */
        fun onFolderPicked(tree: String) {
            viewModelScope.launch {
                screen.update { it.copy(busy = true, found = null, outcome = null) }
                if (!folder.adopt(tree)) return@launch done(BackupOutcome.NO_ACCESS)
                val files = folder.read(tree) ?: return@launch done(BackupOutcome.NO_ACCESS)
                // Папка, куда писать нельзя, не запоминается: иначе она выглядит принятой,
                // а копии нет (ревью v0.2, №3).
                if (!writer.writeTo(tree)) return@launch done(BackupOutcome.NOT_WRITABLE)
                settings.setFolder(tree)
                val found = mirror.inspect(files)
                if (found?.news == true) {
                    offered = files
                    screen.update { it.copy(found = FoundData(found.likes, found.playlists, found.writtenAt)) }
                }
                done(outcome = if (found == null) BackupOutcome.FAILED else null)
            }
        }

        fun onRestore() {
            val files = offered
            offered = emptyList()
            viewModelScope.launch {
                screen.update { it.copy(busy = true, found = null) }
                val restored = mirror.restore(files)
                if (restored != null) writer.writeNow()
                done(if (restored != null) BackupOutcome.RESTORED else BackupOutcome.FAILED)
            }
        }

        fun onDecline() {
            offered = emptyList()
            screen.update { it.copy(found = null) }
        }

        fun onOutcomeShown() = screen.update { it.copy(outcome = null) }

        private fun done(outcome: BackupOutcome?) = screen.update { it.copy(busy = false, outcome = outcome) }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
