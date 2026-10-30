package io.github.puflik.plinth.ui.search

import android.widget.Toast
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ffi.OnlineKind
import io.github.puflik.plinth.ffi.OnlineProblem
import io.github.puflik.plinth.ffi.OnlineResult
import io.github.puflik.plinth.ffi.OnlineSection
import io.github.puflik.plinth.ffi.PlaylistId
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.TrackAction
import io.github.puflik.plinth.ui.library.components.TrackLabel
import io.github.puflik.plinth.ui.library.components.TrackRow
import io.github.puflik.plinth.ui.library.components.rememberTrackActionFeedback
import io.github.puflik.plinth.ui.library.playlists.PlaylistPicker
import io.github.puflik.plinth.ui.online.FirmwareNetworkNote
import io.github.puflik.plinth.ui.online.OnlineAlbumHeader
import io.github.puflik.plinth.ui.online.providerName
import io.github.puflik.plinth.ui.online.rememberFirmwareNetworkBlock

/**
 * Поиск (C4.4, E3): название, исполнитель, альбом. Сначала «В библиотеке» —
 * сразу, ниже — секция провайдера, когда ответит сеть (ответ автора E3).
 * Касание трека — звук и плеер, касание альбома провайдера — его экран.
 * Песня провайдера (`OnlineKind.TRACK`) — строка трека с тем же меню (Ю5а): касание играет её,
 * плеер открывается, когда песня получена. Онлайн-источники выключены — секций нет,
 * экран такой же, как до E3.
 */
@Composable
fun SearchScreen(
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (OnlineAlbumHeader) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // Текст и курсор поля живут здесь, а в ViewModel текст только уходит (Н3):
    // круг через её Flow отставал от быстрого ввода и стирал буквы.
    val query = rememberTextFieldState()
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect(viewModel::onQuery) }
    val feedback = rememberTrackActionFeedback(onOpenPlayer)
    val onTrack = { track: LibraryTrack, action: TrackAction ->
        viewModel.onTrack(track, action)
        feedback(action)
    }
    // Песня попадает в каталог по ответу сети: плеер открывается и «добавлено» говорится, когда действие сделано.
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentFeedback by rememberUpdatedState(feedback)
    LaunchedEffect(viewModel) {
        viewModel.songEvents.collect { event ->
            when (event) {
                is SongEvent.Done -> currentFeedback(event.action)
                is SongEvent.Failed ->
                    Toast
                        .makeText(
                            context,
                            resources.getString(problemRes(event.problem), providerName(event.provider)),
                            Toast.LENGTH_SHORT,
                        ).show()
            }
        }
    }
    val songs =
        SongHandlers(
            states = state.songs,
            onAction = viewModel::onSong,
            onPlaylist = viewModel::onSongToPlaylist,
            onNewPlaylist = viewModel::onSongToNewPlaylist,
        )
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            state = query,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        when {
            state.online != OnlineResults.None ->
                LazyColumn {
                    library(state.results, onTrack)
                    online(state.online, songs, onOpenAlbum, viewModel::onRetryOnline)
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
    songs: SongHandlers,
    onOpenAlbum: (OnlineAlbumHeader) -> Unit,
    onRetry: () -> Unit,
) {
    when (online) {
        OnlineResults.None -> Unit
        OnlineResults.Searching -> item(key = SEARCHING_KEY) { Searching() }
        is OnlineResults.Found -> online.sections.forEach { section(it, songs, onOpenAlbum, onRetry) }
    }
}

private fun LazyListScope.section(
    section: OnlineSection,
    songs: SongHandlers,
    onOpenAlbum: (OnlineAlbumHeader) -> Unit,
    onRetry: () -> Unit,
) {
    val name = providerName(section.provider)
    item(key = "section-${section.provider}") { SectionTitle(name) }
    val problem = section.problem
    when {
        problem != null -> item(key = "problem-${section.provider}") { ProblemNote(problem, name, onRetry) }
        section.results.isEmpty() ->
            item(key = "empty-${section.provider}") { Note(stringResource(R.string.search_nothing_found)) }
        else ->
            items(section.results, key = { songKey(section.provider, it) }) { result ->
                when (result.kind) {
                    OnlineKind.TRACK -> SongItem(section.provider, result, songs)
                    OnlineKind.ALBUM -> ResultRow(result) { onOpenAlbum(header(section.provider, result)) }
                }
            }
    }
}

/** Собрание провайдера (`OnlineKind.ALBUM`) — строка альбома: название, «исполнитель · год». */
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

/** Что экран делает с песнями секций (Ю5а): что о них известно и три действия — из меню, в плейлист, в новый. */
private class SongHandlers(
    val states: Map<String, SongState>,
    val onAction: (String, OnlineResult, TrackAction) -> Unit,
    val onPlaylist: (String, OnlineResult, PlaylistId) -> Unit,
    val onNewPlaylist: (String, OnlineResult, String) -> Unit,
)

/**
 * Песня провайдера — строка трека (Ю5а): облако с именем провайдера, длительность, ⋮ с меню; пока песню получают
 * у провайдера, под строкой идёт полоса хода.
 */
@Composable
private fun SongItem(
    provider: String,
    result: OnlineResult,
    songs: SongHandlers,
) {
    val state = songs.states[songKey(provider, result)] ?: SongState()
    Column {
        TrackRow(
            label =
                TrackLabel(
                    title = result.title,
                    artist = result.artist,
                    album = null,
                    duration = result.duration,
                    liked = state.liked,
                    online = true,
                    provider = provider,
                ),
            onAction = { songs.onAction(provider, result, it) },
        ) { dismiss ->
            PlaylistPicker(
                onAdd = { songs.onPlaylist(provider, result, it.id) },
                onAddToNew = { songs.onNewPlaylist(provider, result, it) },
                onDismiss = dismiss,
            )
        }
        if (state.busy) {
            val progress = stringResource(R.string.online_song_getting)
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = progress },
            )
        }
    }
}

/**
 * Беда секции и «Повторить» под ней — для любой беды (R1 №15). «Нет сети» при живой сети на телефоне
 * Transsion — где прошивка её закрыла (Н7) — с подсказкой про прошивку; она только для `NO_NETWORK`.
 */
@Composable
private fun ProblemNote(
    problem: OnlineProblem,
    provider: String,
    onRetry: () -> Unit,
) {
    Column {
        if (problem == OnlineProblem.NO_NETWORK && rememberFirmwareNetworkBlock()) {
            FirmwareNetworkNote(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            Note(problemText(problem, provider))
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.online_retry))
        }
    }
}

@Composable
private fun problemText(
    problem: OnlineProblem,
    provider: String,
): String = stringResource(problemRes(problem), provider)

/** Слова беды: у «нет сети» имени провайдера нет, остальные его называют — лишний аргумент строке не мешает. */
private fun problemRes(problem: OnlineProblem): Int =
    when (problem) {
        OnlineProblem.NO_NETWORK -> R.string.online_no_network
        OnlineProblem.PROVIDER_DOWN -> R.string.online_provider_down
        OnlineProblem.RATE_LIMITED -> R.string.online_rate_limited
        OnlineProblem.BROKEN -> R.string.online_broken
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
