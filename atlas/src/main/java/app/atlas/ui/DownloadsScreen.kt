package app.atlas.ui

import android.app.DownloadManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import app.atlas.app
import app.atlas.data.DownloadStatus
import app.atlas.data.EpisodeParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where to save a file: name, folder (series episodes are filed automatically), Wi-Fi only. */
@Composable
fun SaveDialog(fileName: String, onDismiss: () -> Unit, onSave: (name: String, folder: String, wifiOnly: Boolean) -> Unit) {
    val dl = LocalContext.current.app.downloads
    var name by remember { mutableStateOf(fileName) }
    var folder by remember { mutableStateOf(EpisodeParser.suggestFolder(fileName)) }
    var wifi by remember { mutableStateOf(dl.wifiOnly) }
    val recent = remember { dl.recentFolders() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; if (folder.isBlank()) folder = EpisodeParser.suggestFolder(it) }, label = { Text("File name") }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder in Downloads/Atlas") }, placeholder = { Text("Leave empty for no folder") }, singleLine = true)
                if (recent.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recent) { f -> SuggestionChip(onClick = { folder = f }, label = { Text(f, maxLines = 1) }) }
                }
                EpisodeParser.parse(name)?.let { Text("Episode ${it.episode} of ${it.show}, season ${it.season}", color = Muted, fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Only on Wi-Fi", Modifier.weight(1f))
                    Switch(wifi, { wifi = it })
                }
            }
        },
        confirmButton = { Button(onClick = { dl.wifiOnly = wifi; onSave(name, folder, wifi) }, enabled = name.isNotBlank()) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val dl = ctx.app.downloads
    val records by dl.records.collectAsState()
    var statuses by remember { mutableStateOf<List<DownloadStatus>>(emptyList()) }
    var link by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var toSave by remember { mutableStateOf<Pair<String, app.atlas.data.Downloads.LinkInfo>?>(null) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // Refresh progress every second while this screen is open.
    LaunchedEffect(records) { while (true) { statuses = dl.statuses(); delay(1000) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (statuses.any { it.finished }) TextButton(onClick = { dl.clearFinished() }) { Text("Clear finished") }
            }
            Text("Saved in Downloads/Atlas. Episodes are filed by series and season, other files by type.", color = Muted, fontSize = 13.sp)
            // Storage: space used by finished downloads, and free space on the phone
            val used = statuses.filter { it.finished }.sumOf { it.total.coerceAtLeast(0) }
            val free = remember(statuses.size) {
                runCatching { android.os.StatFs(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path).availableBytes }.getOrDefault(-1L)
            }
            if (statuses.isNotEmpty()) Text(
                "Using ${size(used)} in ${statuses.count { it.finished }} files" + if (free > 0) " · ${size(free)} free on the phone" else "",
                color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )

            // Download from a direct file link
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(link, { link = it }, placeholder = { Text("Paste a file link (…/file.mp4)") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, null) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp))
                FilledIconButton(enabled = link.startsWith("http") && !checking, onClick = {
                    checking = true
                    scope.launch {
                        val url = link.trim()
                        dl.checkLink(url, BrowserActivity.MOBILE_UA)
                            .onSuccess { info ->
                                if (info == null) snack.showSnackbar("That link is a web page, not a file. Only direct file links can be downloaded.")
                                else toSave = url to info
                            }
                            .onFailure { snack.showSnackbar("Couldn't check that link: ${it.message}") }
                        checking = false
                    }
                }) { if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, "Download") }
            }

            if (statuses.isEmpty()) EmptyState("No downloads yet", "Tap a site's download button in the browser, or paste a file link above.")
            else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                statuses.groupBy { it.rec.folder.ifBlank { "Atlas" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (folder, list) ->
                    item(key = "f:$folder") {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Text(folder, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp).weight(1f))
                            Text("${list.count { it.finished }}/${list.size} · ${size(list.filter { it.finished }.sumOf { it.total.coerceAtLeast(0) })}", color = Muted, fontSize = 12.sp)
                        }
                    }
                    items(list.sortedBy { it.rec.fileName.lowercase() }, key = { it.rec.id }) { s -> DownloadRow(s) }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }

    toSave?.let { (url, info) ->
        SaveDialog(info.fileName, onDismiss = { toSave = null }) { name, folder, wifi ->
            dl.enqueue(url, name, folder, BrowserActivity.MOBILE_UA, info.mime, wifi)
            toSave = null; link = ""
        }
    }
}

@Composable
private fun DownloadRow(s: DownloadStatus) {
    val ctx = LocalContext.current
    val dl = ctx.app.downloads
    val stateText = when (s.state) {
        DownloadManager.STATUS_SUCCESSFUL -> "Done · ${size(s.total)}"
        DownloadManager.STATUS_RUNNING -> "${(s.progress * 100).toInt()}% · ${size(s.done)} of ${size(s.total)}"
        DownloadManager.STATUS_PENDING -> "Waiting…"
        DownloadManager.STATUS_PAUSED -> if (s.rec.wifiOnly) "Waiting for Wi-Fi" else "Waiting for connection"
        else -> "Failed"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.rec.fileName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(stateText, color = if (s.failed) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp)
            }
            when {
                s.finished -> IconButton(onClick = {
                    val uri = dl.openUri(s.rec) ?: return@IconButton
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, s.rec.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Default.PlayCircle, "Open", tint = Accent) }
                s.failed -> IconButton(onClick = { dl.retry(s.rec) }) { Icon(Icons.Default.Refresh, "Retry", tint = Accent) }
            }
            IconButton(onClick = { dl.remove(s.rec) }) {
                Icon(if (s.finished) Icons.Default.Delete else Icons.Default.Close, if (s.finished) "Delete file" else "Stop", tint = Muted)
            }
        }
        if (s.state == DownloadManager.STATUS_RUNNING || s.state == DownloadManager.STATUS_PENDING)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

private fun size(b: Long): String = when {
    b <= 0 -> "?"
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
