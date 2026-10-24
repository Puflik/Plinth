package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProvider
import io.github.puflik.plinth.ffi.OnlineQuality
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineTrack
import io.github.puflik.plinth.ffi.StreamAddress
import org.junit.Test

/** Что подключить ядру, когда часть провайдеров выключена (Ю3г): чистая функция, ядро не нужно. */
class ActiveSourcesTest {
    private val youtube = Stub(OnlineProviderIds.YOUTUBE)

    @Test
    fun `nothing switched off connects the archive and every provider`() {
        val active = activeSources(listOf(youtube), emptySet())

        assertThat(active.archive).isTrue()
        assertThat(active.providers).containsExactly(youtube)
        assertThat(active.isEmpty).isFalse()
    }

    @Test
    fun `youtube switched off leaves the archive alone`() {
        val active = activeSources(listOf(youtube), setOf(OnlineProviderIds.YOUTUBE))

        assertThat(active.archive).isTrue()
        assertThat(active.providers).isEmpty()
        assertThat(active.isEmpty).isFalse()
    }

    @Test
    fun `the archive switched off leaves the providers of the app`() {
        val active = activeSources(listOf(youtube), setOf(OnlineProviderIds.ARCHIVE))

        assertThat(active.archive).isFalse()
        assertThat(active.providers).containsExactly(youtube)
        assertThat(active.isEmpty).isFalse()
    }

    @Test
    fun `everything switched off leaves nothing to connect`() {
        val active = activeSources(listOf(youtube), setOf(OnlineProviderIds.ARCHIVE, OnlineProviderIds.YOUTUBE))

        assertThat(active.archive).isFalse()
        assertThat(active.providers).isEmpty()
        assertThat(active.isEmpty).isTrue()
    }

    @Test
    fun `the order of the providers is kept and an id nobody knows means nothing`() {
        val (first, second, third) = listOf(Stub("a.test"), Stub("b.test"), Stub("c.test"))

        val active = activeSources(listOf(first, second, third), setOf(second.id, "no.such.provider"))

        assertThat(active.archive).isTrue()
        assertThat(active.providers).containsExactly(first, third).inOrder()
    }

    @Test
    fun `without providers of the app the archive is all there is`() {
        assertThat(activeSources(emptyList(), emptySet()).archive).isTrue()
        assertThat(activeSources(emptyList(), emptySet()).isEmpty).isFalse()
        assertThat(activeSources(emptyList(), setOf(OnlineProviderIds.ARCHIVE)).isEmpty).isTrue()
    }

    /** Провайдер, которого не спрашивают: важны только ID и порядок. */
    private class Stub(
        override val id: String,
    ) : OnlineProvider {
        override val finds: Set<OnlineKind> = setOf(OnlineKind.TRACK)
        override val bestQuality: OnlineQuality = OnlineQuality.STANDARD

        override fun search(
            query: String,
            limit: Int,
        ): List<OnlineResult> = error("not asked")

        override fun resolve(item: String): List<OnlineTrack> = error("not asked")

        override fun stream(source: String): StreamAddress = error("not asked")
    }
}
