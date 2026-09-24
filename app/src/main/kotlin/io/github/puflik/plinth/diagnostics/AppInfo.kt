package io.github.puflik.plinth.diagnostics

/**
 * Что за сборка и на чём работает (G1): первая строка лога, шапка отчёта о
 * сбое и шаблона issue. Без этого разбирать присланный лог бессмысленно;
 * ничего личного здесь нет.
 */
data class AppInfo(
    val version: String,
    val flavor: String,
    val androidRelease: String,
    val api: Int,
    val device: String,
) {
    val line: String get() = "Plinth $version ($flavor), Android $androidRelease (API $api), $device"
}
