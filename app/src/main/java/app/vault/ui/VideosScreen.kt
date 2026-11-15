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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class VideoSort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), TITLE("Title A–Z"), LONGEST("Longest") }

class VideosVm(app: Application, val performerId: String) : AndroidViewModel(app) {
    private val repo = (app as App).repo
    val performer = MutableStateFlow<Performer?>(null)
    val scenes = MutableStateFlow<List<Scene>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val total = MutableStateFlow<Int?>(null)
    private var nextPage = 1
    private var lastPage = Int.MAX_VALUE
    val hasMore get() = nextPage <= lastPage

    init { viewModelScope.launch { performer.value = repo.performers.get(performerId); loadMore() } }

    fun loadMore() {
        val p = performer.value ?: return
        if (loading.value || !hasMore) return
        viewModelScope.launch {
            loading.value = true
            attempt { repo.scenes(p, nextPage) }
                .onSuccess { r ->
                    if (r == null) { error.value = "Video lists need the optional ThePornDB key (Settings)."; lastPage = 0; return@onSuccess }
                    scenes.value = (scenes.value + r.scenes).distinctBy { it.id }
                    lastPage = r.lastPage; total.value = r.total ?: total.value; nextPage++
                    if (r.scenes.isEmpty()) lastPage = 0
                }
                .onFailure { AppLog.e("Videos", "Loading page $nextPage failed", it); error.value = it.message }
            loading.value = false
        }
    }
}

@Composable
fun VideosScreen(performerId: String, onBack: () -> Unit, onScene: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: VideosVm = viewModel(key = "v:$performerId", factory = viewModelFactory { initializer { VideosVm(app, performerId) } })
    val p by vm.performer.collectAsStateWithLifecycle()
    val all by vm.scenes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(VideoSort.NEWEST) }
    var previewOnly by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val prefs = LocalContext.current.app.prefs
    val cols = prefs.colsVideos

    val topTags = remember(all) { all.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).map { it.key } }
    val shown = remember(all, q, tag, sort, previewOnly) {
        all.filter { s ->
            (q.isBlank() || s.title.contains(q, true) || s.siteName?.contains(q, true) == true || s.performers.any { it.name.contains(q, true) }) &&
                (tag == null || tag in s.tags) && (!previewOnly || s.trailer != null)
        }.let { l ->
            when (sort) {
                VideoSort.NEWEST -> l.sortedByDescending { it.date.orEmpty() }
                VideoSort.OLDEST -> l.sortedBy { it.date ?: "9999" }
                VideoSort.TITLE -> l.sortedBy { it.title.lowercase() }
                VideoSort.LONGEST -> l.sortedByDescending { it.durationSec ?: 0 }
            }
        }
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shown.size - 6 } }
    LaunchedEffect(nearEnd, all.size) { if (nearEnd && vm.hasMore) vm.loadMore() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(p?.name ?: "Videos", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text("${all.size}${total?.let { " of $it" } ?: if (vm.hasMore) "+" else ""} videos", color = Muted, fontSize = 12.sp)
            }
            ColumnPicker(cols) { prefs.colsVideos = it }
            Box {
                TextButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, null); Text(sort.label) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    VideoSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                }
            }
        }
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("Search titles, studios, co-stars") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(previewOnly, { previewOnly = !previewOnly }, label = { Text("Has preview") }) }
            items(topTags) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, label = { Text(t) }) }
        }
        when {
            all.isEmpty() && loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            all.isEmpty() && error != null -> EmptyState("No videos", error!!)
            shown.isEmpty() && !loading -> EmptyState("No matches", "Try another word or tag.")
            else -> LazyVerticalGrid(
                state = grid, columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(if (cols >= 3) 6.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (cols >= 3) 8.dp else 12.dp),
            ) {
                items(shown, key = { it.id }) { s -> SceneCard(s, Modifier.fillMaxWidth()) { onScene(s.id) } }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            }
        }
    }
}
