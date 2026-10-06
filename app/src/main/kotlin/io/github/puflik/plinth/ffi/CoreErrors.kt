package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.core.AppError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Отказы ядра по ходу работы (A3.3) — туда же, куда ошибки воспроизведения:
 * говорить ли о них человеку, решает `ErrorPresenter`. Сюда их отдаёт фасад
 * [PlinthCore] на каждом вызове API, а [CoreInitializer] — весть о базе,
 * собранной заново при запуске, и о журнале, начатом заново.
 *
 * Отказ без слушателя пропадает: экрана нет — и говорить некому. Вести о
 * восстановлении и о журнале ждут первого слушателя: ядро открывается раньше
 * экранов.
 */
class CoreErrors {
    private val mutableErrors =
        MutableSharedFlow<AppError>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val restored = MutableStateFlow(false)
    private val startedOver = MutableStateFlow<Boolean?>(null)

    /** Отказы вызовов; подписка — сразу, без задержки на запуск сбора. */
    val errors: SharedFlow<AppError> = mutableErrors.asSharedFlow()

    /** Всё, о чём стоит сказать экранам: [errors] и вести о восстановлении и о журнале. */
    val events: Flow<AppError> =
        merge(
            errors,
            restored.filter { it }.map { AppError.LibraryRestored },
            startedOver.filterNotNull().map { AppError.JournalStartedOver(it) },
        )

    fun report(failure: CoreFailure) {
        mutableErrors.tryEmit(failure.error)
    }

    /** База собрана заново из журнала; `ErrorPresenter` скажет об этом один раз. */
    fun libraryRestored() {
        restored.value = true
    }

    /**
     * Журнал начат заново (Р1.4): снимок не читался. [carried] — база что-то
     * перенесла; `false` — вернуть данные может только копия в папке.
     * `ErrorPresenter` скажет об этом один раз, вместо [libraryRestored].
     */
    fun journalStartedOver(carried: Boolean) {
        startedOver.value = carried
    }

    private companion object {
        const val BUFFER = 8
    }
}
