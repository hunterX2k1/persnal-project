package app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.app
import app.vault.data.formatDuration

@Composable
fun PlaylistScreen(name: String, onBack: () -> Unit, onScene: (String) -> Unit) {
    val repo = LocalContext.current.app.repo
    val all by repo.playlists.all.collectAsState()
    val list = all[name].orEmpty()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun open(id: String) { list.firstOrNull { it.id == id }?.let { repo.sceneCache[it.id] = it }; onScene(id) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text("${list.size} videos", color = Muted, fontSize = 12.sp)
            }
            IconButton(enabled = list.isNotEmpty(), onClick = { list.randomOrNull()?.let { open(it.id) } }) { Icon(Icons.Default.Shuffle, "Shuffle") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; deleting = true })
                }
            }
        }
        if (list.isEmpty()) EmptyState("Empty playlist", "Open any video and tap Playlist to add it here.")
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(140.dp).aspectRatio(16f / 9f)) {
                        PreviewThumb(s.id, listOf(s.thumb) + s.imageFallbacks, s.trailer, Modifier.fillMaxSize(), scrub = true) { open(s.id) }
                        formatDuration(s.durationSec)?.let { Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { Badge(it, dark = true) } }
                    }
                    Column(Modifier.weight(1f).clickable { open(s.id) }.padding(8.dp)) {
                        Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(s.siteName, s.date).joinToString(" · "), color = Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    Column {
                        IconButton(enabled = i > 0, onClick = { repo.playlists.move(name, i, i - 1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(enabled = i < list.size - 1, onClick = { repo.playlists.move(name, i, i + 1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    }
                    IconButton(onClick = { repo.playlists.toggle(name, s) }) { Icon(Icons.Default.Close, "Remove", tint = Muted) }
                }
            }
        }
    }
    if (renaming) TextPrompt("Rename playlist", name, initial = name, onDismiss = { renaming = false }) { n ->
        repo.playlists.rename(name, n); renaming = false; onBack()
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete \"$name\"?") },
        text = { Text("The videos stay everywhere else; only this list is removed.") },
        confirmButton = { TextButton(onClick = { repo.playlists.delete(name); deleting = false; onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}
