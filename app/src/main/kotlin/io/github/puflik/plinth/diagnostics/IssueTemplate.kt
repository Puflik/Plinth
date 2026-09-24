package io.github.puflik.plinth.diagnostics

import io.github.puflik.plinth.core.config.ProjectLinks
import java.net.URLEncoder

/**
 * Новое сообщение об ошибке на GitHub (G1.3) — открывается, только если
 * человек сам захотел оставить обратную связь. В адресе — заготовка текста
 * и сборка с устройством; лога в нём нет: сохранённый отчёт человек
 * прикладывает сам, увидев его.
 */
object IssueTemplate {
    fun url(info: AppInfo): String = "${ProjectLinks.NEW_ISSUE}?body=${encode(body(info))}"

    private fun body(info: AppInfo): String =
        """
        |**What happened**
        |
        |
        |**Steps to reproduce**
        |
        |
        |**Environment**
        |- Plinth: ${info.version} (${info.flavor})
        |- Android: ${info.androidRelease} (API ${info.api})
        |- Device: ${info.device}
        |
        |If you saved a report in Settings → Diagnostics, attach it here.
        """.trimMargin()

    // Пробел — %20, а не «+»: так адрес одинаково читают и браузер, и GitHub.
    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20")
}
