package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import io.github.puflik.plinth.ffi.CoreHttpStatusTable
import org.junit.Test

/**
 * Какие провайдеры приложение отдаёт ядру (Ю3в). Пока это YouTube; выбор по сборке и по переключателю — Ю3г.
 * Источник YouTube держит память между вызовами (заготовка потоков `resolve`), поэтому провайдер — один на приложение,
 * а не на вызов: репозиторий берёт список один раз.
 */
class OnlineProvidersTest {
    @Test
    fun `the app gives the core youtube and nothing else yet`() {
        val providers = onlineProviders()

        assertThat(providers.map { it.id }).containsExactly("youtube")
        assertThat(providers.single()).isInstanceOf(YoutubeProvider::class.java)
    }

    @Test
    fun `the providers can be built on the status table of the core`() {
        val providers = onlineProviders(CoreHttpStatusTable)

        assertThat(providers.map { it.id }).containsExactly("youtube")
    }

    @Test
    fun `the module hands them to the repository instead of leaving the source unconnected`() {
        val module = SourceTree.mainFile("di/OnlineModule.kt").readText()

        assertThat(module).contains("onlineProviders(")
    }
}
