package io.github.puflik.plinth.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback
import io.github.puflik.plinth.ui.online.OnlineAlbumHeader
import io.github.puflik.plinth.ui.online.providerName

/**
 * Поиск (C4.4, E3): название, исполнитель, альбом. Сначала «В библиотеке» —
 * сразу, ниже — секция провайдера, когда ответит сеть (ответ автора E3).
 * Касание трека — звук и плеер, касание альбома провайдера — его экран.
 * Онлайн-источники выключены — секций нет, экран такой же, как до E3.
 */
@Composable
fun SearchScreen(
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (OnlineAlbumHeader) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    val onTrack = { track: LibraryTrack, action: TrackAction ->
        viewModel.onTrack(track, action)
        feedback(action)
    }
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
            state.online != OnlineResults.None ->
                LazyColumn {
                    library(state.results, onTrack)
                    online(state.online, onOpenAlbum)
                }
            state.results.isNotEmpty() -> LazyColumn { tracks(state.results, onTrack) }
            state.searched -> Message(stringResource(R.string.search_nothing_found))
            else -> Message(stringResource(R.string.search_prompt))
        }
    }
}

/** «В библиотеке»: треки или «ничего» — секция стоит, даже пустая, над секцией сети. */
private fun LazyListScope.library(
    results: List<LibraryTrack>,
    onTrack: (LibraryTrack, TrackAction) -> Unit,
) {
    item(key = LIBRARY_KEY) { SectionTitle(stringResource(R.string.search_section_library)) }
    if (results.isEmpty()) {
        item(key = LIBRARY_EMPTY_KEY) { Note(stringResource(R.string.search_library_nothing)) }
    } else {
        tracks(results, onTrack)
    }
}

private fun LazyListScope.tracks(
    results: List<LibraryTrack>,
    onTrack: (LibraryTrack, TrackAction) -> Unit,
) {
    items(results, key = { it.id.value }) { track -> TrackRow(track = track, onAction = { onTrack(track, it) }) }
}

private fun LazyListScope.online(
    online: OnlineResults,
    onOpenAlbum: (OnlineAlbumHeader) -> Unit,
) {
    when (online) {
        OnlineResults.None -> Unit
        OnlineResults.Searching -> item(key = SEARCHING_KEY) { Searching() }
        is OnlineResults.Found -> online.sections.forEach { section(it, onOpenAlbum) }
    }
}

private fun LazyListScope.section(
    section: OnlineSection,
    onOpenAlbum: (OnlineAlbumHeader) -> Unit,
) {
    val name = providerName(section.provider)
    item(key = "section-${section.provider}") { SectionTitle(name) }
    val problem = section.problem
    when {
        problem != null -> item(key = "problem-${section.provider}") { Note(problemText(problem, name)) }
        section.results.isEmpty() ->
            item(key = "empty-${section.provider}") { Note(stringResource(R.string.search_nothing_found)) }
        else ->
            items(section.results, key = { "${section.provider}/${it.external}" }) { result ->
                ResultRow(result) { onOpenAlbum(header(section.provider, result)) }
            }
    }
}

/** Собрание провайдера — строка альбома: название, «исполнитель · год». */
@Composable
private fun ResultRow(
    result: OnlineResult,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val artist = result.artist ?: stringResource(R.string.library_unknown_artist)
            Text(
                listOfNotNull(artist, result.year?.toString()).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_album), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun problemText(
    problem: OnlineProblem,
    provider: String,
): String =
    when (problem) {
        OnlineProblem.NO_NETWORK -> stringResource(R.string.online_no_network)
        OnlineProblem.PROVIDER_DOWN -> stringResource(R.string.online_provider_down, provider)
        OnlineProblem.BROKEN -> stringResource(R.string.online_broken, provider)
    }

private fun header(
    provider: String,
    result: OnlineResult,
) = OnlineAlbumHeader(provider, result.external, result.title, result.artist, result.year)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Searching() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.online_searching), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

private const val LIBRARY_KEY = "library"
private const val LIBRARY_EMPTY_KEY = "library-empty"
private const val SEARCHING_KEY = "searching"
