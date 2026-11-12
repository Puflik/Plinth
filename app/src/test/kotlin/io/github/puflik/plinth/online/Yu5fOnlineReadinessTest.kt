package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.CoreProblem
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Readiness is separate from the existing one-shot search/stream gate; no production implementation here. */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu5fOnlineReadinessTest {
    @Test
    fun `second enable waits for actual registry change before archive search`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val settings = FakeOnlineSettings()
            settings.setProviderEnabled(ARCHIVE, false)
            OnlineSwitch(settings, gated, backgroundScope).start()
            runCurrent()
            val firstRevision = revision(gated.awaitConfiguration(YOUTUBE_ONLY))

            val delayed = Step()
            inner.steps.addLast(delayed)
            settings.setProviderEnabled(ARCHIVE, true)
            runCurrent()
            assertThat(delayed.started.isCompleted).isTrue()
            assertThat(inner.registry.disabled).containsExactly(ARCHIVE)

            val readiness: OnlineReadiness = gated
            val ready = async { readiness.awaitConfiguration(BOTH) }
            val search =
                async {
                    if (ready.await() is OnlineReady.Applied) gated.searchIn(ARCHIVE, "concert") else null
                }
            runCurrent()

            assertThat(ready.isCompleted).isFalse()
            assertThat(search.isCompleted).isFalse()
            assertThat(inner.searchInCalls).isEmpty()

            delayed.release.complete(Unit)
            runCurrent()

            assertThat(revision(ready.await())).isGreaterThan(firstRevision)
            assertThat(search.await()?.provider).isEqualTo(ARCHIVE)
            assertThat(search.await()?.results).isNotEmpty()
            assertThat(inner.searchInCalls).containsExactly(ARCHIVE to "concert")
            assertThat(inner.attempts).containsExactly(Call(true, setOf(ARCHIVE)), Call(true, emptySet())).inOrder()
        }

    @Test
    fun `requested configuration can wait before switch observes it`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val settings = FakeOnlineSettings()
            settings.setProviderEnabled(ARCHIVE, false)
            OnlineSwitch(settings, gated, backgroundScope).start()
            runCurrent()
            revision(gated.awaitConfiguration(YOUTUBE_ONLY))

            val ready = async { gated.awaitConfiguration(BOTH) }
            runCurrent()
            assertThat(ready.isCompleted).isFalse()
            assertThat(inner.attempts).hasSize(1)

            settings.setProviderEnabled(ARCHIVE, true)
            runCurrent()

            revision(ready.await())
            assertThat(inner.attempts).hasSize(2)
            assertThat(inner.searchInCalls).isEmpty()
        }

    @Test
    fun `initial wait observes configuration when switch starts later`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val ready = async { gated.awaitConfiguration(BOTH) }
            runCurrent()

            assertThat(ready.isCompleted).isFalse()
            assertThat(inner.attempts).isEmpty()

            OnlineSwitch(FakeOnlineSettings(), gated, backgroundScope).start()
            runCurrent()

            assertThat(revision(ready.await())).isGreaterThan(0L)
            assertThat(inner.attempts).containsExactly(Call(true, emptySet()))
        }

    @Test
    fun `failed second apply cannot reuse applied ack and switch recovers next setting`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val settings = FakeOnlineSettings()
            settings.setProviderEnabled(ARCHIVE, false)
            OnlineSwitch(settings, gated, backgroundScope).start()
            runCurrent()
            val firstRevision = revision(gated.awaitConfiguration(YOUTUBE_ONLY))

            val failed = Step(coreFailure(CoreProblem.NETWORK))
            inner.steps.addLast(failed)
            settings.setProviderEnabled(ARCHIVE, true)
            runCurrent()
            val ready = async { gated.awaitConfiguration(BOTH) }
            val search =
                async {
                    if (ready.await() is OnlineReady.Applied) gated.searchIn(ARCHIVE, "concert") else null
                }
            runCurrent()
            assertThat(ready.isCompleted).isFalse()

            failed.release.complete(Unit)
            runCurrent()

            assertThat(ready.await()).isEqualTo(OnlineReady.Failed(OnlineProblem.NO_NETWORK))
            assertThat(gated.awaitConfiguration(BOTH)).isEqualTo(OnlineReady.Failed(OnlineProblem.NO_NETWORK))
            assertThat(search.await()).isNull()
            assertThat(inner.searchInCalls).isEmpty()
            assertThat(inner.registry.disabled).containsExactly(ARCHIVE)
            assertThat(inner.attempts).hasSize(2)

            settings.setEnabled(false)
            runCurrent()
            revision(gated.awaitConfiguration(OFF))
            settings.setEnabled(true)
            runCurrent()

            assertThat(revision(gated.awaitConfiguration(BOTH))).isGreaterThan(firstRevision)
            assertThat(gated.searchIn(ARCHIVE, "concert")?.results).isNotEmpty()
            assertThat(inner.searchInCalls).containsExactly(ARCHIVE to "concert")
            assertThat(inner.attempts).hasSize(4)
        }

    @Test
    fun `core and unexpected apply exceptions produce explicit problems`() =
        runTest {
            val failures =
                listOf(
                    coreFailure(CoreProblem.NETWORK) to OnlineProblem.NO_NETWORK,
                    coreFailure(CoreProblem.UNAVAILABLE) to OnlineProblem.PROVIDER_DOWN,
                    coreFailure(CoreProblem.INTERNAL) to OnlineProblem.BROKEN,
                    IllegalStateException("apply failed") to OnlineProblem.BROKEN,
                )
            for ((failure, expected) in failures) {
                val inner = ControlledOnline()
                val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
                val step = Step(failure)
                step.release.complete(Unit)
                inner.steps.addLast(step)

                val thrown = runCatching { gated.setEnabled(true) }.exceptionOrNull()

                assertThat(thrown).isSameInstanceAs(failure)
                assertThat(gated.awaitConfiguration(BOTH)).isEqualTo(OnlineReady.Failed(expected))
                assertThat(inner.registry.applied).isEmpty()
                assertThat(inner.searchInCalls).isEmpty()
            }
        }

    @Test
    fun `pending apply timeout never searches and does not stop application`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val settings = FakeOnlineSettings()
            settings.setProviderEnabled(ARCHIVE, false)
            OnlineSwitch(settings, gated, backgroundScope).start()
            runCurrent()
            revision(gated.awaitConfiguration(YOUTUBE_ONLY))
            val delayed = Step()
            inner.steps.addLast(delayed)
            settings.setProviderEnabled(ARCHIVE, true)
            runCurrent()

            val ready = async { gated.awaitConfiguration(BOTH) }
            val search =
                async {
                    if (ready.await() is OnlineReady.Applied) gated.searchIn(ARCHIVE, "concert") else null
                }
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertThat(ready.isCompleted).isFalse()
            advanceTimeBy(1)
            runCurrent()

            assertThat(ready.await()).isEqualTo(OnlineReady.TimedOut)
            assertThat(search.await()).isNull()
            assertThat(inner.searchInCalls).isEmpty()
            assertThat(inner.cancelledApplications).isEqualTo(0)

            delayed.release.complete(Unit)
            runCurrent()

            revision(gated.awaitConfiguration(BOTH))
            assertThat(inner.registry.disabled).isEmpty()
            assertThat(inner.searchInCalls).isEmpty()
        }

    @Test
    fun `unobserved configuration timeout is bounded and passive`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val ready = async { gated.awaitConfiguration(BOTH) }
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertThat(ready.isCompleted).isFalse()
            advanceTimeBy(1)
            runCurrent()

            assertThat(ready.await()).isEqualTo(OnlineReady.TimedOut)
            assertThat(inner.attempts).isEmpty()
            assertThat(inner.searchInCalls).isEmpty()
        }

    @Test
    fun `cancelling one waiter leaves application and another waiter alive`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val delayed = Step()
            inner.steps.addLast(delayed)
            OnlineSwitch(FakeOnlineSettings(), gated, backgroundScope).start()
            runCurrent()
            val cancellationObserved = CompletableDeferred<Unit>()
            val returned = mutableListOf<OnlineReady>()
            val cancelled =
                launch {
                    try {
                        returned += gated.awaitConfiguration(BOTH)
                    } catch (cancelled: CancellationException) {
                        cancellationObserved.complete(Unit)
                        throw cancelled
                    }
                }
            val survivor = async { gated.awaitConfiguration(BOTH) }
            runCurrent()

            cancelled.cancel()
            runCurrent()

            assertThat(cancelled.isCancelled).isTrue()
            assertThat(cancellationObserved.isCompleted).isTrue()
            assertThat(returned).isEmpty()
            assertThat(survivor.isCompleted).isFalse()
            assertThat(inner.cancelledApplications).isEqualTo(0)
            assertThat(inner.registry.applied).isEmpty()

            delayed.release.complete(Unit)
            runCurrent()

            revision(survivor.await())
            assertThat(inner.registry.applied).hasSize(1)
            assertThat(inner.cancelledApplications).isEqualTo(0)
            assertThat(inner.searchInCalls).isEmpty()
        }

    @Test
    fun `new desired revision supersedes bound waiter and old completion cannot win ABA`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            gated.setEnabled(true, setOf(ARCHIVE))
            val firstRevision = revision(gated.awaitConfiguration(YOUTUBE_ONLY))
            val delayed = Step()
            inner.steps.addLast(delayed)
            val oldApplication = backgroundScope.launch { gated.setEnabled(true) }
            runCurrent()
            val oldWaiter = async { gated.awaitConfiguration(BOTH) }
            runCurrent()
            assertThat(oldWaiter.isCompleted).isFalse()

            val offApplication = backgroundScope.launch { gated.setEnabled(false) }
            runCurrent()

            assertThat(oldWaiter.isCompleted).isTrue()
            assertThat(oldWaiter.await()).isEqualTo(OnlineReady.Superseded)
            assertThat(inner.attempts).hasSize(2)
            val newestApplication = backgroundScope.launch { gated.setEnabled(true) }
            runCurrent()
            val newestWaiter = async { gated.awaitConfiguration(BOTH) }
            runCurrent()
            assertThat(newestWaiter.isCompleted).isFalse()
            assertThat(inner.attempts).hasSize(2)

            val offStep = Step()
            val newestStep = Step()
            inner.steps.addLast(offStep)
            inner.steps.addLast(newestStep)
            delayed.release.complete(Unit)
            runCurrent()

            assertThat(oldApplication.isCompleted).isTrue()
            assertThat(offStep.started.isCompleted).isTrue()
            assertThat(newestStep.started.isCompleted).isFalse()
            assertThat(offApplication.isCompleted).isFalse()
            assertThat(newestApplication.isCompleted).isFalse()
            assertThat(newestWaiter.isCompleted).isFalse()
            assertThat(inner.attempts)
                .containsExactly(Call(true, setOf(ARCHIVE)), Call(true, emptySet()), Call(false, emptySet()))
                .inOrder()

            offStep.release.complete(Unit)
            runCurrent()

            assertThat(offApplication.isCompleted).isTrue()
            assertThat(newestStep.started.isCompleted).isTrue()
            assertThat(newestApplication.isCompleted).isFalse()
            assertThat(newestWaiter.isCompleted).isFalse()
            assertThat(inner.registry.applied.map { it.enabled }).containsExactly(true, true, false).inOrder()
            assertThat(inner.registry.disabled).isEmpty()
            assertThat(inner.attempts)
                .containsExactly(
                    Call(true, setOf(ARCHIVE)),
                    Call(true, emptySet()),
                    Call(false, emptySet()),
                    Call(true, emptySet()),
                ).inOrder()

            newestStep.release.complete(Unit)
            runCurrent()

            assertThat(newestWaiter.isCompleted).isTrue()
            val newestRevision = revision(newestWaiter.await())
            assertThat(newestRevision).isGreaterThan(firstRevision)
            assertThat(newestRevision).isEqualTo(revision(gated.awaitConfiguration(BOTH)))
            assertThat(oldWaiter.await()).isEqualTo(OnlineReady.Superseded)
            assertThat(oldApplication.isCompleted).isTrue()
            assertThat(offApplication.isCompleted).isTrue()
            assertThat(newestApplication.isCompleted).isTrue()
            assertThat(inner.registry.applied.map { it.enabled }).containsExactly(true, true, false, true).inOrder()
            assertThat(inner.registry.disabled).isEmpty()
        }

    @Test
    fun `past matching applied revision is not reused while another config is current`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            gated.setEnabled(true)
            val firstRevision = revision(gated.awaitConfiguration(BOTH))
            gated.setEnabled(true, setOf(ARCHIVE))
            val middleRevision = revision(gated.awaitConfiguration(YOUTUBE_ONLY))
            val next = async { gated.awaitConfiguration(BOTH) }
            runCurrent()

            assertThat(next.isCompleted).isFalse()
            assertThat(middleRevision).isGreaterThan(firstRevision)

            gated.setEnabled(true)
            runCurrent()

            assertThat(revision(next.await())).isGreaterThan(middleRevision)
        }

    @Test
    fun `identical direct applications still have different revisions`() =
        runTest {
            val gated = GatedOnlineRepository(ControlledOnline(), waitLimit = 5.seconds)
            gated.setEnabled(true)
            val before = revision(gated.awaitConfiguration(BOTH))

            gated.setEnabled(true)

            assertThat(revision(gated.awaitConfiguration(BOTH))).isGreaterThan(before)
        }

    @Test
    fun `unknown provider IDs and disabled choices normalize without duplicate apply`() =
        runTest {
            val inner = ControlledOnline()
            val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)
            val settings = FakeOnlineSettings()
            OnlineSwitch(settings, gated, backgroundScope).start()
            runCurrent()
            val before = revision(gated.awaitConfiguration(BOTH))

            settings.setProviderEnabled("future-provider", false)
            runCurrent()

            assertThat(inner.attempts).hasSize(1)
            assertThat(revision(gated.awaitConfiguration(OnlineConfiguration(true, setOf("future-provider")))))
                .isEqualTo(before)

            settings.setEnabled(false)
            runCurrent()
            val offRevision = revision(gated.awaitConfiguration(OFF))
            settings.setProviderEnabled(ARCHIVE, false)
            runCurrent()

            assertThat(inner.attempts).containsExactly(Call(true, emptySet()), Call(false, emptySet())).inOrder()
            assertThat(
                revision(
                    gated.awaitConfiguration(
                        OnlineConfiguration(false, setOf(ARCHIVE, "future-provider")),
                    ),
                ),
            ).isEqualTo(offRevision)
        }

    private fun revision(ready: OnlineReady): Long {
        assertThat(ready).isInstanceOf(OnlineReady.Applied::class.java)
        return (ready as OnlineReady.Applied).revision
    }

    private fun coreFailure(problem: CoreProblem): CoreFailure =
        CoreFailure(AppError.CoreFailed(problem), "apply failed", IllegalStateException("core apply"))

    private data class Call(
        val enabled: Boolean,
        val disabled: Set<String>,
    )

    private class Step(
        val failure: Exception? = null,
    ) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class ControlledOnline(
        val registry: FakeOnlineRepository = FakeOnlineRepository(),
    ) : OnlineRepository by registry {
        val steps = ArrayDeque<Step>()
        val attempts = mutableListOf<Call>()
        val searchInCalls = mutableListOf<Pair<String, String>>()
        var cancelledApplications = 0
            private set

        override suspend fun setEnabled(
            enabled: Boolean,
            disabled: Set<String>,
        ) {
            attempts += Call(enabled, disabled.toSet())
            val step = steps.removeFirstOrNull()
            if (step != null) {
                step.started.complete(Unit)
                try {
                    step.release.await()
                } catch (cancelled: CancellationException) {
                    cancelledApplications++
                    throw cancelled
                }
                step.failure?.let { throw it }
            }
            registry.setEnabled(enabled, disabled)
        }

        override suspend fun searchIn(
            provider: String,
            query: String,
        ): OnlineSection? {
            searchInCalls += provider to query
            return registry.searchIn(provider, query)
        }
    }

    private companion object {
        const val ARCHIVE = OnlineProviderIds.ARCHIVE
        val BOTH = OnlineConfiguration(true, emptySet())
        val YOUTUBE_ONLY = OnlineConfiguration(true, setOf(ARCHIVE))
        val OFF = OnlineConfiguration(false, emptySet())
    }
}
