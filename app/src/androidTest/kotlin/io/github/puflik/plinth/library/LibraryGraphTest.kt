package io.github.puflik.plinth.library

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import io.github.puflik.plinth.di.LibraryEntryPoint
import org.junit.Test

/**
 * Граф приложения отдаёт фонотеку на Room, одну на процесс (C3).
 *
 * Пока фасад никто не внедряет, Dagger не проверяет его привязки при сборке:
 * сломанный `LibraryModule` собирался бы молча. Этот тест — первый
 * потребитель. Базу он не открывает: Room открывает файл при первом запросе.
 */
class LibraryGraphTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun app_graph_gives_one_room_repository() {
        val graph = EntryPointAccessors.fromApplication<LibraryEntryPoint>(context)

        val repository = graph.libraryRepository()

        assertThat(repository).isInstanceOf(RoomLibraryRepository::class.java)
        assertThat(graph.libraryRepository()).isSameInstanceAs(repository)
    }
}
