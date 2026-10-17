package io.github.puflik.plinth.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.puflik.plinth.SourceTree
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.generated.CoreException
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Ревью v0.2, №18 (Р1.11в): диск полон, лайк или плейлист не записались — человек должен узнать
 * об этом каждый раз (не раз за процесс), а не только если до того ничего не говорили про ядро.
 */
class R111cNotSavedNoticeTest {
    private var now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now(): Instant = this@R111cNotSavedNoticeTest.now
        }
    private val presenter = ErrorPresenter(clock)
    private val notSaved = AppError.CoreFailed(CoreProblem.STORAGE, saving = true)

    @Test
    fun `a storage failure of a save is told as not saved`() {
        assertThat(presenter.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)
    }

    /** Повторные касания сердца на полном диске не копят очередь строк, но и не молчат вечно. */
    @Test
    fun `not saved is told again after a pause and not on every failed tap`() {
        assertThat(presenter.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)

        now += 1.seconds
        assertThat(presenter.present(notSaved)).isNull()
        now += 8.seconds
        assertThat(presenter.present(notSaved)).isNull()
        now += 1.seconds
        assertThat(presenter.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)
        now += 5.seconds
        assertThat(presenter.present(notSaved)).isNull()
    }

    @Test
    fun `a clock that went back does not mute the notice`() {
        assertThat(presenter.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)

        now -= 1.hours

        assertThat(presenter.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)
    }

    /** Находка: один флаг на «что-то пошло не так» съедал и «не сохранено», и наоборот. */
    @Test
    fun `not saved and the core failure do not use each other up`() {
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.INTERNAL))).isEqualTo(ErrorNotice.CoreFailed)
        assertThat(presenter.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)

        val other = ErrorPresenter(clock)
        assertThat(other.present(notSaved)).isEqualTo(ErrorNotice.NotSaved)
        assertThat(other.present(AppError.CoreFailed(CoreProblem.INTERNAL))).isEqualTo(ErrorNotice.CoreFailed)
        assertThat(other.present(AppError.CoreFailed(CoreProblem.INTERNAL))).isNull()
    }

    /** Охранный: отказ хранилища не при записи — прежнее «что-то пошло не так» раз за запуск. */
    @Test
    fun `a storage failure that was not a save stays the one-per-launch core failure`() {
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.STORAGE))).isEqualTo(ErrorNotice.CoreFailed)
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.STORAGE))).isNull()
    }

    /** Охранный: паника при записи — ошибка, её нужно отдать разработчику, а не «нет места». */
    @Test
    fun `a panic while saving is the core failure and not not-saved`() {
        val panic = AppError.CoreFailed(CoreProblem.INTERNAL, saving = true)

        assertThat(presenter.present(panic)).isEqualTo(ErrorNotice.CoreFailed)
        assertThat(presenter.present(panic)).isNull()
    }

    /** Охранный: запись, которая не вышла по другой причине (пропал плейлист), не про место. */
    @Test
    fun `a save that failed for another reason stays quiet`() {
        for (problem in listOf(CoreProblem.NETWORK, CoreProblem.PARSE, CoreProblem.UNAVAILABLE)) {
            assertWithMessage(problem.name)
                .that(presenter.present(AppError.CoreFailed(problem, saving = true)))
                .isNull()
        }
    }

    @Test
    fun `the failure of the core says whether it was a save`() {
        val exception = CoreException.Storage("storage: disk full")

        assertThat(CoreFailure.of(exception, saving = true).error).isEqualTo(notSaved)
        assertThat(CoreFailure.of(exception).error).isEqualTo(AppError.CoreFailed(CoreProblem.STORAGE))
        assertThat(CoreFailure.of(exception).error.saving).isFalse()
    }

    /** Новая строка без перевода роняет сборку (`RussianTranslationTest`); а без самой строки — нечего показать. */
    @Test
    fun `the notice has words in both languages`() {
        for (file in listOf("values/strings.xml", "values-ru/strings.xml")) {
            assertWithMessage(file)
                .that(SourceTree.resourceFile(file).readText())
                .contains("name=\"error_not_saved\"")
        }
    }
}
