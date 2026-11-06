package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test

/**
 * The final native facade has no injectable JVM seam. Guard the narrow error-propagation
 * contract here; actual Gated failure mapping and recovery are exercised in Yu5fOnlineReadinessTest.
 * This source guard does not claim a native runtime reproduction of a failing connect/disconnect.
 */
class Yu5fCoreReadinessContractTest {
    @Test
    fun `setEnabled must not turn CoreFailure into nullable success`() {
        val method = setEnabledSource()
        val swallowing = Regex("\\b(attempt|quietly|runCatching)\\s*[(\\{]").findAll(method).map { it.value }.toList()

        assertThat(swallowing).isEmpty()
    }

    @Test
    fun `setEnabled forwards connect and disconnect failures without catching them`() {
        val method = setEnabledSource()

        assertThat(Regex("\\bcatch\\s*\\(").containsMatchIn(method)).isFalse()
        assertThat(method).contains("core.online.connect(")
        assertThat(method).contains("core.online.disconnect(")
    }

    private fun setEnabledSource(): String {
        val source = SourceTree.mainFile("online/CoreOnlineRepository.kt").readText()
        val signature = "override suspend fun setEnabled("
        assertThat(source).contains(signature)
        val method = source.substringAfter(signature).substringBefore("\n    /**")
        assertThat(method).doesNotContain("override suspend fun search(")
        return method
    }
}
