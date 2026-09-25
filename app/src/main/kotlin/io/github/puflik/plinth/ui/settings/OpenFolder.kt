package io.github.puflik.plinth.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Системный выбор папки для библиотеки (C2.5). На Android 8 он открывался на
 * «Недавних», а память телефона пряталась за «⋮ → Показать внутреннюю память»
 * (Н4 прогона на старых Android). Флаг [SHOW_ADVANCED] системного выбора
 * показывает её сразу; новые версии Android показывают её и без него.
 */
class OpenFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(
        context: Context,
        input: Uri?,
    ): Intent = super.createIntent(context, input).putExtra(SHOW_ADVANCED, true)

    private companion object {
        /** Недокументирован, но его понимает DocumentsUI всех версий, что поддерживает Plinth. */
        const val SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED"
    }
}
