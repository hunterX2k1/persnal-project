package app.vault.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vault.App
import app.vault.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Words never offered as suggestions here (no youth-themed categories). */
private val NOT_SUGGESTED = listOf("teen", "school", "barely", "young", "underage", "minor", "child")

class CategoriesVm(app: Application) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val terms = MutableStateFlow<List<String>>(emptyList())
    val text = MutableStateFlow("")
    val scenes = MutableStateFlow<List<Scene>>(emptyList())
    val loading = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var page = 1
    private var lastPage = 1
    private var job: Job? = null
    val hasMore get() = page <= lastPage

    fun toggle(term: String) {
        terms.value = if (terms.value.any { it.equals(term, true) }) terms.value.filterNot { it.equals(term, true) } else terms.value + term
        reload()
    }
    fun setText(t: String) { text.value = t; reload() }
    fun clearAll() { terms.value = emptyList(); text.value = ""; scenes.value = emptyList(); message.value = null }

    fun reload() {
        job?.cancel(); page = 1; lastPage = 1; scenes.value = emptyList(); message.value = null
        if (terms.value.isEmpty() && text.value.isBlank()) return
        loadMore()
    }

    /** Loads pages until a screenful of matches is found (filtering can drop many results per page). */
    fun loadMore() {
        if (loading.value || !hasMore) return
        job = viewModelScope.launch {
            loading.value = true
            var found = 0
            var tries = 0
            while (hasMore && found < 20 && tries < 5) {
                tries++
                val r = runCatching { repo.browseScenes(text.value, terms.value, page) }
                    .onFailure { AppLog.e("Categories", "Browse failed", it); message.value = it.message }
                    .getOrNull() ?: run { if (!repo.hasKey) message.value = "Categories need the optional ThePornDB key (Settings)."; lastPage = 0; null } ?: break
                scenes.value = (scenes.value + r.scenes).distinctBy { it.id }
                found += r.scenes.size
                lastPage = r.lastPage; page++
            }
            if (scenes.value.isEmpty() && message.value == null) message.value = "No videos match all of these. Remove a tag or try other words."
            loading.value = false
        }
    }

    suspend fun suggestions(input: String): List<String> {
        val q = input.trim()
        if (q.length < 2) return emptyList()
        val lc = q.lowercase()
        val seen = scenes.value.flatMap { it.tags + listOfNotNull(it.siteName) + it.performers.map { p -> p.name } }
        val names = runCatching { repo.performers.namesStarting(q) }.getOrDefault(emptyList())
        return (Repo.Terms.RELATIONS + names + seen)
            .distinctBy { it.lowercase() }
            .filter { it.lowercase().contains(lc) && NOT_SUGGESTED.none { w -> it.lowercase().contains(w) } }
            .filterNot { s -> terms.value.any { it.equals(s, true) } }
            .sortedBy { if (it.lowercase().startsWith(lc)) 0 else 1 }
            .take(8)
    }
}

enum class CatSort(val label: String) { LATEST("Latest"), OLDEST("Oldest"), LONGEST("Longest"), TITLE("Title A–Z") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(onScene: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm: CategoriesVm = viewModel()
    val terms by vm.terms.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val scenes by vm.scenes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val sites by vm.repo.sites.all().collectAsState(initial = emptyList())
    val archived = sites.filter { it.domain.startsWith("web.archive.org/") }
    var input by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var sort by remember { mutableStateOf(CatSort.LATEST) }
    var sortMenu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()

    LaunchedEffect(input) { delay(150); suggestions = vm.suggestions(input) }
    val shown = remember(scenes, sort) {
        when (sort) {
            CatSort.LATEST -> scenes.sortedByDescending { it.date.orEmpty() }
            CatSort.OLDEST -> scenes.sortedBy { it.date ?: "9999" }
            CatSort.LONGEST -> scenes.sortedByDescending { it.durationSec ?: 0 }
            CatSort.TITLE -> scenes.sortedBy { it.title.lowercase() }
        }
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shown.size - 6 } }
    LaunchedEffect(nearEnd, scenes.size) { if (nearEnd && scenes.isNotEmpty() && vm.hasMore) vm.loadMore() }

    fun submit() {
        val t = input.trim()
        if (t.isEmpty()) return
        val exact = suggestions.firstOrNull { it.equals(t, true) }
        if (exact != null) vm.toggle(exact) else vm.setText(t)
        input = ""; suggestions = emptyList(); focus.clearFocus()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Categories", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, top = 8.dp))

        // Top buttons, like the old site: Latest · Random · archived copies
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AssistChip(onClick = { sort = CatSort.LATEST }, label = { Text("Latest") }, leadingIcon = { Icon(Icons.Default.NewReleases, null, Modifier.size(16.dp)) }) }
            item {
                AssistChip(enabled = scenes.isNotEmpty(), onClick = { scenes.randomOrNull()?.let { onScene(it.id) } },
                    label = { Text("Random") }, leadingIcon = { Icon(Icons.Default.Shuffle, null, Modifier.size(16.dp)) })
            }
            items(archived, key = { it.domain }) { s ->
                AssistChip(onClick = { ctx.openBrowser(s.url) }, label = { Text(s.title) }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) })
            }
        }

        // One box for everything: tags, pornstars, studios, relations — or your own words.
        Box(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text("Search tags, pornstars, studios, relations…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = { input = "" }) { Icon(Icons.Default.Close, null) } },
                singleLine = true, shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Surface1, focusedContainerColor = Surface1, unfocusedBorderColor = Surface1),
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = suggestions.isNotEmpty() && input.isNotBlank(), onDismissRequest = { suggestions = emptyList() },
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                suggestions.forEach { sug ->
                    DropdownMenuItem(text = { Text(sug) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { vm.toggle(sug); input = ""; suggestions = emptyList() })
                }
                DropdownMenuItem(text = { Text("Search for \"${input.trim()}\"") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    onClick = { vm.setText(input.trim()); input = ""; suggestions = emptyList(); focus.clearFocus() })
            }
        }

        // Active filters (stacked) + clear
        if (terms.isNotEmpty() || text.isNotBlank()) FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (text.isNotBlank()) InputChip(true, { vm.setText("") }, label = { Text("“$text”") }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
            terms.forEach { t -> InputChip(true, { vm.toggle(t) }, label = { Text(t) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }) }
            TextButton(onClick = { vm.clearAll() }) { Text("Clear") }
        }

        // Relations: one tap to add/remove
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Repo.Terms.RELATIONS) { r -> FilterChip(terms.any { it.equals(r, true) }, { vm.toggle(r) }, label = { Text(r) }) }
        }

        if (scenes.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${scenes.size}${if (vm.hasMore) "+" else ""} videos", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp)); Text(sort.label) }
                DropdownMenu(sortMenu, { sortMenu = false }) { CatSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) } }
            }
        }

        when {
            terms.isEmpty() && text.isBlank() -> EmptyState("Pick a category", "Tap a relation above, or type tags, pornstars, studios or any words. Add more to narrow it down.")
            scenes.isEmpty() && loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            scenes.isEmpty() -> EmptyState("Nothing yet", message ?: "")
            else -> LazyVerticalGrid(
                state = grid, columns = GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { s -> CoverCard(s) { onScene(s.id) } }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            }
        }
    }
}

/** Old-site style card: cover image with the title over it. */
@Composable
private fun CoverCard(s: Scene, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Surface1).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            VImage(s.image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().height(60.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))))
            Text(s.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            formatDuration(s.durationSec)?.let { Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Badge(it, dark = true) } }
        }
        Text(
            listOfNotNull(s.performers.take(2).joinToString(", ") { it.name }.ifBlank { null }, s.siteName).joinToString(" · "),
            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
