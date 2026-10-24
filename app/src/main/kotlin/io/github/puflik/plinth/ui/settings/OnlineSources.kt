package io.github.puflik.plinth.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.online.OnlineProviderIds
import io.github.puflik.plinth.online.OnlineSettings
import io.github.puflik.plinth.ui.online.providerName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Как у выключенных элементов Material: содержимое строки провайдера гаснет, пока выключен главный переключатель.
private const val DISABLED_ALPHA = 0.38f

/**
 * «Онлайн-источники» (E3, Ю3г): главный переключатель и под ним по строке на провайдера (Internet Archive, YouTube).
 * Выключено — поиск только в библиотеке, сетевые треки не играют. Выключенный провайдер не получает запросов: нет его
 * секции в поиске, его сохранённые треки не играют, пока он выключен. Строки провайдеров недоступны, пока выключен
 * главный переключатель; выбор в них при этом сохраняется.
 */
internal fun LazyListScope.onlineSources(
    enabled: Boolean,
    disabledProviders: Set<String>,
    onToggle: (Boolean) -> Unit,
    onProviderToggle: (String, Boolean) -> Unit,
) {
    item(key = "online") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_online_title)) },
            supportingContent = { Text(stringResource(R.string.settings_online_summary)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        )
    }
    items(OnlineProviderIds.ALL, key = { "online-$it" }) { provider ->
        ProviderSwitch(
            provider = provider,
            on = provider !in disabledProviders,
            available = enabled,
            onToggle = { onProviderToggle(provider, it) },
        )
    }
}

@Composable
private fun ProviderSwitch(
    provider: String,
    on: Boolean,
    available: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val tint = if (available) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val summary = providerSummary(provider)
    ListItem(
        headlineContent = { Text(providerName(provider), color = tint) },
        supportingContent = summary?.let { { Text(stringResource(it), color = tint) } },
        trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = available) },
        modifier = Modifier.toggleable(value = on, enabled = available, role = Role.Switch, onValueChange = onToggle),
    )
}

/** Пояснение под провайдером; незнакомому ID — без пояснения. */
@StringRes
private fun providerSummary(provider: String): Int? =
    when (provider) {
        OnlineProviderIds.ARCHIVE -> R.string.settings_online_archive_summary
        OnlineProviderIds.YOUTUBE -> R.string.settings_online_youtube_summary
        else -> null
    }

@HiltViewModel
class OnlineSourcesViewModel
    @Inject
    constructor(
        private val settings: OnlineSettings,
    ) : ViewModel() {
        val enabled: StateFlow<Boolean> =
            settings.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

        /** ID выключенных провайдеров (Ю3г); пусто до первого значения — включены все. */
        val disabledProviders: StateFlow<Set<String>> =
            settings.disabledProviders.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                emptySet(),
            )

        fun onToggle(enabled: Boolean) {
            viewModelScope.launch { settings.setEnabled(enabled) }
        }

        fun onProviderToggle(
            provider: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch { settings.setProviderEnabled(provider, enabled) }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
