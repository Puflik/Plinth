package io.github.puflik.plinth.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Раскладка плеера под шапкой: квадратная рамка обложки или панели
 * ([frame] получает её размер модификатором), точки-табы под ней ([dots]) и
 * подробности — название, перемотка, кнопки ([details]).
 *
 * Подробности видны всегда (ревью №3): в портрете они меряются первыми, а
 * рамка берёт оставшуюся высоту — не больше 0,8 ширины; в альбомной
 * ориентации рамка во всю высоту слева, подробности справа. Если подробности
 * не помещаются и так (крупный системный шрифт), они прокручиваются.
 */
@Composable
internal fun PlayerFrame(
    frame: @Composable (Modifier) -> Unit,
    dots: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val detailsColumn = @Composable { outer: Modifier ->
            // Прокрутка — только когда есть что прокручивать: иначе она забирала бы свайпы плеера вверх и вниз.
            val scroll = rememberScrollState()
            Column(
                modifier = outer.verticalScroll(scroll, enabled = scroll.canScrollForward || scroll.canScrollBackward),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = details,
            )
        }
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    frame(Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true))
                    dots()
                }
                detailsColumn(Modifier.weight(1f))
            }
        } else {
            val widest = (maxWidth - 48.dp) * ARTWORK_WIDTH
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    frame(
                        Modifier
                            .weight(1f, fill = false)
                            .widthIn(max = widest)
                            .aspectRatio(1f, matchHeightConstraintsFirst = true),
                    )
                    dots()
                }
                detailsColumn(Modifier.fillMaxWidth())
            }
        }
    }
}

private const val ARTWORK_WIDTH = 0.8f
