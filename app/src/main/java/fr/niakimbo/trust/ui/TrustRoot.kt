package fr.niakimbo.trust.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.niakimbo.trust.AppContainer
import fr.niakimbo.trust.R
import fr.niakimbo.trust.ui.screens.AboutScreen
import fr.niakimbo.trust.ui.screens.BibleScreen
import fr.niakimbo.trust.ui.screens.HomeScreen
import fr.niakimbo.trust.ui.screens.PlayerScreen
import fr.niakimbo.trust.ui.screens.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val BIBLE = "bible"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private class Tab(val route: String, val label: String, val vector: ImageVector? = null, @DrawableRes val res: Int = 0)

private val tabs = listOf(
    Tab(Routes.HOME, "Accueil", vector = Icons.Filled.Home),
    Tab(Routes.BIBLE, "Bible", res = R.drawable.ic_book),
    Tab(Routes.PLAYER, "Lecteur", res = R.drawable.ic_headphones),
    Tab(Routes.SETTINGS, "Réglages", vector = Icons.Filled.Settings),
)

@Composable
fun TrustRoot(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = tabs.any { it.route == current }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.vector != null) Icon(tab.vector, contentDescription = null)
                                else Icon(painterResource(tab.res), contentDescription = null)
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(container) }
            composable(Routes.BIBLE) { BibleScreen() }
            composable(Routes.PLAYER) { PlayerScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(container, onOpenAbout = { nav.navigate(Routes.ABOUT) }) }
            composable(Routes.ABOUT) { AboutScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}
