package io.github.puflik.plinth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.puflik.plinth.artwork.ArtworkLoader
import io.github.puflik.plinth.audio.media3.PlaybackServiceConnection
import io.github.puflik.plinth.ui.common.LocalArtworkLoader
import io.github.puflik.plinth.ui.navigation.BottomNavigationBar
import io.github.puflik.plinth.ui.navigation.Destination
import io.github.puflik.plinth.ui.navigation.PlinthNavHost
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

@Composable
private fun PlinthApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Плеер и альбом открываются поверх вкладок — без нижней навигации.
    val onTab = currentRoute == null || Destination.tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (onTab) {
                BottomNavigationBar(
                    currentRoute = currentRoute,
                    onDestinationSelected = { destination ->
                        navController.navigateToTab(destination)
                    },
                )
            }
        },
    ) { innerPadding ->
        PlinthNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Переход по вкладке: одна запись в стеке на вкладку, состояние вкладки
 * сохраняется при возврате.
 */
private fun androidx.navigation.NavHostController.navigateToTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
