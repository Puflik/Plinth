package io.github.puflik.plinth.ui.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.diagnostics.vendor.Vendor
import io.github.puflik.plinth.diagnostics.vendor.VendorIntents
import io.github.puflik.plinth.ui.common.rememberSafeUriHandler

/** Один раз после убитой игры — «Почему музыка останавливается» (G2.4). */
@Composable
fun VendorGuidePrompt(viewModel: VendorGuideViewModel = hiltViewModel()) {
    val show by viewModel.showGuide.collectAsState()
    if (show) VendorGuideDialog(viewModel.vendor, afterKill = true, onClose = viewModel::onClose)
}

/**
 * «Почему музыка останавливается» (G2.2): что делает прошивка и куда
 * нажать — экран автозапуска или батареи этого производителя, иначе общий
 * экран оптимизации батареи; подробности — на dontkillmyapp.com.
 *
 * @param afterKill показан после убитой игры — тогда начинается с того, что случилось.
 */
@Composable
fun VendorGuideDialog(
    vendor: Vendor,
    afterKill: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = rememberSafeUriHandler()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.vendor_guide_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (afterKill) Text(stringResource(R.string.vendor_guide_killed))
                Text(
                    vendor.brand?.let { stringResource(R.string.vendor_guide_text_named, it) }
                        ?: stringResource(R.string.vendor_guide_text_other),
                )
                TextButton(onClick = { uriHandler.openUri(VendorIntents.guideUrl(vendor)) }) {
                    Text(stringResource(R.string.vendor_guide_learn_more))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { openBackgroundSettings(context, vendor) }) {
                Text(stringResource(R.string.vendor_guide_open_settings))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.vendor_guide_close)) } },
    )
}

/** Пункт настроек, открывающий то же объяснение в любой момент. */
internal fun LazyListScope.vendorGuideItem(onOpen: () -> Unit) {
    item(key = "vendor-guide") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_vendor_guide)) },
            supportingContent = { Text(stringResource(R.string.settings_vendor_guide_hint)) },
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

/**
 * Экран производителя, если он есть на этом телефоне; иначе список
 * оптимизации батареи; иначе страница приложения. Экраны прошивок бывают
 * закрыты для чужих приложений — тогда следующий.
 */
private fun openBackgroundSettings(
    context: Context,
    vendor: Vendor,
) {
    val candidates =
        VendorIntents.targets(vendor).map { Intent().setComponent(ComponentName(it.packageName, it.className)) } +
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) +
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    candidates.firstOrNull { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (expected: ActivityNotFoundException) {
            false
        } catch (expected: SecurityException) {
            false
        }
    }
}
