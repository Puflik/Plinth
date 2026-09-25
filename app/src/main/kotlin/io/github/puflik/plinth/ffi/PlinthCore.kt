package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.generated.Core
import io.github.puflik.plinth.ffi.generated.CoreException
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

    val library = CoreLibrary(this)
    val journal = CoreJournal(this)
    val scan = CoreScan(this)

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

    /** Закрывает ядро и снимает замок с его файлов; после этого фасад не используют. */
    override fun close() {
        if (opened.isInitialized()) opened.value.close()
    }

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
