package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.CoreProblem
import io.github.puflik.plinth.ffi.generated.CoreException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** `CoreException` ядра → ошибка приложения (A3.3): вид — для решения, текст — для лога. */
class CoreFailureTest {
    @Test
    fun `every kind of core error has its problem`() {
        val cases =
            mapOf(
                CoreException.Storage("storage: disk full") to CoreProblem.STORAGE,
                CoreException.Network("network: timeout") to CoreProblem.NETWORK,
                CoreException.Parse("parse: bad tag") to CoreProblem.PARSE,
                CoreException.Unavailable("unavailable: gone") to CoreProblem.UNAVAILABLE,
                CoreException.Internal("internal: panicked at lib.rs:1:1: boom") to CoreProblem.INTERNAL,
            )

        for ((exception, problem) in cases) {
            val failure = CoreFailure.of(exception)

            assertThat(failure.error).isEqualTo(AppError.CoreFailed(problem))
            assertThat(failure.message).isEqualTo(exception.message)
            assertThat(failure.cause).isSameInstanceAs(exception)
        }
    }

    /** Отказ уходит в общий поток ошибок: показывать ли его, решает `ErrorPresenter`. */
    @Test
    fun `a reported failure reaches the error stream`() =
        runTest(UnconfinedTestDispatcher()) {
            val errors = CoreErrors()
            val received = mutableListOf<AppError>()
            backgroundScope.launch { errors.errors.toList(received) }

            errors.report(CoreFailure.of(CoreException.Internal("internal: boom")))

            assertThat(received).containsExactly(AppError.CoreFailed(CoreProblem.INTERNAL))
        }
}
