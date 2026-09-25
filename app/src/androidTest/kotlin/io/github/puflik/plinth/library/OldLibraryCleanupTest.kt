package io.github.puflik.plinth.library

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * База фонотеки v0.1 на Room после D3c не нужна (D3c): при старте её файлы
 * стираются вместе с журналом WAL. Каталог ядра они не задевают.
 */
class OldLibraryCleanupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_room_database_of_v01_is_deleted() =
        runBlocking {
            val files =
                listOf("plinth.db", "plinth.db-wal", "plinth.db-shm").map { name ->
                    context.getDatabasePath(name).apply {
                        parentFile?.mkdirs()
                        writeText("v0.1")
                    }
                }

            OldLibraryCleanup(context, this, Dispatchers.IO).start().join()

            files.forEach { assertThat(it.exists()).isFalse() }
        }
}
