package app.vault.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vault.App
import app.vault.app
import app.vault.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Searches every searchable site for one performer and merges the results (duplicates grouped, full versions first). */
class FindVideosVm(app: Application, val performerId: String) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val performer = MutableStateFlow<Performer?>(null)
    val progress = MutableStateFlow<Map<String, SiteProgress>>(emptyMap())
    val running = MutableStateFlow(false)
    val siteCount = MutableStateFlow(-1)
    private var job: Job? = null

    init { viewModelScope.launch { performer.value = repo.performers.get(performerId); search() } }

    fun search() {
        val p = performer.value ?: return
        job?.cancel(); progress.value = emptyMap()
        job = viewModelScope.launch {
            running.value = true
            val sites = repo.videoSearchSites()
            siteCount.value = sites.size
            AppLog.i("FindVideos", "Searching ${sites.size} sites for a performer")
            val names = listOf(p.name) + p.aliases?.split(',')?.map { it.trim() }?.filter { it.length >= 4 }.orEmpty().take(2)
            val q = VideoQuery(p.name, names)
            launch { repo.discover(q).forEach { pr -> progress.value = progress.value.merged(pr) } }
            repo.siteSearch.searchAll(sites, q).collect { pr -> progress.value = progress.value.merged(pr) }
        }
        job?.invokeOnCompletion { running.value = false }
    }
}

private enum class FindSort(val label: String) { BEST("Best match"), LONGEST("Longest"), SHORTEST("Shortest"), FASTEST("Fastest site"), MOST_COPIES("Most copies"), SITE("Site A–Z") }
private enum class FindLength(val label: String, val range: IntRange) { ANY("Any length", 0..Int.MAX_VALUE), SHORT("< 10 min", 1..599), MID("10–30 min", 600..1800), LONG("30 min +", 1801..Int.MAX_VALUE) }

@Composable
fun FindVideosScreen(performerId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val prefs = ctx.app.prefs
    val vm: FindVideosVm = viewModel(key = "f:$performerId", factory = viewModelFactory { initializer { FindVideosVm(app, performerId) } })
    val p by vm.performer.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val siteCount by vm.siteCount.collectAsStateWithLifecycle()

    var q by rememberSaveable { mutableStateOf("") }
    var fullOnly by rememberSaveable { mutableStateOf(false) }
    var length by rememberSaveable { mutableStateOf(FindLength.ANY) }
    var sort by rememberSaveable { mutableStateOf(FindSort.BEST) }
    var site by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val cols = prefs.colsFind

    val allGroups = remember(progress) { HitGroups.group(progress.values.flatMap { it.hits }) }
    val sitesWithResults = remember(allGroups) {
        allGroups.flatMap { g -> g.all.map { it.siteTitle } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val groups = remember(allGroups, q, fullOnly, length, sort, site) {
        fun secs(g: HitGroup) = g.all.maxOf { HitGroups.seconds(it.duration) ?: 0 }
        allGroups.filter { g ->
            (q.isBlank() || SiteSearch.tokens(q).all { w -> g.all.any { it.title.lowercase().contains(w) } }) &&
                (!fullOnly || g.all.any { HitGroups.isFull(it) == true }) &&
                (length == FindLength.ANY || secs(g) in length.range) &&
                (site == null || g.all.any { it.siteTitle == site })
        }.let { l ->
            when (sort) {
                FindSort.BEST -> l
                FindSort.LONGEST -> l.sortedByDescending { secs(it) }
                FindSort.SHORTEST -> l.sortedBy { secs(it).takeIf { s -> s > 0 } ?: Int.MAX_VALUE }
                FindSort.FASTEST -> l.sortedBy { g -> g.all.minOf { if (it.ms > 0) it.ms else Long.MAX_VALUE } }
                FindSort.MOST_COPIES -> l.sortedByDescending { it.all.size }
                FindSort.SITE -> l.sortedBy { it.best.siteTitle.lowercase() }
            }
        }
    }
    val done = progress.values.count { it.status !is SiteStatus.Searching }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(p?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text(
                    when {
                        siteCount == 0 -> "No searchable sites yet"
                        running -> "Searching $done / $siteCount sites…"
                        else -> "${groups.size} of ${allGroups.size} videos · ${progress.values.count { it.hits.isNotEmpty() }} sites"
                    },
                    color = Muted, fontSize = 12.sp,
                )
            }
            Box {
                TextButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, null); Text(sort.label) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    FindSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                }
            }
            IconButton(onClick = { vm.search() }, enabled = !running) { Icon(Icons.Default.Refresh, "Search again") }
        }
        if (running && siteCount > 0) LinearProgressIndicator(progress = { done / siteCount.toFloat() }, modifier = Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                q, { q = it }, placeholder = { Text("Search these results") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, null) } },
                shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f),
            )
            ColumnPicker(cols) { prefs.colsFind = it }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(fullOnly, { fullOnly = !fullOnly }, label = { Text("Full only") }) }
            items(FindLength.entries.drop(1)) { l -> FilterChip(length == l, { length = if (length == l) FindLength.ANY else l }, label = { Text(l.label) }) }
            items(sitesWithResults) { s -> FilterChip(site == s, { site = if (site == s) null else s }, label = { Text(s) }) }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (cols >= 3) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (cols >= 3) 6.dp else 10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val full = GridItemSpan(cols)
            if (siteCount == 0) item(span = { full }) {
                EmptyState("No searchable sites", "Add video sites in My sites and scan them.")
            }
            if (!running && allGroups.isEmpty() && siteCount > 0 && progress.isNotEmpty()) item(span = { full }) {
                EmptyState("Nothing found", "None of your sites had videos for this name.")
            }
            if (allGroups.isNotEmpty() && groups.isEmpty()) item(span = { full }) { EmptyState("No matches", "Loosen the filters or search words.") }
            items(groups, key = { it.best.url }) { g -> if (cols == 1) GroupCard(g) else HitTile(g, cols) }
            if (progress.isNotEmpty()) item(span = { full }) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide site details" else "Show each site's result") }
            }
            if (details) items(progress.values.sortedBy { it.site.title.lowercase() }, key = { "s:" + it.site.domain }, span = { full }) { pr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pr.site.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(statusText(pr.status), color = if (pr.status is SiteStatus.Found) Accent else Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { ctx.openBrowser(vm.repo.siteSearchUrl(pr.site, p?.name.orEmpty())) }) { Text("Open") }
                }
            }
        }
    }
}
