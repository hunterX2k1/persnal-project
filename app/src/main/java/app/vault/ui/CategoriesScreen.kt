package app.vault.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoriesVm(app: Application) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val all = MutableStateFlow(repo.categories.load())
    /** First pick drives the search; the rest narrow it down. */
    val picked = MutableStateFlow<List<Category>>(emptyList())
    val text = MutableStateFlow("")
    val scenes = MutableStateFlow<List<Scene>>(emptyList())
    val loading = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var page = 1
    private var lastPage = 1
    private var job: Job? = null
    val hasMore get() = page <= lastPage

    fun toggle(c: Category) {
        picked.value = if (picked.value.any { it.code == c.code }) picked.value.filterNot { it.code == c.code } else picked.value + c
        reload()
    }
    fun setText(t: String) { text.value = t.trim(); reload() }
    fun clear() { picked.value = emptyList(); text.value = ""; reload() }

    private var generation = 0

    fun reload() {
        job?.cancel()
        generation++
        loading.value = false
        page = 1; lastPage = 1; scenes.value = emptyList(); message.value = null
        loadMore()
    }

    /** Fetches pages until a screenful of matches is found. Nothing picked = latest videos. */
    fun loadMore() {
        if (loading.value || !hasMore) return
        val gen = generation
        job = viewModelScope.launch {
            loading.value = true
            try {
                var found = 0; var tries = 0
                while (hasMore && found < 18 && tries < 6 && gen == generation) {
                    tries++
                    val main = picked.value.firstOrNull()
                    val r = attempt { repo.browseCategory(main, picked.value.drop(1), text.value, page) }
                        .onFailure { AppLog.e("Categories", "Browse failed", it); if (gen == generation) message.value = it.message }
                        .getOrNull()
                    if (gen != generation) return@launch
                    if (r == null) { if (!repo.hasKey) message.value = "Categories use the ThePornDB key — add it in Settings."; lastPage = 0; break }
                    scenes.value = (scenes.value + r.scenes).distinctBy { it.id }
                    found += r.scenes.size
                    lastPage = r.lastPage; page++
                }
                if (gen == generation && scenes.value.isEmpty() && message.value == null) message.value = "No videos matched. Remove a category or try other words."
            } finally {
                if (gen == generation) loading.value = false
            }
        }
    }

    val saved = repo.saved.all
    fun saveSearch(name: String) = repo.saved.save(SavedSearch(name.trim(), "categories", categorySearchJson(picked.value.map { it.code }, text.value)))
    fun applySaved(s: SavedSearch) {
        val (codes, t) = categorySearchFrom(s.data)
        picked.value = codes.mapNotNull { c -> (Categories.MOODS + all.value).firstOrNull { it.code == c } }
        text.value = t
        reload()
    }
    fun deleteSaved(s: SavedSearch) = repo.saved.remove(s)

    init { reload() } // start with the latest videos

    /** Adds the categories the user ticked after importing a saved page. */
    fun addAll(list: List<Category>): Int {
        val merged = (all.value + list).distinctBy { it.code.lowercase() }
        val added = merged.size - all.value.size
        all.value = merged; repo.categories.save(merged)
        AppLog.i("Categories", "Added $added categories from a saved page")
        return added
    }

    fun add(label: String, words: String) {
        val c = Categories.fromLabel(label.trim(), label.trim(), "Mine")?.let { base ->
            if (words.isBlank()) base else base.copy(queries = listOf(words.trim()), words = SiteSearch.tokens(words).toList())
        } ?: return
        all.value = all.value.filterNot { it.code.equals(c.code, true) } + c
        repo.categories.save(all.value)
    }

    fun resetList() { repo.categories.reset(); all.value = repo.categories.load() }
}

private enum class CatSort(val label: String) { LATEST("Latest"), OLDEST("Oldest"), LONGEST("Longest"), TITLE("Title A–Z") }
private enum class Length(val label: String, val range: IntRange) { ANY("Any length", 0..Int.MAX_VALUE), SHORT("< 15 min", 1..899), MID("15–40 min", 900..2400), LONG("40 min +", 2401..Int.MAX_VALUE) }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onScene: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm: CategoriesVm = viewModel()
    val all by vm.all.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val scenes by vm.scenes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val sites by vm.repo.sites.all().collectAsState(initial = emptyList())
    val archived = sites.filter { it.domain.startsWith("web.archive.org/") }
    var input by rememberSaveable { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<Categories.Imported?>(null) }
    val savedAll by vm.saved.collectAsStateWithLifecycle()
    val savedSearches = savedAll.filter { it.kind == "categories" }
    val recent by vm.repo.taste.recent.collectAsStateWithLifecycle()
    val later by vm.repo.taste.later.collectAsStateWithLifecycle()
    fun openSaved(s: Scene) { vm.repo.sceneCache[s.id] = s; onScene(s.id) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedSearch?>(null) }
    var sort by rememberSaveable { mutableStateOf(CatSort.LATEST) }
    var length by rememberSaveable { mutableStateOf(Length.ANY) }
    var refine by rememberSaveable { mutableStateOf("") }
    var previewOnly by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
            val got = raw?.let { withContext(Dispatchers.Default) { runCatching { Categories.importFrom(it) }.getOrNull() } }
            when {
                got == null -> snack.showSnackbar("Couldn't read that file")
                got.found.isEmpty() -> snack.showSnackbar("No category links found on that page. Save the site's categories or tags page and try again.")
                else -> importing = got
            }
        }
    }

    val suggestions = remember(input, all) {
        val q = input.trim().lowercase()
        if (q.length < 2) emptyList() else all.filter { it.label.lowercase().contains(q) || it.code.lowercase() == q }.take(8)
    }
    val shown = remember(scenes, sort, length, refine, previewOnly) {
        scenes.filter { s ->
            (length == Length.ANY || (s.durationSec ?: 0) in length.range) && (!previewOnly || s.trailer != null) &&
                (refine.isBlank() || SiteSearch.tokens(refine).all { w -> (s.title + " " + s.performers.joinToString(" ") { it.name } + " " + s.siteName).lowercase().contains(w) })
        }.let { l ->
            when (sort) {
                CatSort.LATEST -> l.sortedByDescending { it.date.orEmpty() }
                CatSort.OLDEST -> l.sortedBy { it.date ?: "9999" }
                CatSort.LONGEST -> l.sortedByDescending { it.durationSec ?: 0 }
                CatSort.TITLE -> l.sortedBy { it.title.lowercase() }
            }
        }
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shown.size - 6 } }
    LaunchedEffect(nearEnd, scenes.size) { if (nearEnd && scenes.isNotEmpty() && vm.hasMore) vm.loadMore() }

    fun submit() {
        val t = input.trim(); if (t.isEmpty()) return
        val exact = all.firstOrNull { it.label.equals(t, true) || it.code.equals(t, true) }
        if (exact != null) vm.toggle(exact) else vm.setText(t)
        input = ""; focus.clearFocus()
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = grid, columns = GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Categories", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Import categories from a saved site") }, leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                                    onClick = { menu = false; importer.launch(arrayOf("text/html", "multipart/related", "message/rfc822", "*/*")) })
                                DropdownMenuItem(text = { Text("Add my own category") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menu = false; adding = true })
                                DropdownMenuItem(text = { Text("Reset category list") }, leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { menu = false; vm.resetList() })
                            }
                        }
                    }
                    // Quick buttons, like the old site
                    LazyRow(contentPadding = PaddingValues(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { AssistChip(onClick = { showAll = true }, label = { Text("All ${all.size} categories") }, leadingIcon = { Icon(Icons.Default.Category, null, Modifier.size(16.dp)) }) }
                        item { AssistChip(enabled = scenes.isNotEmpty(), onClick = { scenes.randomOrNull()?.let { onScene(it.id) } }, label = { Text("Random") }, leadingIcon = { Icon(Icons.Default.Shuffle, null, Modifier.size(16.dp)) }) }
                        items(archived, key = { it.domain }) { s ->
                            AssistChip(onClick = { ctx.openBrowser(s.url) }, label = { Text(s.title) }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) })
                        }
                    }
                    // One box: categories by name, or any words
                    OutlinedTextField(
                        input, { input = it },
                        placeholder = { Text("Search categories, pornstars, studios or any words") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = { input = "" }) { Icon(Icons.Default.Close, null) } },
                        singleLine = true, shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Surface1, focusedContainerColor = Surface1, unfocusedBorderColor = Surface1),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                    if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { c -> SuggestionChip(onClick = { vm.toggle(c); input = "" }, label = { Text(c.label) }, icon = { Icon(Icons.Default.Add, null, Modifier.size(14.dp)) }) }
                        SuggestionChip(onClick = { vm.setText(input); input = ""; focus.clearFocus() }, label = { Text("Words: \"${input.trim()}\"") })
                    }
                    // What's applied (stackable)
                    if (picked.isNotEmpty() || text.isNotBlank()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { naming = true }, label = { Text("Save") }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null, Modifier.size(16.dp)) })
                        if (text.isNotBlank()) InputChip(true, { vm.setText("") }, label = { Text("“$text”") }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                        picked.forEachIndexed { i, c -> InputChip(true, { vm.toggle(c) }, label = { Text((if (i == 0) "" else "+ ") + c.label) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }) }
                        TextButton(onClick = { vm.clear() }) { Text("Clear") }
                    }
                    if (savedSearches.isNotEmpty()) LazyRow(contentPadding = PaddingValues(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(savedSearches, key = { it.name }) { s -> SavedChip(s.name, onApply = { vm.applySaved(s) }, onLongPress = { deleting = s }) }
                    }
                    // Moods, recently viewed and watch later (home view)
                    if (picked.isEmpty() && text.isBlank()) {
                        Text("Moods", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Categories.MOODS, key = { it.code }) { m ->
                                val forYou = m.code == Categories.FOR_YOU
                                Column(
                                    Modifier.width(150.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (forYou) MaterialTheme.colorScheme.primaryContainer else Surface1)
                                        .clickable {
                                            if (forYou && !vm.repo.taste.hasTaste) scope.launch { snack.showSnackbar("Open a few videos first — For you learns from what you watch") }
                                            else vm.toggle(m)
                                        }.padding(12.dp),
                                ) {
                                    Text(if (forYou) "✨" else moodEmoji(m.code), fontSize = 22.sp)
                                    Text(m.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, minLines = 2)
                                }
                            }
                        }
                        if (later.isNotEmpty()) SceneRow("Watch later", later) { openSaved(it) }
                        if (recent.isNotEmpty()) SceneRow("Recently viewed", recent) { openSaved(it) }
                    }
                    // Popular pairs, one tap
                    if (picked.isEmpty() && text.isBlank()) {
                        Text("Popular", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            all.filter { it.group == "Pairs" }.take(14).forEach { c -> FilterChip(false, { vm.toggle(c) }, label = { Text(c.label) }) }
                        }
                    }
                    // Refine results
                    if (scenes.isNotEmpty()) {
                        OutlinedTextField(refine, { refine = it }, placeholder = { Text("Filter these results") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.FilterList, null) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        LazyRow(contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CatSort.entries) { s -> FilterChip(sort == s, { sort = s }, label = { Text(s.label) }) }
                            item { FilterChip(previewOnly, { previewOnly = !previewOnly }, label = { Text("Has preview") }) }
                            items(Length.entries) { l -> FilterChip(length == l, { length = l }, label = { Text(l.label) }) }
                        }
                        Text((if (picked.isEmpty() && text.isBlank()) "Latest videos · " else "") + "${shown.size}${if (vm.hasMore) "+" else ""} · swipe a thumbnail to preview", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            when {
                scenes.isEmpty() && loading -> item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
                scenes.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Nothing yet", message ?: "") }
                else -> {
                    items(shown, key = { it.id }) { s -> CoverCard(s) { onScene(s.id) } }
                    if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }

    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }, containerColor = Surface1) {
        var q by remember { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("All categories", fontSize = 20.sp, fontWeight = FontWeight.Black)
            OutlinedTextField(q, { q = it }, placeholder = { Text("Find a category") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                all.filter { q.isBlank() || it.label.contains(q, true) || it.code.contains(q, true) }.groupBy { it.group }.forEach { (group, list) ->
                    Text(group, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { c ->
                            FilterChip(picked.any { it.code == c.code }, { vm.toggle(c) },
                                label = { Text(if (c.code.all { it.isUpperCase() } && c.code.length <= 6) "${c.code} · ${c.label}" else c.label) })
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (naming) TextPrompt("Save this search", "e.g. My favourites", onDismiss = { naming = false }) { n -> if (n.isNotBlank()) vm.saveSearch(n); naming = false }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${s.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deleteSaved(s); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    importing?.let { imp ->
        val existing = remember(all) { all.map { it.code.lowercase() }.toSet() }
        val fresh = imp.found.filter { it.code.lowercase() !in existing }
        var chosen by remember(imp) { mutableStateOf(fresh.map { it.code }.toSet()) }
        var q by remember(imp) { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { importing = null }, containerColor = Surface1, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Choose categories", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    "Found ${imp.found.size} on that page" +
                        (if (imp.found.size > fresh.size) " · ${imp.found.size - fresh.size} you already have" else "") +
                        (if (imp.hidden > 0) " · ${imp.hidden} not allowed and left out" else ""),
                    color = Muted, fontSize = 13.sp,
                )
                OutlinedTextField(q, { q = it }, placeholder = { Text("Find") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                val list = fresh.filter { q.isBlank() || it.label.contains(q, true) || it.code.contains(q, true) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { chosen = chosen + list.map { it.code } }) { Text("Select all") }
                    TextButton(onClick = { chosen = chosen - list.map { it.code }.toSet() }) { Text("Select none") }
                    Spacer(Modifier.weight(1f))
                    Text("${chosen.size} chosen", color = Muted, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f, fill = false).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (fresh.isEmpty()) Text("You already have every category from this page.", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                    list.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (c.code in chosen) chosen - c.code else chosen + c.code }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.code in chosen, { chosen = if (c.code in chosen) chosen - c.code else chosen + c.code })
                            Column(Modifier.weight(1f)) {
                                Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.group} · searches \"${c.queries.last()}\"", color = Muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
                Button(
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        val n = vm.addAll(fresh.filter { it.code in chosen })
                        importing = null
                        scope.launch { snack.showSnackbar("Added $n categories") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(50.dp),
                ) { Text("Add ${chosen.size} categories", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (adding) {
        var label by remember { mutableStateOf("") }
        var words by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add my own category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(words, { words = it }, label = { Text("Search words (optional)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { if (Categories.isExcluded(label, words)) { scope.launch { snack.showSnackbar("That category isn't allowed") } } else vm.add(label, words); adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

private fun moodEmoji(code: String) = when (code) {
    "mood-mature-younger" -> "🍷"; "mood-gilf" -> "🌹"; "mood-cougar" -> "🐆"; "mood-stepmom" -> "🏠"
    "mood-romantic" -> "💞"; "mood-intimate" -> "🕯️"; "mood-sensual" -> "🌙"; "mood-fetish" -> "⛓️"; else -> "🎬"
}

@Composable
private fun SceneRow(title: String, scenes: List<Scene>, onOpen: (Scene) -> Unit) {
    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(scenes.take(20), key = { title + it.id }) { s -> Box(Modifier.width(200.dp)) { CoverCard(s, scrub = false) { onOpen(s) } } }
    }
}

/** Old-site style card: cover (swipe to preview) with the title over it. */
@Composable
private fun CoverCard(s: Scene, scrub: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Surface1)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            PreviewThumb(s.id, listOf(s.thumb) + s.imageFallbacks, s.trailer, Modifier.fillMaxSize(), scrub = scrub, onClick = onClick)
            Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            Text(s.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 26.dp, end = 6.dp, bottom = 6.dp))
            formatDuration(s.durationSec)?.let { Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Badge(it, dark = true) } }
        }
        Text(
            listOfNotNull(s.performers.take(2).joinToString(", ") { it.name }.ifBlank { null }, s.siteName).joinToString(" · "),
            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
