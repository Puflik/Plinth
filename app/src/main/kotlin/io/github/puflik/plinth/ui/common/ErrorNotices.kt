package io.github.puflik.plinth.ui.common

import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.puflik.plinth.R
import io.github.puflik.plinth.core.ErrorNotice
import io.github.puflik.plinth.core.FailedTrack
import io.github.puflik.plinth.core.TrackProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Сообщения об ошибках — строкой внизу, на любом экране. Приложение в фоне —
 * сказать некому: ошибка остаётся только в логе. Об остановке на открытом
 * плеере строка молчит — плеер сам пишет ошибку под названием.
 */
@Composable
fun ErrorNotices(
    snackbar: SnackbarHostState,
    playerOpen: Boolean,
    viewModel: ErrorNoticesViewModel = hiltViewModel(),
) {
    val resources = LocalContext.current.resources
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onPlayer by rememberUpdatedState(playerOpen)
    LaunchedEffect(viewModel, lifecycle) {
        showWhileStarted(lifecycle, viewModel.notices) { notice ->
            if (notice is ErrorNotice.Stopped && onPlayer) return@showWhileStarted
            // Строки показываются по очереди; сбор не ждёт, пока закроется прошлая.
            launch { snackbar.showSnackbar(resources.text(notice)) }
        }
    }
}

/**
 * Отдаёт [notices] в [show], только пока экран виден (ревью №16): свёрнутое
 * приложение сообщения не копит, а начатые строки снимаются вместе со сбором.
 */
internal suspend fun showWhileStarted(
    lifecycle: Lifecycle,
    notices: Flow<ErrorNotice>,
    show: CoroutineScope.(ErrorNotice) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { notices.collect { show(it) } }
}

private fun Resources.text(notice: ErrorNotice): String =
    when (notice) {
        is ErrorNotice.Skipped ->
            if (notice.count == 1) {
                getString(R.string.error_skipped_one, name(notice.first), problem(notice.first))
            } else {
                getQuantityString(R.plurals.error_skipped_many, notice.count, notice.count)
            }
        is ErrorNotice.Stopped ->
            if (notice.count == 1) {
                getString(R.string.error_stopped_one, name(notice.track), problem(notice.track))
            } else {
                getQuantityString(R.plurals.error_stopped_many, notice.count, notice.count)
            }
    }

private fun Resources.name(track: FailedTrack): String = track.title ?: getString(R.string.error_untitled)

private fun Resources.problem(track: FailedTrack): String =
    getString(
        when (track.problem) {
            TrackProblem.UNAVAILABLE -> R.string.error_problem_unavailable
            TrackProblem.UNPLAYABLE -> R.string.error_problem_unplayable
            TrackProblem.NETWORK -> R.string.error_problem_network
            TrackProblem.UNKNOWN -> R.string.error_problem_unknown
        },
    )
