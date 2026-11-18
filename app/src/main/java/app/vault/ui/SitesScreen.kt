package app.vault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import app.vault.data.SiteKind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import app.vault.data.AppLog
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
    var pasting by remember { mutableStateOf(false) }
    var kindFilter by remember { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    val scanning by repo.scanning.collectAsState()
    val scanStatus by repo.scanStatus.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching { repo.importSites(ctx, uri) }
                .fold({ (found, added) -> "Found $found sites · $added new" }, { AppLog.e("Sites", "File import failed", it); "Import failed: ${it.message}" })
            snack.showSnackbar(msg)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("My sites", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add site") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Rescan all sites") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { menu = false; repo.scanSites(rescan = true) }, enabled = !scanning)
                        if (sites.isNotEmpty()) DropdownMenuItem(text = { Text("Remove all sites") }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            }
            Text("Stored only on this phone. Used by \"Find on my sites\" on every profile.", color = Muted, fontSize = 13.sp)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { picker.launch(arrayOf("text/html", "text/plain", "application/xhtml+xml", "*/*")) },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Upload file", fontWeight = FontWeight.Bold) }
                FilledTonalButton(
                    onClick = { pasting = true },
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paste", fontWeight = FontWeight.Bold) }
            }

            // Scanner: sorts sites by type and finds the search page of video sites
            val unscanned = sites.count { it.kind == null && !it.domain.contains('/') }
            if (sites.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(12.dp)) {
                Text(
                    when {
                        scanning -> scanStatus ?: "Scanning…"
                        unscanned > 0 -> "$unscanned sites not checked yet. Scan them to find which ones have videos and a search page."
                        else -> "${sites.count { it.kind == SiteKind.VIDEO }} video sites, all searchable (${sites.count { it.kind == SiteKind.VIDEO && !it.searchTemplate.isNullOrBlank() }} with their own search, the rest through web search) · ${sites.count { it.discovered }} found automatically"
                    },
                    fontSize = 13.sp,
                )
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                else if (unscanned > 0) Button(onClick = { repo.scanSites() }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Default.Radar, null); Spacer(Modifier.width(6.dp)); Text("Scan $unscanned sites")
                }
            }

            if (sites.isEmpty()) {
                EmptyState("No sites yet", "Upload a bookmarks export or HTML page, or paste links. Every website is added once.")
            } else {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Filter ${sites.size} sites") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                // Type filter chips with counts
                val filters = remember(sites) {
                    buildList {
                        add(Triple("all", "All", sites.size))
                        add(Triple("searchable", "Searchable", sites.count { isSearchable(it) }))
                        add(Triple("new", "Found automatically", sites.count { it.discovered }))
                        add(Triple("best", "Best", sites.count { (repo.health.score(it.domain) ?: 0) >= 60 }))
                        SiteKind.LABELS.forEach { (k, label) -> add(Triple(k, label, sites.count { it.kind == k })) }
                        add(Triple("unscanned", "Not checked", sites.count { it.kind == null }))
                    }.filter { it.third > 0 || it.first == "all" }
                }
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filters, key = { it.first }) { (k, label, n) -> FilterChip(kindFilter == k, { kindFilter = k }, label = { Text("$label · $n") }) }
                }
                val shown = sites.filter { s ->
                    (q.isBlank() || s.title.contains(q, true) || s.domain.contains(q, true)) && when (kindFilter) {
                        "all" -> true
                        "searchable" -> isSearchable(s)
                        "new" -> s.discovered
                        "best" -> (repo.health.score(s.domain) ?: 0) >= 60
                        "unscanned" -> s.kind == null
                        else -> s.kind == kindFilter
                    }
                }
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(shown, key = { it.domain }) { s ->
                        SiteRow(s, false, trailing = {
                            Column(horizontalAlignment = Alignment.End) {
                                s.kind?.let { k -> Text(SiteKind.LABELS[k] ?: k, fontSize = 11.sp, color = if (k == SiteKind.VIDEO) Accent else Muted) }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (s.discovered) Text("NEW", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Bold)
                                    repo.health.score(s.domain)?.let { h ->
                                        Text("♥$h", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            color = when { h >= 60 -> androidx.compose.ui.graphics.Color(0xFF3DDC84); h >= 30 -> Accent; else -> MaterialTheme.colorScheme.error })
                                    }
                                    if (s.inVideoSearch && !s.searchTemplate.isNullOrBlank()) Icon(Icons.Default.ManageSearch, "Searchable (own search)", tint = Accent, modifier = Modifier.size(14.dp))
                                    else if (isSearchable(s)) Icon(Icons.Default.TravelExplore, "Searchable via web search", tint = Muted, modifier = Modifier.size(14.dp))
                                }
                            }
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
    if (adding) TextPrompt("Add site or exact page", "example.com or a full link (Wayback links work)", onDismiss = { adding = false }) { url ->
        repo.addSite(url.trim(), null)?.let { scope.launch { repo.sites.upsert(it) } }
        adding = false
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }) { text ->
        pasting = false
        scope.launch {
            val msg = runCatching { repo.importSitesText(text) }
                .fold({ (found, added) -> "Found $found sites · $added new" }, { "Import failed: ${it.message}" })
            snack.showSnackbar(msg)
        }
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
    var address by remember { mutableStateOf(site.url) }
    var tpl by remember { mutableStateOf(site.searchTemplate.orEmpty()) }
    var inSearch by remember { mutableStateOf(site.inVideoSearch) }
    var detecting by remember { mutableStateOf(false) }
    var detectMsg by remember { mutableStateOf<String?>(null) }
    val repo = LocalContext.current.app.repo
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(site.domain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Address it opens") }, singleLine = true)
                OutlinedTextField(tpl, { tpl = it }, label = { Text("Search link (optional)") },
                    placeholder = { Text("https://${site.domain}/search?q={q}") })
                Text(
                    "Search for any name on the site, copy the address from the result page and replace the name with {q} " +
                        "(or {q-} if the site joins words with hyphens). Leave empty to detect it automatically.",
                    color = Muted, fontSize = 12.sp,
                )
                OutlinedButton(enabled = !detecting, onClick = {
                    detecting = true; detectMsg = null
                    scope.launch {
                        val sample = repo.performers.favoriteIds().firstOrNull()?.let { repo.performers.get(it)?.name } ?: "Jane Doe"
                        val found = runCatching { repo.siteSearch.detectTemplate(site, sample) }.getOrNull()
                        if (found != null) tpl = found
                        detectMsg = if (found != null) "Found it." else "Couldn't detect — the site may block apps or use an unusual search page."
                        detecting = false
                    }
                }) { Text(if (detecting) "Detecting…" else "Detect search link") }
                detectMsg?.let { Text(it, color = Muted, fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include in video search", Modifier.weight(1f))
                    Switch(inSearch, { inSearch = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(site.copy(title = title.ifBlank { site.title }, url = address.trim().ifBlank { site.url }, searchTemplate = tpl.trim().takeIf { it.contains("{q") }, inVideoSearch = inSearch, searchCheckedAt = if (tpl.isBlank()) 0 else site.searchCheckedAt))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste websites") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste HTML, links or domains — one per line is fine.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("example.com\nhttps://another-site.com\n<a href=\"...\">…</a>") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { text = if (text.isBlank()) it else text + "\n" + it } }) {
                    Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste from clipboard")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A site is searchable for videos if it has its own search page, or is a video site (searched through web engines). */
private fun isSearchable(s: Site) = s.inVideoSearch && !s.domain.contains('/') &&
    (!s.searchTemplate.isNullOrBlank() || s.kind == SiteKind.VIDEO)
