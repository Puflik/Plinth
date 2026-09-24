package io.github.puflik.plinth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.puflik.plinth.artwork.ArtworkLoader
import io.github.puflik.plinth.audio.media3.PlaybackServiceConnection
import io.github.puflik.plinth.startup.StartDecision
import io.github.puflik.plinth.startup.StartDestination
import io.github.puflik.plinth.ui.common.LocalArtworkLoader
import io.github.puflik.plinth.ui.navigation.BottomNavigationBar
import io.github.puflik.plinth.ui.navigation.Destination
import io.github.puflik.plinth.ui.navigation.PlinthNavHost
import io.github.puflik.plinth.ui.onboarding.OnboardingScreen
import io.github.puflik.plinth.ui.onboarding.OnboardingUiState
import io.github.puflik.plinth.ui.onboarding.OnboardingViewModel
import io.github.puflik.plinth.ui.player.MiniPlayer
import io.github.puflik.plinth.ui.player.MiniPlayerActions
import io.github.puflik.plinth.ui.player.PlayerViewModel
import io.github.puflik.plinth.ui.settings.CrashReportOffer
import io.github.puflik.plinth.ui.start.StartViewModel
import io.github.puflik.plinth.ui.start.explanationText
import io.github.puflik.plinth.ui.theme.PlinthTheme
import javax.inject.Inject

/**
 * Единственная Activity приложения (A2.1): хост Compose и ничего больше.
 * Экранам отсюда же достаётся загрузчик обложек — как `LocalArtworkLoader`.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var artworkLoader: ArtworkLoader<ImageBitmap>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(PlaybackServiceConnection(this))
        setContent {
            PlinthTheme {
                CompositionLocalProvider(LocalArtworkLoader provides artworkLoader) {
                    PlinthApp()
                }
            }
        }
    }
}

/** Мастер первого запуска стоит перед приложением, пока его не пройдут или не пропустят (F1). */
@Composable
private fun PlinthApp() {
    val onboarding: OnboardingViewModel = hiltViewModel()
    when (val state = onboarding.uiState.collectAsState().value) {
        // Первый ли это запуск, ещё не прочитано — доли секунды виден фон темы.
        OnboardingUiState.Loading -> Unit
        is OnboardingUiState.Step -> OnboardingScreen(step = state.step, viewModel = onboarding)
        OnboardingUiState.Finished -> StartedApp()
    }
}

/**
 * Куда открыться (F3): решение принимается за доли секунды — настройка,
 * библиотека и время последней игры, — до него виден фон темы.
 */
@Composable
private fun StartedApp() {
    val start: StartViewModel = hiltViewModel()
    val decision = start.decision.collectAsState().value
    if (decision != null) PlinthMain(decision = decision, takeDecision = start::take)
}

/**
 * Приложение: вкладки, экраны поверх них и мини-плеер. Стартовое решение
 * применяется один раз: плеер открывается поверх библиотеки, и если его
 * выбрало «Авто», снизу объяснение со ссылкой на настройку (12.2, 12.6).
 */
@Composable
private fun PlinthMain(
    decision: StartDecision,
    takeDecision: () -> StartDecision?,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val explanation = explanationText(decision.explanation)
    val toSettings = stringResource(R.string.start_explain_settings)
    LaunchedEffect(Unit) {
        val taken = takeDecision() ?: return@LaunchedEffect
        if (taken.destination != StartDestination.PLAYER) return@LaunchedEffect
        navController.openPlayer()
        if (taken.explanation.automatic) {
            val result = snackbar.showSnackbar(explanation, actionLabel = toSettings, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) navController.navigateToTab(Destination.Settings)
        }
    }

    // Плеер и альбом открываются поверх вкладок — без нижней навигации.
    val onTab = currentRoute == null || Destination.tabs.any { it.route == currentRoute }
    // Мини-плеер — на всех экранах, кроме самого плеера, когда есть что играть.
    val player: PlayerViewModel = hiltViewModel()
    val playerState by player.uiState.collectAsState()
    val showMiniPlayer = playerState.hasTrack && currentRoute != Destination.Player.route

    // Прошлый запуск закончился сбоем — предложить сохранить отчёт (G1.3).
    CrashReportOffer(snackbar)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        state = playerState,
                        actions =
                            MiniPlayerActions(
                                onPlayPause = player::onPlayPause,
                                onNext = player::onNext,
                                onPrevious = player::onPrevious,
                                onExpand = navController::openPlayer,
                            ),
                        // Без нижней навигации отступ от системной панели — его забота.
                        modifier = if (onTab) Modifier else Modifier.navigationBarsPadding(),
                    )
                }
                if (onTab) {
                    BottomNavigationBar(
                        currentRoute = currentRoute,
                        onDestinationSelected = { destination ->
                            navController.navigateToTab(destination)
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        PlinthNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Плеер поверх текущего экрана; уже открытый второй раз не открывается. */
private fun NavHostController.openPlayer() {
    navigate(Destination.Player.route) { launchSingleTop = true }
}

/**
 * Переход по вкладке: одна запись в стеке на вкладку, состояние вкладки
 * сохраняется при возврате.
 */
private fun NavHostController.navigateToTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
