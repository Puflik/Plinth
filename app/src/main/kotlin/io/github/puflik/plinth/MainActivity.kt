package io.github.puflik.plinth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.puflik.plinth.ui.navigation.BottomNavigationBar
import io.github.puflik.plinth.ui.navigation.Destination
import io.github.puflik.plinth.ui.navigation.PlinthNavHost
import io.github.puflik.plinth.ui.theme.PlinthTheme

/**
 * Единственная Activity приложения (A2.1): хост Compose и ничего больше.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlinthTheme {
                PlinthApp()
            }
        }
    }
}

@Composable
private fun PlinthApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                currentRoute = currentRoute,
                onDestinationSelected = { destination ->
                    navController.navigateToTab(destination)
                },
            )
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
