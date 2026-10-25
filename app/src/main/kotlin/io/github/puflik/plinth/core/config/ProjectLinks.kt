package io.github.puflik.plinth.core.config

/** Адреса проекта, на которые приложение ссылается само. */
object ProjectLinks {
    /** Исходный код Plinth (AGPL-3.0): ссылка в «О приложении» (Ю3г). */
    const val SOURCE: String = "https://github.com/Puflik/Plinth"

    /** Релизы с заметками — там видно, что вышло и что на подходе (12.5). */
    const val RELEASES: String = "https://github.com/Puflik/Plinth/releases"

    /** Новое сообщение об ошибке; текст-заготовку добавляет `IssueTemplate`. */
    const val NEW_ISSUE: String = "https://github.com/Puflik/Plinth/issues/new"
}
