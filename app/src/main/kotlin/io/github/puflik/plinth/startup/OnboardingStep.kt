package io.github.puflik.plinth.startup

/**
 * Шаги мастера первого запуска (F1, план 12.4) в порядке показа.
 *
 * В v0.1 их два — настраивать больше нечего; к v1.0 мастер дорастёт до семи.
 * Имя шага хранится строкой, поэтому переименование — это миграция.
 *
 * @property skippable у шага есть своя кнопка «Пропустить». Обязательный шаг
 *   пропускается только вместе со всем мастером.
 */
enum class OnboardingStep(
    val skippable: Boolean,
) {
    /** Разрешение на музыку: без него библиотеки нет, поэтому шаг обязательный. */
    PERMISSION(skippable = false),

    /** Какие папки сканировать. */
    FOLDERS(skippable = true),
    ;

    /** Следующий шаг; после последнего — `null`. */
    val next: OnboardingStep? get() = entries.getOrNull(ordinal + 1)
}
