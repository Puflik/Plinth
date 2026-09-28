package io.github.puflik.plinth.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.online.OnlineSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * «Онлайн-источники» (E3): переключатель и что он значит — запросы уходят на
 * archive.org. Выключено — поиск только в библиотеке, сетевые треки не играют.
 */
internal fun LazyListScope.onlineSources(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    item(key = "online") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_online_title)) },
            supportingContent = { Text(stringResource(R.string.settings_online_summary)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        )
    }
}

@HiltViewModel
class OnlineSourcesViewModel
    @Inject
    constructor(
        private val settings: OnlineSettings,
    ) : ViewModel() {
        val enabled: StateFlow<Boolean> =
            settings.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

        fun onToggle(enabled: Boolean) {
            viewModelScope.launch { settings.setEnabled(enabled) }
        }

        private companion object {
            // Переживает поворот экрана, не держит подписку в фоне.
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
