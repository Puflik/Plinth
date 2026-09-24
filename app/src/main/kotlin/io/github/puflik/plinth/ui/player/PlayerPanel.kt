package io.github.puflik.plinth.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/**
 * Что показано на месте обложки (E4, слой D2 по 12.10): сама обложка или
 * одна из панелей. Переключаются точками-табами под этим местом (12.11,
 * умолчание B: свайп по обложке остаётся за сменой трека).
 */
enum class PlayerPanel(
    @param:StringRes val label: Int,
) {
    COVER(R.string.player_panel_cover),
    LYRICS(R.string.player_panel_lyrics),
    QUEUE(R.string.player_panel_queue),
    SIMILAR(R.string.player_panel_similar),
    INFO(R.string.player_panel_info),
}

/** Точки-табы: выбранная — цветом акцента; TalkBack читает их как вкладки с названиями. */
@Composable
fun PanelDots(
    selected: PlayerPanel,
    onSelect: (PlayerPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.selectableGroup(), horizontalArrangement = Arrangement.Center) {
        PlayerPanel.entries.forEach { panel ->
            val label = stringResource(panel.label)
            Box(
                modifier =
                    Modifier
                        .size(DOT_TARGET)
                        .selectable(selected = panel == selected, role = Role.Tab) { onSelect(panel) }
                        .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(DOT_SIZE)
                            .clip(CircleShape)
                            .background(
                                if (panel == selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                )
            }
        }
    }
}

/** Панель, которой ещё нет: честно говорит, что она появится позже. */
@Composable
fun PanelPlaceholder(
    @StringRes text: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val DOT_SIZE = 8.dp

/** Точка маленькая, а попасть в неё пальцем должно быть легко. */
private val DOT_TARGET = 40.dp
