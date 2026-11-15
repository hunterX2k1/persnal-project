package app.vault.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
            repo.siteSearch.searchAll(sites, VideoQuery(p.name, names)).collect { pr -> progress.value = progress.value + (pr.site.domain to pr) }
        }
        job?.invokeOnCompletion { running.value = false }
    }
}

private enum class Show(val label: String) { ALL("All"), FULL("Full only"), LONG("Longest") }

@Composable
fun FindVideosScreen(performerId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: FindVideosVm = viewModel(key = "f:$performerId", factory = viewModelFactory { initializer { FindVideosVm(app, performerId) } })
    val p by vm.performer.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val siteCount by vm.siteCount.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(Show.ALL) }
    var details by remember { mutableStateOf(false) }

    val groups = remember(progress, show) {
        HitGroups.group(progress.values.flatMap { it.hits }).let { g ->
            when (show) {
                Show.ALL -> g
                Show.FULL -> g.filter { gr -> gr.all.any { HitGroups.isFull(it) == true } }
                Show.LONG -> g.sortedByDescending { gr -> gr.all.maxOf { HitGroups.seconds(it.duration) ?: 0 } }
            }
        }
    }
    val done = progress.values.count { it.status !is SiteStatus.Searching }
    val copies = progress.values.sumOf { it.hits.size }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(p?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text(
                    when {
                        siteCount == 0 -> "No searchable sites yet"
                        running -> "Searching $done / $siteCount sites…"
                        else -> "${groups.size} videos · $copies copies on ${progress.values.count { it.hits.isNotEmpty() }} sites"
                    },
                    color = Muted, fontSize = 12.sp,
                )
            }
            IconButton(onClick = { vm.search() }, enabled = !running) { Icon(Icons.Default.Refresh, "Search again") }
        }
        if (running && siteCount > 0) LinearProgressIndicator(progress = { done / siteCount.toFloat() }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Show.entries.forEach { s -> FilterChip(show == s, { show = s }, label = { Text(s.label) }) }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (siteCount == 0) item {
                EmptyState("No searchable sites", "Add video sites in My sites. Sites without a search page are skipped; you can set a site's search link by editing it.")
            }
            if (!running && groups.isEmpty() && siteCount > 0 && progress.isNotEmpty()) item {
                EmptyState("Nothing found", "None of your searchable sites had videos for this name.")
            }
            items(groups, key = { it.best.url }) { GroupCard(it) }
            if (progress.isNotEmpty()) item {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide site details" else "Show each site's result") }
            }
            if (details) items(progress.values.sortedBy { it.site.title.lowercase() }, key = { "s:" + it.site.domain }) { pr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pr.site.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(statusText(pr.status), color = if (pr.status is SiteStatus.Found) Accent else Muted, fontSize = 12.sp)
                    }
                    val ctx = LocalContext.current
                    TextButton(onClick = { ctx.openBrowser(vm.repo.siteSearchUrl(pr.site, p?.name.orEmpty())) }) { Text("Open") }
                }
            }
        }
    }
}
