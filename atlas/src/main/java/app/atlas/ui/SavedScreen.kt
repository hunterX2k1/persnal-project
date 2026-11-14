package app.atlas.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.app
import app.atlas.data.Saved

@Composable
fun SavedScreen() {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val bookmarks by store.bookmarks.collectAsState()
    val history by store.history.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val open = LocalOpen.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Saved", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        TabRow(tab, containerColor = Bg) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Bookmarks (${bookmarks.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("History") })
        }
        val list = if (tab == 0) bookmarks else history
        if (list.isEmpty()) EmptyState(
            if (tab == 0) "No bookmarks yet" else "Nothing opened yet",
            if (tab == 0) "Tap the bookmark icon on any site to keep it here." else "Sites you open appear here.",
        ) else {
            if (tab == 1) TextButton(onClick = { store.clearHistory() }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text("Clear history") }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.url + it.time }) { s ->
                    SavedRow(s, onOpen = { open(s.url, s.name) }, onRemove = if (tab == 0) ({ store.removeBookmark(s.url) }) else null)
                }
            }
        }
    }
}

@Composable
private fun SavedRow(s: Saved, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Favicon(s.url, 28)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Uri.parse(s.url).host.orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1)
            if (s.desc.isNotBlank()) MdText(s.desc, color = Muted, fontSize = 12.sp, maxLines = 2)
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove", tint = Muted) }
    }
}
