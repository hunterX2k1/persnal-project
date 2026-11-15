package app.vault.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
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
import app.vault.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FilterOptions(
    val genders: List<String> = emptyList(),
    val ethnicities: List<String> = emptyList(),
    val nationalities: List<String> = emptyList(),
    val hair: List<String> = emptyList(),
    val eyes: List<String> = emptyList(),
    val cups: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val zodiacs: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverVm(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).repo
    val prefs = (app as App).prefs
    val filter = MutableStateFlow(Filter())
    private val limit = MutableStateFlow(PAGE)

    val results = combine(filter, limit) { f, l -> f to l }
        .flatMapLatest { (f, l) -> repo.performers.query(f.toQuery(l)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites = repo.performers.favoriteIdsFlow().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val total = repo.performers.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @Suppress("UNCHECKED_CAST")
    val options = combine(
        listOf(
            repo.performers.genders(), repo.performers.ethnicities(), repo.performers.nationalities(),
            repo.performers.hairColors(), repo.performers.eyeColors(), repo.performers.cups(), repo.performers.allTags(),
            repo.performers.zodiacs(),
        )
    ) { a -> FilterOptions(a[0], a[1], a[2], a[3], a[4], a[5].sortedBy { c -> CUP_ORDER.indexOf(c).let { if (it < 0) 99 else it } }, a[6], a[7]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilterOptions())

    // ---- typing suggestions ----
    private var nameIndex: List<NameRow>? = null
    private var indexSize = -1
    val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val online = MutableStateFlow<List<Performer>>(emptyList())
    private var suggestJob: kotlinx.coroutines.Job? = null

    fun suggest(q: String) {
        suggestJob?.cancel()
        if (q.isBlank()) { suggestions.value = emptyList(); online.value = emptyList(); return }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            val idx = nameIndex?.takeIf { indexSize == total.value } ?: repo.performers.nameIndex().also { nameIndex = it; indexSize = total.value }
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NameSuggest.suggest(idx, q) }
            suggestions.value = local
            // Online: only when the library doesn't already have a strong match
            if (repo.hasKey && q.trim().length >= 3 && local.none { it.rank <= 2 }) {
                kotlinx.coroutines.delay(450)
                val have = local.map { it.row.id }.toSet()
                online.value = runCatching { repo.onlineSuggestions(q) }.getOrDefault(emptyList()).filter { it.id !in have }
            } else online.value = emptyList()
        }
    }

    fun openOnline(p: Performer, then: (String) -> Unit) = viewModelScope.launch { repo.saveFromOnline(p); nameIndex = null; then(p.id) }

    val saved = repo.saved.all.map { l -> l.filter { it.kind == "discover" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun saveFilter(name: String) = repo.saved.save(SavedSearch(name.trim(), "discover", filter.value.toJson()))
    fun applySaved(s: SavedSearch) = update(filterFromJson(s.data, Filter(favoritesOnly = filter.value.favoritesOnly)))
    fun deleteSaved(s: SavedSearch) = repo.saved.remove(s)

    private val localBusy = MutableStateFlow(false)
    val busy = combine(localBusy, repo.syncing) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val progress = repo.syncProgress
    val message = MutableStateFlow<String?>(null)

    fun update(f: Filter) { filter.value = f; limit.value = PAGE }
    fun loadMore() { limit.value += PAGE }

    fun sync() = repo.startSync { message.value = it }
    fun searchOnline(q: String) {
        if (q.isBlank()) return
        launchBusy { repo.searchOnline(q) }
    }

    private fun launchBusy(block: suspend () -> String) {
        if (localBusy.value) return
        viewModelScope.launch {
            localBusy.value = true
            message.value = attempt { block() }.getOrElse {
                AppLog.e("Discover", "Action failed", it)
                it.message ?: "Something went wrong"
            }
            localBusy.value = false
        }
    }

    companion object { const val PAGE = 60 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(key: String, favoritesOnly: Boolean, onOpen: (String) -> Unit, onPanic: () -> Unit, onSettings: () -> Unit) {
    val vm: DiscoverVm = viewModel(key = key)
    LaunchedEffect(favoritesOnly) {
        if (vm.filter.value.favoritesOnly != favoritesOnly) vm.update(vm.filter.value.copy(favoritesOnly = favoritesOnly))
    }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val savedFilters by vm.saved.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedSearch?>(null) }
    val snack = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var searchFocused by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val grid = rememberLazyGridState()

    // First run: fill the library straight away from the free database.
    LaunchedEffect(total) { if (total == 0 && !favoritesOnly) { kotlinx.coroutines.delay(800); if (vm.total.value == 0) vm.sync() } }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= results.size - 12 } }
    LaunchedEffect(nearEnd, results.size) { if (nearEnd && results.size >= DiscoverVm.PAGE) vm.loadMore() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Header
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (favoritesOnly) "Favourites" else "Discover", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = onPanic) { Icon(Icons.Default.VisibilityOff, "Quick hide", tint = Muted) }
            }
            // Search, with name suggestions as you type
            Box {
            OutlinedTextField(
                value = filter.text,
                onValueChange = { vm.update(filter.copy(text = it)); vm.suggest(it) },
                placeholder = { Text("Search by name") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (filter.text.isNotEmpty()) IconButton(onClick = { vm.update(filter.copy(text = "")) }) { Icon(Icons.Default.Close, null) }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.searchOnline(filter.text) }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Surface1, focusedContainerColor = Surface1, unfocusedBorderColor = Surface1),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .onFocusChanged { searchFocused = it.isFocused; if (it.isFocused) vm.suggest(filter.text) }
                    .onSizeChanged { fieldWidth = it.width },
            )
            DropdownMenu(
                expanded = searchFocused && filter.text.isNotBlank() && (suggestions.isNotEmpty() || online.isNotEmpty()),
                onDismissRequest = { searchFocused = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                offset = androidx.compose.ui.unit.DpOffset(16.dp, 0.dp),
                modifier = Modifier.width(with(density) { fieldWidth.toDp() } - 32.dp).heightIn(max = 420.dp),
            ) {
                suggestions.forEach { s ->
                    SuggestionRow(
                        name = s.row.name, typed = filter.text, image = s.row.thumb ?: s.row.image,
                        hint = when {
                            s.matchedAlias != null -> "aka ${s.matchedAlias}"
                            s.rank >= 5 -> "Did you mean?"
                            else -> null
                        },
                        onOpen = { focus.clearFocus(); searchFocused = false; onOpen(s.row.id) },
                        onComplete = { vm.update(filter.copy(text = s.row.name)); vm.suggest(s.row.name) },
                    )
                }
                if (online.isNotEmpty()) {
                    Text("From ThePornDB", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    online.forEach { p ->
                        SuggestionRow(
                            name = p.name, typed = filter.text, image = p.thumb ?: p.image, hint = "Not in your library yet",
                            onOpen = { focus.clearFocus(); searchFocused = false; vm.openOnline(p) { id -> onOpen(id) } },
                            onComplete = { vm.update(filter.copy(text = p.name)); vm.suggest(p.name) },
                        )
                    }
                }
            }
            }
            // Filter + sort bar and quick chips
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = filter.activeCount > 0, onClick = { showFilters = true },
                        label = { Text(if (filter.activeCount > 0) "Filters · ${filter.activeCount}" else "Filters") },
                        leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(18.dp)) },
                    )
                }
                item {
                    Box {
                        FilterChip(
                            selected = filter.sort != Sort.NAME, onClick = { showSort = true },
                            label = { Text(filter.sort.label) },
                            leadingIcon = { Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(showSort, onDismissRequest = { showSort = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.update(filter.copy(sort = s)); showSort = false },
                                    trailingIcon = { if (s == filter.sort) Icon(Icons.Default.Check, null) })
                            }
                        }
                    }
                }
                if (filter.activeCount > 0 || filter.sort != Sort.NAME) item {
                    AssistChip(onClick = { naming = true }, label = { Text("Save filter") }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp)) })
                }
                items(savedFilters, key = { "saved:" + it.name }) { s -> SavedChip(s.name, onApply = { vm.applySaved(s) }, onLongPress = { deleting = s }) }
                item { QuickChip("Active", filter.status == Status.ACTIVE) { vm.update(filter.copy(status = if (filter.status == Status.ACTIVE) Status.ANY else Status.ACTIVE)) } }
                item { QuickChip("Retired", filter.status == Status.RETIRED) { vm.update(filter.copy(status = if (filter.status == Status.RETIRED) Status.ANY else Status.RETIRED)) } }
                items(listOf(18 to 25, 26 to 35, 36 to MAX_AGE)) { (a, b) ->
                    val on = filter.ageFrom == a && filter.ageTo == b
                    QuickChip(if (b == MAX_AGE) "$a+" else "$a–$b", on) {
                        vm.update(if (on) filter.copy(ageFrom = MIN_AGE, ageTo = MAX_AGE) else filter.copy(ageFrom = a, ageTo = b))
                    }
                }
                item { QuickChip("New this year", filter.debutFrom == CURRENT_YEAR) { vm.update(filter.copy(debutFrom = if (filter.debutFrom == CURRENT_YEAR) MIN_DEBUT else CURRENT_YEAR)) } }
                items(options.tags) { t ->
                    QuickChip("#$t", t in filter.tags) { vm.update(filter.copy(tags = filter.tags.toggle(t))) }
                }
            }
            Text(
                progress ?: "${results.size}${if (results.size >= DiscoverVm.PAGE) "+" else ""} shown · $total in library",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            PullToRefreshBox(isRefreshing = busy, onRefresh = { vm.sync() }, modifier = Modifier.fillMaxSize()) {
                when {
                    total == 0 && busy -> EmptyState("Setting up your library", progress ?: "Downloading profiles…")
                    total == 0 -> EmptyState("Library is empty", "Tap below to download profiles. No account or key needed.", "Download now") { vm.sync() }
                    results.isEmpty() && filter.text.isNotBlank() -> EmptyState(
                        "No matches saved", "Search the online database for \"${filter.text}\"?", "Search online"
                    ) { vm.searchOnline(filter.text) }
                    results.isEmpty() -> EmptyState(
                        if (favoritesOnly) "No favourites yet" else "No matches",
                        if (favoritesOnly) "Tap the heart on a profile to save it here." else "Try loosening your filters.",
                        if (filter.activeCount > 0) "Reset filters" else null,
                    ) { vm.update(Filter(favoritesOnly = favoritesOnly, text = filter.text, sort = filter.sort)) }
                    else -> LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(results, key = { it.id }) { p -> PerformerCard(p, p.id in favs) { onOpen(p.id) } }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }

    if (naming) TextPrompt("Save filter", "e.g. Tall redheads", onDismiss = { naming = false }) { name ->
        if (name.isNotBlank()) vm.saveFilter(name); naming = false
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${s.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deleteSaved(s); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (showFilters) FilterSheet(filter, options, onApply = { vm.update(it); showFilters = false }, onDismiss = { showFilters = false })
}

/** One suggestion: photo, name with the typed part in bold, and a button that fills in the full name. */
@Composable
private fun SuggestionRow(name: String, typed: String, image: String?, hint: String?, onOpen: () -> Unit, onComplete: () -> Unit) {
    val t = typed.trim()
    val start = if (t.isEmpty()) -1 else name.indexOf(t, ignoreCase = true)
    val label = androidx.compose.ui.text.buildAnnotatedString {
        if (start < 0) append(name) else {
            append(name.substring(0, start))
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Accent)) { append(name.substring(start, start + t.length)) }
            append(name.substring(start + t.length))
        }
    }
    DropdownMenuItem(
        text = {
            Column {
                Text(label, maxLines = 1)
                if (hint != null) Text(hint, color = Muted, fontSize = 11.sp)
            }
        },
        leadingIcon = { VImage(image, Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)) },
        trailingIcon = { IconButton(onClick = onComplete) { Icon(Icons.Default.NorthWest, "Fill in name", tint = Muted) } },
        onClick = onOpen,
    )
}

/** A saved filter: tap to apply, long-press to delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SavedChip(name: String, onApply: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(onClick = onApply, onLongClick = onLongPress).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Bookmark, null, tint = Accent, modifier = Modifier.size(16.dp))
        Text(name, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun QuickChip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })

fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v
