package app.vault.ui

import android.app.Application
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
        )
    ) { a -> FilterOptions(a[0], a[1], a[2], a[3], a[4], a[5], a[6]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilterOptions())

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun update(f: Filter) { filter.value = f; limit.value = PAGE }
    fun loadMore() { limit.value += PAGE }

    fun sync() = launchBusy { repo.syncStep() }
    fun searchOnline(q: String) {
        if (q.isBlank()) return
        launchBusy { "Found ${repo.searchOnline(q)} online" }
    }

    private fun launchBusy(block: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = runCatching { block() }.getOrElse { it.message ?: "Something went wrong" }
            busy.value = false
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
    val message by vm.message.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()

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
            // Search
            OutlinedTextField(
                value = filter.text,
                onValueChange = { vm.update(filter.copy(text = it)) },
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
                "${results.size}${if (results.size >= DiscoverVm.PAGE) "+" else ""} shown · $total in library",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            PullToRefreshBox(isRefreshing = busy, onRefresh = { vm.sync() }, modifier = Modifier.fillMaxSize()) {
                when {
                    vm.prefs.apiKey.isBlank() && total == 0 -> EmptyState(
                        "Connect the database", "Add your free ThePornDB API key in Settings to load performers.", "Open Settings", onSettings
                    )
                    total == 0 -> EmptyState("Library is empty", "Pull down or tap below to download the first batch of profiles.", "Sync now") { vm.sync() }
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

    if (showFilters) FilterSheet(filter, options, onApply = { vm.update(it); showFilters = false }, onDismiss = { showFilters = false })
}

@Composable
private fun QuickChip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })

fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v
