package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Р9а: `FakeUserDataRepository.rekey` изображает перепривязку каталога — на нём стоят проверки
 * плеера (`R9aPlayerRelinkTest`). Как в приложении, смена ID под путём и сигнал «каталог
 * изменился» — два шага: поиск по пути видит смену сразу, поток по пути — после сигнала.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R9aFakeRekeyTest {
    private val repository = FakeUserDataRepository()

    @Test
    fun `a rekeyed file leads to the new track and the stream announces it`() =
        runTest(UnconfinedTestDispatcher()) {
            val old = repository.add(SONG).single()
            val seen = mutableListOf<TrackId?>()
            backgroundScope.launch { repository.trackAtChanges(SONG).toList(seen) }

            repository.rekey(SONG, JOURNAL_ID)

            assertThat(repository.trackAt(SONG)).isEqualTo(JOURNAL_ID)
            assertThat(seen).containsExactly(old, JOURNAL_ID).inOrder()
        }

    @Test
    fun `a rekey that is not announced is seen by a lookup but not by the stream`() =
        runTest(UnconfinedTestDispatcher()) {
            val old = repository.add(SONG).single()
            val seen = mutableListOf<TrackId?>()
            backgroundScope.launch { repository.trackAtChanges(SONG).toList(seen) }

            repository.rekey(SONG, JOURNAL_ID, announce = false)
            val lookup = repository.trackAt(SONG)
            val beforeSignal = seen.toList()
            repository.catalogChanged()

            assertThat(lookup).isEqualTo(JOURNAL_ID)
            assertThat(beforeSignal).containsExactly(old)
            assertThat(seen).containsExactly(old, JOURNAL_ID).inOrder()
        }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/song.mp3"
        val JOURNAL_ID = TrackId("journal-song")
    }
}
