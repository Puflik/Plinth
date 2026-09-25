package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.core.AppError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Отказы ядра по ходу работы (A3.3) — туда же, куда ошибки воспроизведения:
 * говорить ли о них человеку, решает `ErrorPresenter`. Сюда их отдаёт фасад
 * [PlinthCore] на каждом вызове API. Запуск ядра — нет: пока экраны от него
 * не зависят (до D3), неудачный запуск остаётся в логе.
 */
class CoreErrors {
    private val mutableErrors =
        MutableSharedFlow<AppError>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val errors: SharedFlow<AppError> = mutableErrors.asSharedFlow()

    fun report(failure: CoreFailure) {
        mutableErrors.tryEmit(failure.error)
    }

    private companion object {
        const val BUFFER = 8
    }
}
