package app.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.app
import app.atlas.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(slug: String, anchor: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val store = ctx.app.store
    val info = PAGE_BY_SLUG[slug] ?: return
    var page by remember { mutableStateOf<WikiPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    val starredOnly by store.starredOnly.state.collectAsState()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun load(force: Boolean) {
        runCatching { repo.page(slug, force) }
            .onSuccess { page = it; error = null }
            .onFailure { AppLog.e("Page", "Load $slug failed", it); if (page == null) error = it.message }
    }
    LaunchedEffect(slug) { load(false) }
    LaunchedEffect(page, anchor) {
        val p = page ?: return@LaunchedEffect
        InternalLinks.findAnchor(p, anchor)?.let { list.scrollToItem(it) }
    }

    val shown: List<Item> = remember(page, filter, starredOnly) {
        val p = page ?: return@remember emptyList()
        if (filter.isBlank() && !starredOnly) return@remember p.items
        val q = filter.trim().lowercase()
        val out = ArrayList<Item>()
        var lastHeading: Heading? = null
        var headingAdded = false
        for (it in p.items) when (it) {
            is Heading -> { lastHeading = it; headingAdded = false }
            is Entry -> if (!it.note && (!starredOnly || it.rating > 0) &&
                (q.isEmpty() || it.name.lowercase().contains(q) || it.desc.lowercase().contains(q))) {
                if (!headingAdded && lastHeading != null) { out += lastHeading!!; headingAdded = true }
                out += it
            }
            else -> {}
        }
        out
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            if (searching) {
                OutlinedTextField(
                    filter, { filter = it }, placeholder = { Text("Filter this page") }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                    trailingIcon = { IconButton(onClick = { filter = ""; searching = false }) { Icon(Icons.Default.Close, null) } },
                )
            } else {
                Text("${info.emoji} ${info.title}", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Filter") }
            }
            IconButton(onClick = { showToc = true }, enabled = page != null) { Icon(Icons.AutoMirrored.Filled.List, "Sections") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(starredOnly, { store.starredOnly.value = !starredOnly }, label = { Text("⭐ Recommended only") })
            page?.let { Text("${it.entries.size} sites", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)) }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(true); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                page == null && error != null -> EmptyState("Couldn't load", error!!, "Try again") { scope.launch { load(true) } }
                page == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> EmptyState("Nothing matches", "Try a different word or turn off \"Recommended only\".")
                else -> LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.key }) { item ->
                        when (item) {
                            is Heading -> HeadingRow(item)
                            is Entry -> EntryCard(item)
                            is Para -> ParaRow(item)
                        }
                    }
                }
            }
        }
    }

    if (showToc) page?.let { p ->
        ModalBottomSheet(onDismissRequest = { showToc = false }, containerColor = Surface1) {
            Text("Sections", fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(p.headings.filter { it.level <= 3 }, key = { it.key }) { h ->
                    Text(
                        h.title,
                        fontWeight = if (h.level <= 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (h.level <= 1) Accent else androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showToc = false; filter = ""; searching = false; store.starredOnly.value = false
                            scope.launch { list.scrollToItem(p.items.indexOf(h).coerceAtLeast(0)) }
                        }.padding(start = (20 + (h.level - 1).coerceAtLeast(0) * 16).dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HeadingRow(h: Heading) {
    val open = LocalOpen.current
    val mod = if (h.link != null) Modifier.clickable { open(h.link, h.title) } else Modifier
    when {
        h.level <= 1 -> Row(mod.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
            Text(h.title, fontSize = 21.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 10.dp))
        }
        h.level == 2 -> Text(
            h.title + if (h.link != null) "  ›" else "", fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = if (h.link != null) Accent else androidx.compose.ui.graphics.Color.Unspecified,
            modifier = mod.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        else -> Text(h.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Muted, modifier = mod.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp))
    }
}

@Composable
private fun ParaRow(p: Para) {
    val mod = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    if (p.callout) Box(mod.clip(RoundedCornerShape(10.dp)).background(Surface2).padding(12.dp)) { MdText(p.md, color = androidx.compose.ui.graphics.Color(0xFFC9D3E6), fontSize = 13.sp) }
    else MdText(p.md, mod, fontSize = 14.sp)
}
