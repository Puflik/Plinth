package io.github.puflik.plinth.ui.library.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.puflik.plinth.R

/**
 * Знак сетевого трека (E3, ответ автора): облако у длительности — в
 * строках списков и очереди. TalkBack читает, откуда трек.
 */
@Composable
fun OnlineMark(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_cloud),
        contentDescription = stringResource(R.string.track_online),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(16.dp),
    )
}
