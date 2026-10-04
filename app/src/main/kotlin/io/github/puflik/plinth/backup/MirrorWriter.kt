package io.github.puflik.plinth.backup

import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.MirrorFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Как идёт копия: когда записана в последний раз (в этом запуске) и не
 * отказывает ли папка — вынули карту, переименовали, отозвали разрешение.
 */
data class MirrorStatus(
    val lastWrittenAt: Instant? = null,
    val failing: Boolean = false,
)

/**
 * Пишет копию журнала в папку человека (C4, ответ автора): через [pause]
 * после последней правки — серия правок даёт одну запись — и сразу, когда
 * приложение уходит в фон ([flush]). Папку не выбрали — не пишет ничего.
 *
 * Запись, которая не удалась, повторит следующая правка или уход в фон:
 * правка считается сохранённой, только когда файл записан. Отказ виден в
 * [status]: иначе человек узнает о нём при переустановке, когда поздно.
 */
class MirrorWriter(
    private val mirror: JournalMirror,
    private val folder: MirrorFolder,
    private val settings: MirrorSettings,
    private val scope: CoroutineScope,
    private val pause: Duration = PAUSE,
) {
    private val lock = Mutex()
    private val mutableStatus = MutableStateFlow(MirrorStatus())

    val status: StateFlow<MirrorStatus> = mutableStatus.asStateFlow()

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

    /** Данные восстановили — копия сразу. `false` — папка отказала. */
    suspend fun writeNow(): Boolean = write(force = true)

    /**
     * Пробная запись в [tree] до того, как папку запомнили: выбрали папку, куда
     * писать нельзя, — человек должен узнать об этом сразу. `false` — отказ.
     */
    suspend fun writeTo(tree: String): Boolean =
        lock.withLock {
            val copy = mirror.copy() ?: return@withLock true
            store(tree, copy, seen)
        }

    private suspend fun write(force: Boolean): Boolean =
        lock.withLock {
            val state = seen
            if (!force && written != null && written == state) return@withLock true
            val tree = settings.folder.first() ?: return@withLock true
            val copy = mirror.copy() ?: return@withLock true
            store(tree, copy, state)
        }

    private suspend fun store(
        tree: String,
        copy: MirrorFile,
        state: Long?,
    ): Boolean {
        val done = folder.write(tree, copy)
        if (done) {
            written = state
            mutableStatus.value = MirrorStatus(lastWrittenAt = Clock.System.now(), failing = false)
        } else {
            AppLog.w(TAG, "journal copy was not written")
            mutableStatus.update { it.copy(failing = true) }
        }
        return done
    }

    private companion object {
        const val TAG = "Mirror"
        val PAUSE = 5.seconds
    }
}
