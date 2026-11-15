package app.vault.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vault.App
import app.vault.app
import app.vault.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Search my own sites list with my own words and/or categories.
 * Only theme & mood categories run here; family-relation categories stay on the database (Categories tab).
 */
class MySitesSearchVm(app: Application) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val categories = (Categories.MOODS + repo.categories.load()).filter { Categories.allowedOnSites(it) }.distinctBy { it.code }
    val text = MutableStateFlow("")
    val picked = MutableStateFlow<List<Category>>(emptyList())
    val progress = MutableStateFlow<Map<String, SiteProgress>>(emptyMap())
    val running = MutableStateFlow(false)
    val siteCount = MutableStateFlow(-1)
    val notice = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun toggle(c: Category) {
        picked.value = if (picked.value.any { it.code == c.code }) picked.value.filterNot { it.code == c.code } else picked.value + c
    }

    fun search() {
        job?.cancel(); progress.value = emptyMap(); notice.value = null
        val words = text.value.trim()
        if (words.isEmpty() && picked.value.isEmpty()) return
        if (!Categories.allowedText(words)) {
            notice.value = "Family-relation searches run on the database only — use the Categories tab for those."
            return
        }
        // First category (or my words) is what each site searches for; the rest narrow the results.
        val term = words.ifBlank { picked.value.first().queries.last() }
        val narrow = (if (words.isBlank()) picked.value.drop(1) else picked.value).map { it.words }.filter { it.isNotEmpty() }
        job = viewModelScope.launch {
            running.value = true
            try {
                val sites = repo.videoSearchSites()
                siteCount.value = sites.size
                AppLog.i("MySitesSearch", "Searching ${sites.size} of my sites (${picked.value.size} categories, words ${words.isNotBlank()})")
                val q = VideoQuery(term, emptyList())
                fun keep(p: SiteProgress) = p.copy(hits = p.hits.filter { h ->
                    !Categories.unsafeTitle(h.title) && narrow.all { w -> Categories.score(h.title, w) >= 66 }
                })
                launch { repo.discover(q, broad = false).forEach { p -> progress.value = progress.value.merged(keep(p)) } }
                repo.siteSearch.searchAll(sites, q).collect { p -> progress.value = progress.value.merged(keep(p)) }
            } finally { running.value = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MySitesSearch() {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val vm: MySitesSearchVm = viewModel()
    val text by vm.text.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val siteCount by vm.siteCount.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val moods = vm.categories.filter { it.group == "Moods" }
    val others = vm.categories.filter { it.group != "Moods" }

    SiteResultsPane(
        progress = progress, running = running, siteCount = siteCount.coerceAtLeast(if (progress.isEmpty()) 1 else 0),
        cols = prefs.colsFind, onCols = { prefs.colsFind = it },
        openTerm = text.ifBlank { picked.firstOrNull()?.queries?.last().orEmpty() },
        emptyTitle = "Nothing found on my sites", emptyBody = "Try other words or categories.",
    ) { full ->
        item(span = { full }) {
            Column {
                OutlinedTextField(
                    text, { vm.text.value = it },
                    placeholder = { Text("Search my sites — any words") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { vm.text.value = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true, shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Surface1, focusedContainerColor = Surface1, unfocusedBorderColor = Surface1),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                // Categories (stack them: the first is searched for, the others narrow it down)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Categories", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Fewer" else "All ${vm.categories.size}") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(moods, key = { it.code }) { c -> FilterChip(picked.any { it.code == c.code }, { vm.toggle(c) }, label = { Text(c.label) }) }
                }
                if (showAll) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { c -> FilterChip(picked.any { it.code == c.code }, { vm.toggle(c) }, label = { Text(c.label) }) }
                }
                if (picked.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    picked.forEachIndexed { i, c ->
                        InputChip(true, { vm.toggle(c) }, label = { Text((if (i == 0 && text.isBlank()) "" else "+ ") + c.label) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                    TextButton(onClick = { vm.picked.value = emptyList() }) { Text("Clear") }
                }
                Button(
                    onClick = { focus.clearFocus(); vm.search() }, enabled = !running && (text.isNotBlank() || picked.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp), shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.TravelExplore, null); Spacer(Modifier.width(8.dp))
                    Text(if (running) "Searching…" else "Search my sites", fontWeight = FontWeight.Bold)
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                Text("Family-relation categories aren't searched on your sites — they're in the Categories tab (database only).",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
