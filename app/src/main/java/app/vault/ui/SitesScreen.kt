package app.vault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.app
import app.vault.data.Site
import kotlinx.coroutines.launch

@Composable
fun SitesScreen() {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val sites by repo.sites.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var q by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Site?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching { repo.importSites(ctx, uri) }
                .fold({ (found, added) -> "Found $found sites · $added new" }, { "Import failed: ${it.message}" })
            snack.showSnackbar(msg)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("My sites", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add site") }
                if (sites.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Clear all", tint = Muted) }
            }
            Text("Stored only on this phone. Used by \"Find on my sites\" on every profile.", color = Muted, fontSize = 13.sp)
            Button(
                onClick = { picker.launch(arrayOf("text/html", "text/plain", "application/xhtml+xml", "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(50.dp),
            ) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import HTML file", fontWeight = FontWeight.Bold) }

            if (sites.isEmpty()) {
                EmptyState("No sites yet", "Import a bookmarks export or any HTML page with links. Every website in it is added once.")
            } else {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Filter ${sites.size} sites") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                val shown = sites.filter { q.isBlank() || it.title.contains(q, true) || it.domain.contains(q, true) }
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(shown, key = { it.domain }) { s ->
                        SiteRow(s, false, trailing = {
                            IconButton(onClick = { editing = s }) { Icon(Icons.Default.Edit, "Edit", tint = Muted) }
                        }) { ctx.openBrowser(s.url) }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }

    editing?.let { s -> EditSiteDialog(s, onDismiss = { editing = null },
        onSave = { scope.launch { repo.sites.upsert(it) }; editing = null },
        onDelete = { scope.launch { repo.sites.delete(s) }; editing = null }) }
    if (adding) TextPrompt("Add site", "example.com", onDismiss = { adding = false }) { url ->
        repo.addSite(url.trim(), null)?.let { scope.launch { repo.sites.upsert(it) } }
        adding = false
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Remove all ${sites.size} sites?") },
        confirmButton = { TextButton(onClick = { scope.launch { repo.sites.clear() }; confirmClear = false }) { Text("Remove all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun EditSiteDialog(site: Site, onDismiss: () -> Unit, onSave: (Site) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(site.title) }
    var tpl by remember { mutableStateOf(site.searchTemplate.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(site.domain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(tpl, { tpl = it }, label = { Text("Search link (optional)") },
                    placeholder = { Text("https://${site.domain}/search?q={q}") })
                Text(
                    "Search for any name on the site, copy the address from the result page and replace the name with {q}. " +
                        "Leave empty to use a web search limited to this site.",
                    color = Muted, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(site.copy(title = title.ifBlank { site.title }, searchTemplate = tpl.trim().takeIf { it.contains("{q}") }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
    )
}
