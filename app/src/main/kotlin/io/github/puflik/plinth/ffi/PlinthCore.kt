package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.generated.CoreException
import io.github.puflik.plinth.ffi.generated.hello as coreHello
import io.github.puflik.plinth.ffi.generated.panicForTest as corePanicForTest
import io.github.puflik.plinth.ffi.generated.start as coreStart

/**
 * Фасад над Rust-ядром (A3.2) — единственное место приложения, которое
 * зовёт сгенерированные биндинги (`FfiBoundaryTest`).
 *
 * Ядро запускается при первом обращении: грузится libplinth_ffi.so, ставятся
 * логгер и хук паники. Вызовы блокирующие — не из главного потока. Ошибка и
 * паника ядра приходят как [CoreException].
 */
class PlinthCore(
    private val logLevel: LogLevel,
) {
    // lazy не запоминает исключение: не запустилось — следующий вызов попробует снова.
    private val started by lazy { coreStart(CoreLogBridge(), logLevel.toCore()) }

    /** Запускает ядро, если оно ещё не запущено. */
    @Throws(CoreException::class)
    fun start() = started

    /** Проверка связки: строка уходит в Rust и возвращается приветствием. */
    @Throws(CoreException::class)
    fun hello(name: String): String {
        start()
        return coreHello(name)
    }

    /** Намеренная паника в ядре — для проверки, что она приходит исключением. */
    @Throws(CoreException::class)
    fun panicForTest(message: String) {
        start()
        corePanicForTest(message)
    }
}
