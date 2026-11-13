package app.vault.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("discover", "Discover", Icons.Default.GridView),
    Tab("favorites", "Favourites", Icons.Default.FavoriteBorder),
    Tab("web", "Search", Icons.Default.TravelExplore),
    Tab("sites", "My sites", Icons.Default.Language),
    Tab("settings", "Settings", Icons.Default.Tune),
)

@Composable
fun MainScaffold(onPanic: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (route?.startsWith("performer") != true) NavigationBar(containerColor = Surface1) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("discover") { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Surface2, selectedIconColor = Accent, selectedTextColor = Accent),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "discover", modifier = Modifier.padding(pad)) {
            val open: (String) -> Unit = { id -> nav.navigate("performer/${android.net.Uri.encode(id)}") }
            composable("discover") { DiscoverScreen(key = "all", favoritesOnly = false, onOpen = open, onPanic = onPanic, onSettings = { nav.navigate("settings") }) }
            composable("favorites") { DiscoverScreen(key = "fav", favoritesOnly = true, onOpen = open, onPanic = onPanic, onSettings = { nav.navigate("settings") }) }
            composable("web") { WebSearchScreen() }
            composable("sites") { SitesScreen() }
            composable("settings") { SettingsScreen() }
            composable("performer/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                PerformerScreen(id = it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}
