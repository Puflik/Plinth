package io.github.puflik.plinth.ffi

import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.CoreProblem
import io.github.puflik.plinth.ffi.generated.CoreException

/**
 * Отказ ядра, как его видит приложение (A3.3): [error] — что случилось по
 * сути, `message` — текст ошибки ядра для лога (без названий треков и путей,
 * ADR 0010). Фасад [PlinthCore] бросает только его: сгенерированный
 * `CoreException` за пределы пакета `ffi` не выходит.
 */
class CoreFailure(
    val error: AppError.CoreFailed,
    message: String?,
    cause: Throwable,
) : Exception(message, cause) {
    internal companion object {
        /** `CoreException` ядра → отказ с видом ошибки. */
        fun of(exception: CoreException): CoreFailure {
            val problem =
                when (exception) {
                    is CoreException.Storage -> CoreProblem.STORAGE
                    is CoreException.Network -> CoreProblem.NETWORK
                    is CoreException.Parse -> CoreProblem.PARSE
                    is CoreException.Unavailable -> CoreProblem.UNAVAILABLE
                    is CoreException.Internal -> CoreProblem.INTERNAL
                }
            return CoreFailure(AppError.CoreFailed(problem), exception.message, exception)
        }
    }
}
