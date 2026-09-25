package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.core.AppError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Отказы ядра по ходу работы (A3.3) — туда же, куда ошибки воспроизведения:
 * говорить ли о них человеку, решает `ErrorPresenter`. Сюда их отдаёт фасад
 * [PlinthCore] на каждом вызове API, а [CoreInitializer] — весть о базе,
 * собранной заново при запуске.
 *
 * Отказ без слушателя пропадает: экрана нет — и говорить некому. Весть о
 * восстановлении ждёт первого слушателя: ядро открывается раньше экранов.
 */
class CoreErrors {
    private val mutableErrors =
        MutableSharedFlow<AppError>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val restored = MutableStateFlow(false)

    /** Отказы вызовов; подписка — сразу, без задержки на запуск сбора. */
    val errors: SharedFlow<AppError> = mutableErrors.asSharedFlow()

    /** Всё, о чём стоит сказать экранам: [errors] и весть о восстановлении. */
    val events: Flow<AppError> = merge(errors, restored.filter { it }.map { AppError.LibraryRestored })

    fun report(failure: CoreFailure) {
        mutableErrors.tryEmit(failure.error)
    }

    /** База собрана заново из журнала; `ErrorPresenter` скажет об этом один раз. */
    fun libraryRestored() {
        restored.value = true
    }

    private companion object {
        const val BUFFER = 8
    }
}
