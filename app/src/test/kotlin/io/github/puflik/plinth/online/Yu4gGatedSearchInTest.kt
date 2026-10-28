package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.FakeOnlineRepository.Companion.PROVIDER
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Ю4г (`docs/work/yu4g.md`) и `GatedOnlineRepository`: поиск у одного провайдера (`searchIn`) ждёт первого применения
 * переключателя, как поиск по всем, альбом и радио, — запуск радио случается и на холодном старте, одновременно с
 * `OnlineSwitch`. `GatedOnlineRepository` делегирует остальное внутреннему репозиторию (`by inner`), и новый член
 * интерфейса без переопределения прошёл бы мимо ворот: ядро ответило бы «источники выключены», а запуск сказал бы
 * человеку «не нашлось».
 *
 * `OnlineRepository.searchIn` — новый API (сигнатура в файле задачи): пока его нет, тесты приложения не собираются.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Yu4gGatedSearchInTest {
    private val inner = FakeOnlineRepository()
    private val gated = GatedOnlineRepository(inner, waitLimit = 5.seconds)

    @Test
    fun `a search in one provider that comes before the switch waits for it and then asks`() =
        runTest {
            val search = async { gated.searchIn(PROVIDER, "concert") }
            runCurrent()
            val waiting = !search.isCompleted

            gated.setEnabled(true)
            runCurrent()

            assertThat(waiting).isTrue()
            assertThat(search.isCompleted).isTrue()
            val section = checkNotNull(search.await())
            assertThat(section.provider).isEqualTo(PROVIDER)
            assertThat(section.results).hasSize(1)
            assertThat(inner.searches).containsExactly("concert")
        }

    @Test
    fun `a switch that turns the sources off lets the search in one provider through at once with nothing`() =
        runTest {
            val search = async { gated.searchIn(PROVIDER, "concert") }
            runCurrent()

            gated.setEnabled(false)
            runCurrent()

            assertThat(search.isCompleted).isTrue()
            assertThat(search.await()).isNull()
        }

    @Test
    fun `a switch that never comes lets the search in one provider through after the wait limit`() =
        runTest {
            val search = async { gated.searchIn(PROVIDER, "concert") }
            runCurrent()
            val waiting = !search.isCompleted

            advanceTimeBy(5_001)
            runCurrent()

            assertThat(waiting).isTrue()
            assertThat(search.isCompleted).isTrue()
            assertThat(search.await()).isNull()
        }

    @Test
    fun `after the switch a search in one provider goes straight through`() =
        runTest {
            gated.setEnabled(true)

            val section = checkNotNull(gated.searchIn(PROVIDER, "concert"))

            assertThat(section.results).hasSize(1)
            assertThat(gated.searchIn("youtube", "concert")).isNull()
        }
}
