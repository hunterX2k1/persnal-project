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
import app.vault.app

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("discover", "Discover", Icons.Default.GridView),
    Tab("favorites", "Favourites", Icons.Default.FavoriteBorder),
    Tab("categories", "Categories", Icons.Default.Category),
    Tab("web", "Search", Icons.Default.TravelExplore),
    Tab("sites", "My sites", Icons.Default.Language),
    Tab("settings", "Settings", Icons.Default.Tune),
)

@Composable
fun MainScaffold(onPanic: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val newCount by androidx.compose.ui.platform.LocalContext.current.app.repo.releases.unseen.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val previewPlayer = remember { PreviewEngine.newPlayer(ctx).trackPreviewState() }
    DisposableEffect(Unit) { onDispose { previewPlayer.release() } }
    // Stop any preview when leaving a screen
    LaunchedEffect(route) { previewPlayer.pause(); PreviewState.activeId = null }
    CompositionLocalProvider(LocalPreviewPlayer provides previewPlayer) {
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (route?.let { r -> r.startsWith("performer") || r.startsWith("videos") || r.startsWith("scene") || r.startsWith("find") || r.startsWith("playlist") } != true) NavigationBar(containerColor = Surface1) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("discover") { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = {
                            if (t.route == "categories" && newCount > 0) BadgedBox(badge = { androidx.compose.material3.Badge { Text("$newCount") } }) { Icon(t.icon, null) }
                            else Icon(t.icon, null)
                        },
                        label = { Text(t.label, maxLines = 1) },
                        alwaysShowLabel = false,
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
            composable("categories") {
                CategoriesScreen(
                    onScene = { sid -> nav.navigate("scene/${android.net.Uri.encode(sid)}") },
                    onPlaylist = { n -> nav.navigate("playlist/${android.net.Uri.encode(n)}") },
                )
            }
            composable("playlist/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) {
                PlaylistScreen(
                    name = it.arguments?.getString("name").orEmpty(), onBack = { nav.popBackStack() },
                    onScene = { sid -> nav.navigate("scene/${android.net.Uri.encode(sid)}") },
                )
            }
            composable("sites") { SitesScreen() }
            composable("settings") { SettingsScreen() }
            composable("performer/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val pid = it.arguments?.getString("id").orEmpty()
                PerformerScreen(
                    id = pid, onBack = { nav.popBackStack() },
                    onScene = { sid -> nav.navigate("scene/${android.net.Uri.encode(sid)}") },
                    onAllVideos = { nav.navigate("videos/${android.net.Uri.encode(pid)}") },
                    onFind = { nav.navigate("find/${android.net.Uri.encode(pid)}") },
                    onPerformer = open,
                )
            }
            composable("find/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                FindVideosScreen(performerId = it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("videos/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                VideosScreen(
                    performerId = it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() },
                    onScene = { sid -> nav.navigate("scene/${android.net.Uri.encode(sid)}") },
                )
            }
            composable("scene/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                SceneScreen(
                    sceneId = it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() },
                    onPerformer = open,
                )
            }
        }
    }
    }
}
