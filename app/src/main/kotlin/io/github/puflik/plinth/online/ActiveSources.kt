package io.github.puflik.plinth.online

import io.github.puflik.plinth.ffi.OnlineProvider

/** Что подключать ядру: Internet Archive (он в ядре) и провайдеры приложения — только включённые. */
internal class ActiveSources(
    val archive: Boolean,
    val providers: List<OnlineProvider>,
) {
    /** Подключать нечего: выключены все. */
    val isEmpty: Boolean get() = !archive && providers.isEmpty()
}

/**
 * [all] — провайдеры приложения (без Internet Archive: он в ядре), [disabled] — ID выключенных.
 * Порядок [all] сохраняется; незнакомые ID в [disabled] ничего не значат.
 */
internal fun activeSources(
    all: List<OnlineProvider>,
    disabled: Set<String>,
): ActiveSources =
    ActiveSources(archive = OnlineProviderIds.ARCHIVE !in disabled, providers = all.filter { it.id !in disabled })
