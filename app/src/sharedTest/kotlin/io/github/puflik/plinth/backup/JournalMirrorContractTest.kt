package io.github.puflik.plinth.backup

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.MirrorFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Контракт `JournalMirror` (C4) — требования к копии журнала в папке
 * человека. `FakeJournalMirror` проходит его на JVM, ядро на Rust
 * (`CoreJournalMirror`) — на эмуляторе: писатель копии и экраны
 * восстановления тестируются на фейке.
 *
 * Установка — своя фонотека и свой журнал; переустановка — новая установка
 * с теми же файлами. ID треков выдаёт установка, у новой они другие: вернуть
 * лайки на свои файлы — забота копии.
 *
 * Имена тестов — через подчёркивание: класс собирается и в APK для эмулятора.
 */
abstract class JournalMirrorContractTest {
    /** Установка приложения для контракта: журнал, фонотека и её копия. */
    interface Installation {
        val mirror: JournalMirror

        /** Кладёт файлы [paths] в фонотеку, как скан. */
        suspend fun scan(paths: List<String>)

        suspend fun like(path: String)

        /** Новый плейлист [name] из файлов [paths] по порядку. */
        suspend fun playlist(
            name: String,
            paths: List<String>,
        )

        /** Файлы лайкнутых треков фонотеки. */
        suspend fun liked(): Set<String>

        /** Плейлисты: имя → файлы треков по порядку. */
        suspend fun playlists(): Map<String, List<String>>
    }

    /** Новая установка с пустыми журналом и фонотекой. */
    protected abstract fun install(): Installation

    protected open fun uninstall(installation: Installation) = Unit

    @Test
    fun a_copy_of_another_installation_is_found_with_its_counts() =
        contract { install ->
            val copy = old(install)
            val new = install()

            val found = checkNotNull(new.mirror.inspect(listOf(copy)))

            assertThat(listOf(found.likes, found.playlists, found.unreadable)).containsExactly(1, 1, 0).inOrder()
            assertThat(found.news).isTrue()
            assertThat(found.writtenAt).isNotNull()
        }

    @Test
    fun the_own_copy_is_not_news() =
        contract { install ->
            val installation = install()
            installation.scan(listOf(SONG))
            installation.like(SONG)

            val found = checkNotNull(installation.mirror.inspect(listOf(checkNotNull(installation.mirror.copy()))))

            assertThat(found.news).isFalse()
        }

    /** Файл — на установку: новая установка не перезапишет копию прошлой. */
    @Test
    fun installations_write_different_files() =
        contract { install ->
            val first = checkNotNull(install().mirror.copy()).name
            val second = checkNotNull(install().mirror.copy()).name

            assertThat(first).isNotEqualTo(second)
        }

    /** DoD эпика C: переустановили, скан выдал файлам новые ID — лайки и плейлисты вернулись. */
    @Test
    fun a_restore_brings_likes_and_playlists_back_to_rescanned_files() =
        contract { install ->
            val copy = old(install)
            val new = install()
            new.scan(listOf(SONG, OTHER))

            val restored = checkNotNull(new.mirror.restore(listOf(copy)))

            assertThat(restored.merged).isEqualTo(1)
            assertThat(new.liked()).containsExactly(SONG)
            assertThat(new.playlists()).containsExactly("Road", listOf(OTHER, SONG))
        }

    /** Восстановили в мастере первого запуска, до скана: всё вернётся со сканом. */
    @Test
    fun a_restore_before_the_scan_comes_back_with_the_scan() =
        contract { install ->
            val copy = old(install)
            val new = install()
            new.mirror.restore(listOf(copy))
            assertThat(new.liked()).isEmpty()

            new.scan(listOf(OTHER, SONG))

            assertThat(new.liked()).containsExactly(SONG)
            assertThat(new.playlists()).containsExactly("Road", listOf(OTHER, SONG))
        }

    /** Слияние, а не замена: лайк, поставленный до ответа «Восстановить», остаётся. */
    @Test
    fun a_restore_keeps_what_was_done_before() =
        contract { install ->
            val copy = old(install)
            val new = install()
            new.scan(listOf(SONG, OTHER))
            new.like(OTHER)

            new.mirror.restore(listOf(copy))

            assertThat(new.liked()).containsExactly(SONG, OTHER)
        }

    @Test
    fun a_restored_copy_is_no_longer_news() =
        contract { install ->
            val copy = old(install)
            val new = install()
            new.mirror.restore(listOf(copy))

            val found = checkNotNull(new.mirror.inspect(listOf(copy)))

            assertThat(found.news).isFalse()
        }

    /** Испорченный или посторонний файл в папке пропускается и считается. */
    @Test
    fun a_damaged_file_is_counted_and_skipped() =
        contract { install ->
            val copy = old(install)
            val new = install()
            new.scan(listOf(SONG, OTHER))
            val files = listOf(MirrorFile("notes.journal", "not a copy".toByteArray()), copy)

            val found = checkNotNull(new.mirror.inspect(files))
            val restored = checkNotNull(new.mirror.restore(files))

            assertThat(listOf(found.likes, found.unreadable)).containsExactly(1, 1).inOrder()
            assertThat(listOf(restored.merged, restored.unreadable)).containsExactly(1, 1).inOrder()
            assertThat(new.liked()).containsExactly(SONG)
        }

    /** Правка пользователя двигает сигнал: по нему пишется копия. */
    @Test
    fun a_user_edit_moves_the_change_signal() =
        contract { install ->
            val installation = install()
            installation.scan(listOf(SONG))
            val before = installation.mirror.changes.first()

            installation.like(SONG)

            assertThat(installation.mirror.changes.first { it != before }).isNotEqualTo(before)
        }

    /** Прошлая установка: лайк [SONG], плейлист «Road» — [OTHER], потом [SONG]. Её копия. */
    private suspend fun old(install: () -> Installation): MirrorFile {
        val old = install()
        old.scan(listOf(SONG, OTHER))
        old.like(SONG)
        old.playlist("Road", listOf(OTHER, SONG))
        return checkNotNull(old.mirror.copy())
    }

    private fun contract(block: suspend (install: () -> Installation) -> Unit) =
        runBlocking {
            val installed = mutableListOf<Installation>()
            try {
                withTimeout(TIMEOUT) { block { install().also(installed::add) } }
            } finally {
                installed.forEach(::uninstall)
            }
        }

    protected companion object {
        const val SONG = "/storage/emulated/0/Music/Кино/Кукушка.mp3"
        const val OTHER = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3"
        val TIMEOUT = 10.seconds
    }
}
