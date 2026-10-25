package io.github.puflik.plinth.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/**
 * «О приложении» в конце настроек (Ю3г): версия и flavor, версия экстрактора YouTube, ссылка на исходный код
 * и экран «Лицензии». Значения — из [details]; строки с текстом «Версия» и «Экстрактор» не нажимаются.
 */
internal fun LazyListScope.aboutSection(
    details: AboutDetails,
    onOpenSource: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    item(key = "about-title") {
        Text(
            text = stringResource(R.string.settings_about_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
    }
    item(key = "about-version") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_version)) },
            supportingContent = { Text(details.versionLine) },
        )
    }
    item(key = "about-extractor") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_extractor)) },
            supportingContent = { Text(details.extractorLine) },
        )
    }
    item(key = "about-source") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_source)) },
            supportingContent = { Text(stringResource(R.string.settings_about_source_hint)) },
            modifier = Modifier.clickable(onClick = onOpenSource),
        )
    }
    item(key = "about-licenses") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_licenses)) },
            supportingContent = { Text(stringResource(R.string.settings_about_licenses_hint)) },
            modifier = Modifier.clickable(onClick = onOpenLicenses),
        )
    }
}
