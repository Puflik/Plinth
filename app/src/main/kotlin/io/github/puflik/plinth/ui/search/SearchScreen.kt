package io.github.puflik.plinth.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.components.TrackRow

/** Поиск по фонотеке (C4.4): название, исполнитель, альбом. Касание — звук и плеер. */
@Composable
fun SearchScreen(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        when {
            state.results.isNotEmpty() ->
                LazyColumn {
                    items(state.results, key = LibraryTrack::id) { track ->
                        TrackRow(
                            track = track,
                            onClick = {
                                viewModel.play(track)
                                onOpenPlayer()
                            },
                        )
                    }
                }
            state.searched -> Message(stringResource(R.string.search_nothing_found))
            else -> Message(stringResource(R.string.search_prompt))
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
