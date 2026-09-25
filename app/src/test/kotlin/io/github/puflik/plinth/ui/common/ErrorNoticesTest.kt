package io.github.puflik.plinth.ui.common

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.ErrorNotice
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test

/**
 * Ревью №16: свёрнутое приложение сообщения не копит — сказать некому, ошибка
 * остаётся в логе. Вернулся — видишь только то, что случилось при тебе.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorNoticesTest {
    private val owner =
        object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `notices while the app is in the background are not kept`() =
        runTest(UnconfinedTestDispatcher()) {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val errors = MutableSharedFlow<ErrorNotice>(extraBufferCapacity = BUFFER)
            val shown = mutableListOf<ErrorNotice>()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            backgroundScope.launch { showWhileStarted(owner.lifecycle, errors) { shown += it } }

            errors.emit(skipped("before"))
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            errors.emit(skipped("in the background 1"))
            errors.emit(skipped("in the background 2"))
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            errors.emit(skipped("after"))

            assertThat(shown).containsExactly(skipped("before"), skipped("after")).inOrder()
        }

    private fun skipped(title: String) =
        ErrorNotice.Skipped(FailedTrack(title, "content://media/external/audio/media/1", TrackProblem.UNPLAYABLE), 1)

    private companion object {
        const val BUFFER = 8
    }
}
