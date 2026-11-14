package app.atlas.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.atlas.data.InternalLinks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AtlasTheme { Main() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Explore),
    Tab("search", "Search", Icons.Default.Search),
    Tab("saved", "Saved", Icons.Default.Bookmarks),
    Tab("settings", "Settings", Icons.Default.Tune),
)

@Composable
private fun Main() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    val open: (String, String) -> Unit = remember(nav) {
        { url, title ->
            val internal = InternalLinks.resolve(url)
            when {
                internal == null -> ctx.openBrowser(url, title)
                internal.first.isEmpty() -> nav.navigate("home") { popUpTo("home") { inclusive = false }; launchSingleTop = true }
                else -> nav.navigate("page/${internal.first}?a=${android.net.Uri.encode(internal.second.orEmpty())}")
            }
        }
    }

    CompositionLocalProvider(LocalOpen provides open) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Surface1) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Surface2, selectedIconColor = Accent, selectedTextColor = Accent),
                        )
                    }
                }
            },
        ) { pad ->
            NavHost(nav, "home", Modifier.padding(pad)) {
                composable("home") { HomeScreen(onPage = { nav.navigate("page/$it?a=") }, onSearch = { nav.navigate("search") }) }
                composable("search") { SearchScreen() }
                composable("saved") { SavedScreen() }
                composable("settings") { SettingsScreen() }
                composable(
                    "page/{slug}?a={a}",
                    arguments = listOf(navArgument("slug") { type = NavType.StringType }, navArgument("a") { type = NavType.StringType; defaultValue = "" }),
                ) {
                    PageScreen(
                        slug = it.arguments?.getString("slug").orEmpty(),
                        anchor = it.arguments?.getString("a")?.takeIf(String::isNotEmpty),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
