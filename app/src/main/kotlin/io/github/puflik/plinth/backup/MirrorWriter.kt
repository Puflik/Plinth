package io.github.puflik.plinth.backup

import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Пишет копию журнала в папку человека (C4, ответ автора): через [pause]
 * после последней правки — серия правок даёт одну запись — и сразу, когда
 * приложение уходит в фон ([flush]). Папку не выбрали — не пишет ничего.
 *
 * Запись, которая не удалась, повторит следующая правка или уход в фон:
 * правка считается сохранённой, только когда файл записан.
 */
class MirrorWriter(
    private val mirror: JournalMirror,
    private val folder: MirrorFolder,
    private val settings: MirrorSettings,
    private val scope: CoroutineScope,
    private val pause: Duration = PAUSE,
) {
    private val lock = Mutex()

    /** Последнее состояние журнала, какое видел писатель, и записанное в файл. */
    @Volatile private var seen: Long? = null
    private var written: Long? = null

    /** Слушает правки журнала до конца [scope]. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            mirror.changes
                .onEach { seen = it }
                .drop(1)
                .debounce(pause)
                .collect { write(force = false) }
        }
    }

    /** Приложение уходит в фон: несохранённое — сейчас, не дожидаясь паузы. */
    fun flush() {
        scope.launch { write(force = false) }
    }

    /** Папку только что выбрали или данные восстановили — копия сразу. */
    suspend fun writeNow() = write(force = true)

    private suspend fun write(force: Boolean) =
        lock.withLock {
            val state = seen
            if (!force && written != null && written == state) return@withLock
            val tree = settings.folder.first() ?: return@withLock
            val copy = mirror.copy() ?: return@withLock
            if (folder.write(tree, copy)) {
                written = state
            } else {
                AppLog.w(TAG, "journal copy was not written")
            }
        }

    private companion object {
        const val TAG = "Mirror"
        val PAUSE = 5.seconds
    }
}
