package app.vault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.app
import app.vault.data.Prefs

/** Search tab: My sites (search my own list, with categories) or Web (search engines). */
@Composable
fun WebSearchScreen() {
    var mode by rememberSaveable { mutableStateOf("sites") }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            FilterChip(mode == "sites", { mode = "sites" }, label = { Text("My sites") }, leadingIcon = { Icon(Icons.Default.Language, null, Modifier.size(16.dp)) })
            Spacer(Modifier.width(6.dp))
            FilterChip(mode == "web", { mode = "web" }, label = { Text("Web") }, leadingIcon = { Icon(Icons.Default.TravelExplore, null, Modifier.size(16.dp)) })
        }
        if (mode == "sites") MySitesSearch() else WebEnginesPane()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WebEnginesPane() {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val sites by ctx.app.repo.sites.all().collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    var addingEngine by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Prefs.Engine?>(null) }
    fun go() {
        val t = q.trim(); if (t.isEmpty()) return
        val looksLikeUrl = !t.contains(' ') && t.contains('.')
        if (looksLikeUrl) ctx.openBrowser(if (t.startsWith("http")) t else "https://$t", fallback = prefs.webSearchUrl(t))
        else ctx.openBrowser(prefs.webSearchUrl(t))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Search engines · opens in the private in-app browser.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("Search or type a web address") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = ::go) { Icon(Icons.Default.ArrowCircleRight, "Go", tint = Accent) } },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        // Search engines: tap to choose, long-press one you added to remove it
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(prefs.engines, key = { it.key }) { e ->
                Box(Modifier.combinedClickable(onClick = { prefs.searchEngine = e.key }, onLongClick = { if (e.custom) removing = e })) {
                    FilterChip(selected = prefs.searchEngine == e.key, onClick = { prefs.searchEngine = e.key },
                        label = { Text(e.label) }, leadingIcon = if (e.custom) ({ Icon(Icons.Default.Star, null, Modifier.size(14.dp)) }) else null)
                }
            }
            item { AssistChip(onClick = { addingEngine = true }, label = { Text("Add engine") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) }) }
        }
        if (sites.isNotEmpty()) {
            SectionTitle("Quick open")
            LazyColumn {
                items(sites, key = { it.domain }) { s -> SiteRow(s, false) { ctx.openBrowser(s.url) } }
            }
        }
    }

    if (addingEngine) {
        var name by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        var discover by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { addingEngine = false },
            title = { Text("Add a search engine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(link, { link = it }, label = { Text("Search link") }, placeholder = { Text("https://example.com/search?q={q}") }, singleLine = true)
                    Text("Search for anything on that engine, copy the address of the results page, and replace your words with {q}.", color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Also use it to find new sites", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(discover, { discover = it })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && link.contains("{q}") && link.startsWith("http"), onClick = {
                    prefs.addEngine(name.trim(), link.trim(), discover); prefs.searchEngine = "c:${name.trim()}"; addingEngine = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingEngine = false }) { Text("Cancel") } },
        )
    }
    removing?.let { e ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${e.label}?") },
            confirmButton = { TextButton(onClick = { prefs.removeEngine(e); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
