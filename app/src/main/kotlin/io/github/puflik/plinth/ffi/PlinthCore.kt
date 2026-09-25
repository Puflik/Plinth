package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.generated.Core
import io.github.puflik.plinth.ffi.generated.CoreException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import io.github.puflik.plinth.ffi.generated.panicForTest as corePanicForTest
import io.github.puflik.plinth.ffi.generated.start as coreStart

/**
 * Фасад над Rust-ядром (A3.2) — единственное место приложения, которое
 * зовёт сгенерированные биндинги (`FfiBoundaryTest`). API — по темам, как в
 * ядре: [library] — чтение библиотеки, [journal] — действия пользователя.
 *
 * Ядро живёт в [dataDir]: база, журнал пользовательских данных и
 * идентификатор установки (docs/adr/0007-journal-as-source-of-truth.md).
 * Открывается при первом обращении; не открылось — следующее обращение
 * попробует снова. Вызовы блокирующие — не из главного потока.
 *
 * Отказ ядра приходит исключением [CoreFailure]. Отказ вызова API к тому же
 * уходит в [errors] — дальше `ErrorPresenter` решает, говорить ли человеку.
 *
 * Изменения каталога видны в [catalogChanges]: по нему списки перечитывают ядро.
 */
class PlinthCore(
    private val logLevel: LogLevel,
    private val dataDir: File,
    private val errors: CoreErrors,
) : AutoCloseable {
    // lazy не запоминает исключение: не вышло — следующий вызов попробует снова.
    private val started by lazy { coreStart(CoreLogBridge(), logLevel.toCore()) }
    private val opened =
        lazy {
            started
            Core.open(dataDir.path)
        }

    private val changes = MutableStateFlow(0L)

    val library = CoreLibrary(this)
    val journal = CoreJournal(this)
    val scan = CoreScan(this)

    /**
     * Сигнал «каталог изменился» (D3b) — счётчик. Его двигают запись каждой
     * пачки скана и конец скана: экраны видят музыку, не дожидаясь конца.
     */
    val catalogChanges: StateFlow<Long> = changes.asStateFlow()

    /** Запускает ядро — логгер и хук паники, — если оно ещё не запущено. */
    @Throws(CoreFailure::class)
    fun start() = mapped { started }

    /**
     * Открывает ядро, если оно ещё не открыто, и говорит, что пришлось
     * восстанавливать. Отказ — исключением, но не в [errors]: пока экраны от
     * ядра не зависят (до D3), неудачный запуск — дело лога.
     */
    @Throws(CoreFailure::class)
    fun open(): StartupReport = mapped { opened.value.startupReport().toApp() }

    /** Намеренная паника в ядре — для проверки, что она приходит исключением. */
    @Throws(CoreFailure::class)
    fun panicForTest(message: String) {
        mapped {
            started
            corePanicForTest(message)
        }
    }

    /**
     * Кладёт [files] в каталог новыми треками — как их записал бы скан, без
     * чтения файлов. Для контракта фонотеки на эмуляторе.
     */
    @Throws(CoreFailure::class)
    fun seedForTest(files: List<CoreTestFile>) {
        call { core -> core.seedForTest(files.map(CoreTestFile::toRust)) }
        catalogChanged()
    }

    /** Файлы [paths] пропали — их треки скрываются отовсюду, как после скана. */
    @Throws(CoreFailure::class)
    fun hideForTest(paths: List<String>) {
        call { it.hideForTest(paths) }
        catalogChanged()
    }

    /** Закрывает ядро и снимает замок с его файлов; после этого фасад не используют. */
    override fun close() {
        if (opened.isInitialized()) opened.value.close()
    }

    /** Каталог изменился — списки перечитают ядро. */
    internal fun catalogChanged() = changes.update { it + 1 }

    /**
     * Вызов API, отказ которого — не сбой ядра, а свойство данных (файл
     * пропал после скана): исключением, но не в [errors].
     */
    internal fun <T> quietCall(block: (Core) -> T): T = mapped { block(opened.value) }

    /** Вызов API: отказ — исключением и в поток [errors]. */
    internal fun <T> call(block: (Core) -> T): T =
        try {
            block(opened.value)
        } catch (e: CoreException) {
            val failure = CoreFailure.of(e)
            errors.report(failure)
            throw failure
        }

    private inline fun <T> mapped(block: () -> T): T =
        try {
            block()
        } catch (e: CoreException) {
            throw CoreFailure.of(e)
        }
}
