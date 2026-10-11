package io.github.puflik.plinth.library.scan

import android.os.Environment
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Что скан делает с томом по его состоянию в системе (Р1.15, находка 5):
 * смонтированный обходит; карту, которую система проверяет после загрузки или
 * вставки, не трогает — её треки не пропали; всё прочее — вынутая карта, её
 * треки скан отмечает недоступными, как всегда.
 */
class VolumeReadinessTest {
    @Test
    fun `a mounted volume is walked`() {
        assertWithMessage("mounted")
            .that(volumeReadiness(Environment.MEDIA_MOUNTED))
            .isEqualTo(VolumeReadiness.READY)
        assertWithMessage("mounted read-only")
            .that(volumeReadiness(Environment.MEDIA_MOUNTED_READ_ONLY))
            .isEqualTo(VolumeReadiness.READY)
    }

    @Test
    fun `a card that the system is checking is left alone`() {
        assertWithMessage("checking")
            .that(volumeReadiness(Environment.MEDIA_CHECKING))
            .isEqualTo(VolumeReadiness.PENDING)
    }

    /**
     * Не проверка, а все остальные состояния — вынутая карта. Вручную отключённая
     * (`unmounted`) тоже: иначе путь «трек не играет → пересканировать» не отметил
     * бы её треки недоступными никогда. Строки состояний — часть API Android,
     * поэтому литералы: тест не зависит от скрытых констант.
     */
    @Test
    fun `every other state is an ejected card`() {
        val others =
            listOf(
                "unmounted",
                "ejecting",
                "removed",
                "bad_removal",
                "unmountable",
                "nofs",
                "shared",
                "unknown",
                "",
                "a state of a newer Android",
            )

        for (state in others) {
            assertWithMessage(state.ifEmpty { "(empty)" })
                .that(volumeReadiness(state))
                .isEqualTo(VolumeReadiness.ABSENT)
        }
    }
}
