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
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import fr.niakimbo.trust.ui.components.MiniPlayer
import fr.niakimbo.trust.ui.screens.BooksScreen
import fr.niakimbo.trust.ui.screens.ChaptersScreen
import fr.niakimbo.trust.ui.screens.JournalScreen
import fr.niakimbo.trust.ui.screens.ReaderScreen
import fr.niakimbo.trust.ui.screens.HomeScreen
import fr.niakimbo.trust.ui.screens.PlayerScreen
import fr.niakimbo.trust.ui.screens.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val BIBLE = "bible"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val BOOK = "book/{code}"
    const val READ = "read/{code}/{chapter}?verse={verse}"
    const val JOURNAL = "journal?tab={tab}"
    fun book(code: String) = "book/$code"
    fun read(code: String, chapter: Int, verse: Int = 0) = "read/$code/$chapter?verse=$verse"
    fun journal(tab: Int = 0) = "journal?tab=$tab"
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
    val showBar = tabs.any { it.route == current } || current == Routes.BOOK || current == Routes.READ
    val player by container.player.state.collectAsStateWithLifecycle()

    fun goTab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) Column {
                if (current != Routes.PLAYER) {
                    MiniPlayer(
                        player,
                        onOpen = { goTab(Routes.PLAYER) },
                        onToggle = { container.player.togglePlay() },
                        onNextVerse = { container.player.nextVerse() },
                    )
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route ||
                                (tab.route == Routes.BIBLE && (current == Routes.BOOK || current == Routes.READ)),
                            onClick = { goTab(tab.route) },
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
            composable(Routes.HOME) {
                HomeScreen(container, onResume = {
                    container.player.resumeLast()
                    goTab(Routes.PLAYER)
                }, onOpenBible = { goTab(Routes.BIBLE) }, onOpenJournal = { tab -> nav.navigate(Routes.journal(tab)) })
            }
            composable(Routes.JOURNAL, listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
                JournalScreen(container, e.arguments!!.getInt("tab"), onBack = { nav.popBackStack() }) { code, c, v ->
                    nav.navigate(Routes.read(code, c, v))
                }
            }
            composable(Routes.BIBLE) { BooksScreen(container) { code -> nav.navigate(Routes.book(code)) } }
            composable(Routes.BOOK, listOf(navArgument("code") { type = NavType.StringType })) { e ->
                val code = e.arguments!!.getString("code")!!
                ChaptersScreen(container, code, onBack = { nav.popBackStack() }) { c -> nav.navigate(Routes.read(code, c)) }
            }
            composable(
                Routes.READ,
                listOf(
                    navArgument("code") { type = NavType.StringType },
                    navArgument("chapter") { type = NavType.IntType },
                    navArgument("verse") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { e ->
                val code = e.arguments!!.getString("code")!!
                val chapter = e.arguments!!.getInt("chapter")
                val verse = e.arguments!!.getInt("verse")
                ReaderScreen(container, code, chapter, verse, onBack = { nav.popBackStack() }) { c ->
                    nav.navigate(Routes.read(code, c)) { popUpTo(Routes.READ) { inclusive = true } }
                }
            }
            composable(Routes.PLAYER) {
                PlayerScreen(
                    container,
                    onOpenText = { code, c -> nav.navigate(Routes.read(code, c)) },
                    onOpenBible = { goTab(Routes.BIBLE) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(container, onOpenAbout = { nav.navigate(Routes.ABOUT) }) }
            composable(Routes.ABOUT) { AboutScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}
