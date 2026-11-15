package app.vault.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

class SceneVm(app: Application, val sceneId: String) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val scene = repo.sceneCache[sceneId]
    /** Per-site progress, in the order sites were searched. */
    val progress = MutableStateFlow<Map<String, SiteProgress>>(emptyMap())
    val running = MutableStateFlow(false)
    val siteCount = MutableStateFlow(-1)
    private var job: Job? = null

    fun search() {
        val s = scene ?: return
        job?.cancel()
        progress.value = emptyMap()
        job = viewModelScope.launch {
            running.value = true
            val sites = repo.videoSearchSites()
            siteCount.value = sites.size
            AppLog.i("SiteSearch", "Searching ${sites.size} sites for a video")
            val q = VideoQuery(s.title, s.performers.map { it.name })
            repo.siteSearch.searchAll(sites, q).collect { p ->
                progress.value = progress.value + (p.site.domain to p)
                if (progress.value.values.none { it.status is SiteStatus.Searching } && progress.value.size == sites.size) running.value = false
            }
        }
        job?.invokeOnCompletion { running.value = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneScreen(sceneId: String, onBack: () -> Unit, onPerformer: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val vm: SceneVm = viewModel(key = "s:$sceneId", factory = viewModelFactory { initializer { SceneVm(app, sceneId) } })
    val s = vm.scene
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val siteCount by vm.siteCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var descOpen by remember { mutableStateOf(false) }
    var showSites by remember { mutableStateOf(false) }

    if (s == null) {
        Column(Modifier.statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            EmptyState("Video not loaded", "Open it again from the performer's profile.")
        }
        return
    }
    val hits = remember(progress) { progress.values.flatMap { it.hits }.sortedByDescending { it.score } }
    val done = progress.values.count { it.status !is SiteStatus.Searching }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(enabled = s.trailer != null) { s.trailer?.let { ctx.playVideo(it, s.title) } }) {
                VImage(listOf(s.image) + s.imageFallbacks + s.thumb, Modifier.fillMaxSize())
                if (s.trailer != null) Box(Modifier.align(Alignment.Center).size(64.dp).clip(RoundedCornerShape(32.dp)).background(Color(0x99000000)), Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play preview", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                formatDuration(s.durationSec)?.let { Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Badge(it, dark = true) } }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(s.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(listOfNotNull(s.siteName, s.date, formatDuration(s.durationSec)).joinToString(" • "), color = Muted, fontSize = 13.sp)

                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.url != null) OutlinedButton(onClick = { ctx.openBrowser(s.url) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Official page") }
                    OutlinedButton(onClick = {
                        ctx.openBrowser(vm.repo.prefs.webSearchUrl("\"${s.title}\" " + s.performers.take(2).joinToString(" ") { it.name }))
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Web search") }
                }

                if (s.performers.isNotEmpty()) {
                    SectionTitle("Cast")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.performers.forEach { p ->
                            AssistChip(onClick = {
                                scope.launch {
                                    val found = p.id?.let { vm.repo.ensurePerformer(it) }
                                    if (found != null) onPerformer(found.id) else snack.showSnackbar("${p.name} isn't in your library yet — search Discover for them")
                                }
                            }, label = { Text(p.name) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(16.dp)) })
                        }
                    }
                }
                if (s.tags.isNotEmpty()) {
                    SectionTitle("Tags")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.tags.forEach { t -> Text(t, fontSize = 12.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Surface2).padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
                }
                s.description?.let {
                    SectionTitle("About")
                    Text(it, color = Color(0xFFCFCFD4), maxLines = if (descOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { descOpen = !descOpen })
                }

                // ---------- Find this video on my sites ----------
                SectionTitle("Find on my sites")
                Button(
                    onClick = { vm.search() }, enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.TravelExplore, null); Spacer(Modifier.width(8.dp))
                    Text(if (progress.isEmpty()) "Search all my sites" else if (running) "Searching… $done / $siteCount" else "Search again", fontWeight = FontWeight.Bold)
                }
                if (running) LinearProgressIndicator(progress = { if (siteCount == 0) 0f else done / siteCount.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (progress.isNotEmpty()) {
                    Text(
                        "${hits.size} matching videos on ${progress.values.count { it.hits.isNotEmpty() }} of ${progress.size} sites",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            if (siteCount == 0 && !running) Text("No sites to search. Import your sites in My sites first (and keep \"Include in video search\" on).", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            // Results, best matches first
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                hits.forEach { h -> HitRow(h) { ctx.openBrowser(h.url) } }
            }
            if (progress.isNotEmpty()) {
                TextButton(onClick = { showSites = !showSites }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showSites) "Hide site details" else "Show each site's result")
                }
                if (showSites) Column(Modifier.padding(horizontal = 16.dp)) {
                    progress.values.sortedBy { it.site.title.lowercase() }.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.site.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(statusText(p.status), color = if (p.status is SiteStatus.Found) Accent else Muted, fontSize = 12.sp)
                            }
                            TextButton(onClick = { ctx.openBrowser(vm.repo.siteSearchUrl(p.site, s.title)) }) { Text("Open") }
                        }
                    }
                    Text(
                        "Sites that block apps or have no search page can still be opened by hand. Set a site's search link in My sites to improve results.",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            FilledIconButton(onClick = onBack, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0x99000000))) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

private fun statusText(s: SiteStatus) = when (s) {
    SiteStatus.Searching -> "Searching…"
    is SiteStatus.Found -> "${s.count} match${if (s.count == 1) "" else "es"}"
    SiteStatus.Nothing -> "No matching videos"
    is SiteStatus.Blocked -> "Site blocks app searches — tap Open"
    SiteStatus.NoSearchLink -> "No search link found — tap Open, or set one in My sites"
    is SiteStatus.Failed -> "Couldn't search (${s.reason})"
}

@Composable
private fun HitRow(h: Hit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).clickable(onClick = onClick)) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f)) {
            VImage(h.thumb, Modifier.fillMaxSize())
            h.duration?.let { Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { Badge(it, dark = true) } }
        }
        Column(Modifier.weight(1f).padding(10.dp)) {
            Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("${h.score}% match", dark = h.score < 70)
                Text(h.siteTitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
