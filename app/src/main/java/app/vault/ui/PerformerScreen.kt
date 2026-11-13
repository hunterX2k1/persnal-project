package app.vault.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PerformerVm(app: Application, val id: String) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val performer = repo.performers.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favorite = repo.performers.isFavorite(id).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tags = repo.performers.tagsFor(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sites = repo.sites.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val scenes = MutableStateFlow<List<Scene>?>(null)
    val scenesError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val p = performer.filterNotNull().first()
            runCatching { repo.scenes(p) }
                .onSuccess { scenes.value = it }
                .onFailure { scenes.value = emptyList(); scenesError.value = it.message }
            runCatching { repo.refresh(id) }
        }
    }

    fun toggleFavorite() = viewModelScope.launch { repo.toggleFavorite(id, favorite.value) }
    fun addTag(t: String) = viewModelScope.launch { t.trim().takeIf { it.isNotEmpty() }?.let { repo.performers.addTag(Tag(id, it)) } }
    fun removeTag(t: String) = viewModelScope.launch { repo.performers.removeTag(id, t) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformerScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val vm: PerformerVm = viewModel(key = "p:$id", factory = viewModelFactory { initializer { PerformerVm(app, id) } })
    val p by vm.performer.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val scenes by vm.scenes.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    var showSites by remember { mutableStateOf(false) }
    var showTag by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var bioOpen by remember { mutableStateOf(false) }
    var sceneMenu by remember { mutableStateOf<Scene?>(null) }

    val perf = p ?: run {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Hero
            Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).aspectRatio(0.8f).clickable { reveal = !reveal }) {
                VImage(perf.image ?: perf.thumb, Modifier.fillMaxSize(), forceReveal = reveal)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Bg)))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(if (perf.isActive()) "ACTIVE" else "RETIRED", dark = !perf.isActive())
                        perf.age()?.let { Badge("$it yrs", dark = true) }
                        perf.careerText()?.let { Badge(it, dark = true) }
                    }
                    Text(perf.name, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))
                    perf.aliases?.let { Text("aka $it", color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                // Actions
                Button(
                    onClick = { showSites = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.TravelExplore, null); Spacer(Modifier.width(8.dp))
                    Text("Find on my sites", fontWeight = FontWeight.Bold)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.toggleFavorite() }, modifier = Modifier.weight(1f)) {
                        Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = Accent)
                        Spacer(Modifier.width(6.dp)); Text(if (fav) "Saved" else "Favourite")
                    }
                    OutlinedButton(onClick = { ctx.openBrowser(vm.repo.prefs.webSearchUrl(perf.name)) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Search web")
                    }
                }

                // Tags
                SectionTitle("My tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        InputChip(selected = true, onClick = { vm.removeTag(t) }, label = { Text(t) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                    }
                    AssistChip(onClick = { showTag = true }, label = { Text("Add tag") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
                }

                // Stats
                SectionTitle("Profile")
                val stats = listOf(
                    "Born" to perf.birthdayText(), "Age" to perf.age()?.toString(), "Gender" to perf.gender,
                    "Nationality" to perf.nationality, "Ethnicity" to perf.ethnicity, "Career" to perf.careerText(),
                    "Height" to perf.heightText(), "Weight" to perf.weightText(), "Hair" to perf.hair, "Eyes" to perf.eyes,
                    "Measurements" to perf.measurements, "Cup" to perf.cup, "Tattoos" to perf.tattoos, "Piercings" to perf.piercings,
                    "Scenes" to perf.sceneCount?.toString(),
                ).filter { it.second != null }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Surface1).padding(vertical = 4.dp)) {
                    stats.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text(k, color = Muted, modifier = Modifier.width(120.dp))
                            Text(v!!, modifier = Modifier.weight(1f))
                        }
                    }
                }

                perf.bio?.let { bio ->
                    SectionTitle("About")
                    Text(bio, color = Color(0xFFCFCFD4), maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { bioOpen = !bioOpen })
                }

                SectionTitle("Scenes")
            }
            when {
                scenes == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                scenes!!.isEmpty() -> Text("No scenes listed.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(scenes!!, key = { it.id }) { s -> SceneCard(s) { sceneMenu = s } }
                }
            }
            Spacer(Modifier.height(40.dp))
        }

        // Top bar over hero
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            FilledIconButton(onClick = onBack, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0x99000000))) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
    }

    if (showSites) FindOnSitesSheet(perf, scenes.orEmpty(), sites, vm.repo, onDismiss = { showSites = false })
    if (showTag) TextPrompt("Add tag", "e.g. top10, watch-later", onDismiss = { showTag = false }) { vm.addTag(it); showTag = false }
    sceneMenu?.let { s ->
        AlertDialog(
            onDismissRequest = { sceneMenu = null },
            title = { Text(s.title) },
            text = { Text(listOfNotNull(s.siteName, s.date, formatDuration(s.durationSec)).joinToString(" • ")) },
            confirmButton = {
                if (s.trailer != null) TextButton(onClick = { ctx.playVideo(s.trailer, s.title); sceneMenu = null }) { Text("Play preview") }
            },
            dismissButton = {
                if (s.url != null) TextButton(onClick = { ctx.openBrowser(s.url); sceneMenu = null }) { Text("Open on site") }
            },
        )
    }
}

@Composable
private fun SceneCard(s: Scene, onClick: () -> Unit) {
    Column(Modifier.width(240.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            VImage(s.image, Modifier.fillMaxSize())
            formatDuration(s.durationSec)?.let { Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { Badge(it, dark = true) } }
            if (s.trailer != null) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Text(listOfNotNull(s.siteName, s.date).joinToString(" • "), color = Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindOnSitesSheet(p: Performer, scenes: List<Scene>, sites: List<Site>, repo: Repo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val (confirmed, others) = remember(scenes, sites) { repo.matchSites(scenes, sites) }
    fun filt(l: List<Site>) = if (q.isBlank()) l else l.filter { it.title.contains(q, true) || it.domain.contains(q, true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Find ${p.name}", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("Tap a site to open it searching for this name.", color = Muted, fontSize = 13.sp)
            if (sites.isEmpty()) {
                EmptyState("No sites yet", "Import your HTML list of websites in the \"My sites\" tab first.")
                return@Column
            }
            OutlinedTextField(q, { q = it }, placeholder = { Text("Filter sites") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                val c = filt(confirmed); val o = filt(others)
                if (c.isNotEmpty()) {
                    item { Text("CONFIRMED · appeared here", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
                    items(c, key = { "c" + it.domain }) { s -> SiteRow(s, true) { ctx.openBrowser(repo.siteSearchUrl(s, p.name)) } }
                }
                item { Text("YOUR OTHER SITES", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                items(o, key = { "o" + it.domain }) { s -> SiteRow(s, false) { ctx.openBrowser(repo.siteSearchUrl(s, p.name)) } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun SiteRow(s: Site, confirmed: Boolean, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (confirmed) Accent else Surface2), Alignment.Center) {
            if (confirmed) Icon(Icons.Default.Check, null, tint = Color.Black)
            else Text(s.title.take(1).uppercase(), fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.domain + if (s.searchTemplate != null) " · custom search" else "", color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        trailing()
    }
}

@Composable
fun TextPrompt(title: String, hint: String, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, placeholder = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
