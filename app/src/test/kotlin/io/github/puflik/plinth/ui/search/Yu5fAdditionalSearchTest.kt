package io.github.puflik.plinth.ui.search

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.online.OnlineConfiguration
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineReady
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Retry after grace must use the current settings before the restarted query debounce finishes. */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu5fAdditionalSearchTest : Yu5fReadinessHarness() {
    @Test
    fun `removed youtube retry before resumed debounce creates no obsolete wait or delayed replay`() =
        runTest(dispatcher) {
            val visible = cacheBothProviders()
            val archive = sections().single { it.provider == ARCHIVE }
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            val resumedAt = testScheduler.currentTime
            screen()
            runCurrent()
            retry(YOUTUBE)
            runCurrent()

            assertThat(testScheduler.currentTime).isEqualTo(resumedAt)
            assertThat(readiness.calls).hasSize(2)
            assertThat(readiness.calls.map { it.configuration }).containsExactly(ALL, ALL)
            assertThat(online.calls.map { it.provider to it.query }).containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY)
            assertThat(sections()).containsExactly(archive)
            loading()

            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls.map { it.provider to it.query }).containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY)
            assertThat(sections()).containsExactly(archive)
            loading()
        }

    @Test
    fun `master off retry before resumed debounce creates no obsolete wait loading or delayed replay`() =
        runTest(dispatcher) {
            val visible = cacheBothProviders()
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            settings.setEnabled(false)
            runCurrent()
            val resumedAt = testScheduler.currentTime
            screen()
            runCurrent()
            retry(YOUTUBE)
            runCurrent()

            assertThat(testScheduler.currentTime).isEqualTo(resumedAt)
            assertThat(readiness.calls).hasSize(2)
            assertThat(readiness.calls.map { it.configuration }).containsExactly(ALL, ALL)
            assertThat(online.calls.map { it.provider to it.query }).containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY)
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)

            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(2)
            assertThat(online.calls.map { it.provider to it.query }).containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY)
            assertThat(viewModel.uiState.value.online).isEqualTo(OnlineResults.None)
        }

    @Test
    fun `retained archive retry before resumed debounce waits once for current archive only configuration`() =
        runTest(dispatcher) {
            val visible = cacheBothProviders()
            visible.cancel()
            advanceTimeBy(GRACE_MS + 1)
            runCurrent()
            settings.setProviderEnabled(YOUTUBE, false)
            runCurrent()
            val resumedAt = testScheduler.currentTime
            screen()
            runCurrent()
            retry(ARCHIVE)
            retry(ARCHIVE)
            runCurrent()

            assertThat(testScheduler.currentTime).isEqualTo(resumedAt)
            assertThat(readiness.calls.map { it.configuration }).containsExactly(ALL, ALL, ARCHIVE_ONLY).inOrder()
            val pending = readiness.calls.last()
            assertThat(online.calls.map { it.provider to it.query }).containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY)
            assertThat(sections()).isEmpty()
            loading(ARCHIVE)
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            assertThat(readiness.calls.last()).isSameInstanceAs(pending)
            assertThat(pending.cancelled).isFalse()
            assertThat(online.calls).hasSize(2)
            loading(ARCHIVE)

            pending.outcome.complete(OnlineReady.Applied(2L))
            runCurrent()
            assertThat(online.calls.map { it.provider to it.query })
                .containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY, ARCHIVE to QUERY)
            loading(ARCHIVE)
            val refreshed = section(ARCHIVE, suffix = "retry")
            online.calls
                .last()
                .outcome
                .complete(refreshed)
            runCurrent()
            assertThat(sections()).containsExactly(refreshed)
            loading()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertThat(readiness.calls).hasSize(3)
            assertThat(online.calls).hasSize(3)
            assertThat(sections()).containsExactly(refreshed)
            loading()
        }

    private fun TestScope.cacheBothProviders(): Job {
        val visible = screen()
        search(QUERY)
        assertThat(readiness.calls.map { it.configuration }).containsExactly(ALL, ALL)
        assertThat(online.calls).isEmpty()
        readiness.calls.forEach { it.outcome.complete(OnlineReady.Applied(1L)) }
        runCurrent()
        assertThat(online.calls.map { it.provider to it.query }).containsExactly(ARCHIVE to QUERY, YOUTUBE to QUERY)
        val archive = section(ARCHIVE)
        val youtube = section(YOUTUBE, OnlineProblem.NO_NETWORK)
        online.calls
            .single { it.provider == ARCHIVE }
            .outcome
            .complete(archive)
        online.calls
            .single { it.provider == YOUTUBE }
            .outcome
            .complete(youtube)
        runCurrent()
        assertThat(sections()).containsExactly(archive, youtube).inOrder()
        assertThat(readiness.calls.all { it.outcome.isCompleted }).isTrue()
        assertThat(online.calls.all { it.outcome.isCompleted }).isTrue()
        loading()
        return visible
    }
}

private const val ARCHIVE = OnlineProviderIds.ARCHIVE
private const val YOUTUBE = OnlineProviderIds.YOUTUBE
private const val QUERY = "concert"
private const val GRACE_MS = 5_000L
private val ALL = OnlineConfiguration(true, emptySet())
private val ARCHIVE_ONLY = OnlineConfiguration(true, setOf(YOUTUBE))

private fun section(
    provider: String,
    problem: OnlineProblem? = null,
    suffix: String = "cached",
): OnlineSection {
    val results =
        if (problem == null) {
            listOf(OnlineResult("$provider-$suffix", OnlineKind.ALBUM, "$provider-$suffix", null, null))
        } else {
            emptyList()
        }
    return OnlineSection(provider, results, problem)
}
