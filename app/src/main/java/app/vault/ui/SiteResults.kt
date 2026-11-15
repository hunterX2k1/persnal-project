package app.vault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import app.vault.app
import app.vault.data.*

enum class FindSort(val label: String) { BEST("Best match"), LONGEST("Longest"), SHORTEST("Shortest"), FASTEST("Fastest site"), MOST_COPIES("Most copies"), SITE("Site A–Z") }
enum class FindLength(val label: String, val range: IntRange) { ANY("Any length", 0..Int.MAX_VALUE), SHORT("< 10 min", 1..599), MID("10–30 min", 600..1800), LONG("30 min +", 1801..Int.MAX_VALUE) }

/**
 * Results from my sites, shared by "Find all videos" and Search → My sites:
 * copies grouped, 1–4 column switch, search within results, Full only / length / site filters, sort, per-site details.
 * [header] lets a screen put its own controls at the top of the same scrolling grid.
 */
@Composable
fun SiteResultsPane(
    progress: Map<String, SiteProgress>,
    running: Boolean,
    siteCount: Int,
    cols: Int,
    onCols: (Int) -> Unit,
    openTerm: String,
    emptyTitle: String,
    emptyBody: String,
    header: LazyGridScope.(GridItemSpan) -> Unit = {},
) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    var q by rememberSaveable { mutableStateOf("") }
    var fullOnly by rememberSaveable { mutableStateOf(false) }
    var length by rememberSaveable { mutableStateOf(FindLength.ANY) }
    var sort by rememberSaveable { mutableStateOf(FindSort.BEST) }
    var site by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

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

    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(if (cols >= 3) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (cols >= 3) 6.dp else 10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val full = GridItemSpan(cols)
        header(full)
        item(span = { full }) {
            Column {
                Text(
                    when {
                        siteCount == 0 -> "No searchable sites yet"
                        running -> "Searching $done / $siteCount sites…"
                        progress.isEmpty() -> ""
                        else -> "${groups.size} of ${allGroups.size} videos · from ${progress.values.count { it.hits.isNotEmpty() }} sites · copies grouped, full versions first"
                    },
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
                if (running && siteCount > 0) LinearProgressIndicator(progress = { done / siteCount.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                if (allGroups.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            q, { q = it }, placeholder = { Text("Search these results") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.FilterList, null) },
                            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, null) } },
                            shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f),
                        )
                        ColumnPicker(cols, onCols)
                    }
                    LazyRow(contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            Box {
                                AssistChip(onClick = { sortMenu = true }, label = { Text(sort.label) }, leadingIcon = { Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp)) })
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    FindSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                                }
                            }
                        }
                        item { FilterChip(fullOnly, { fullOnly = !fullOnly }, label = { Text("Full only") }) }
                        items(FindLength.entries.drop(1)) { l -> FilterChip(length == l, { length = if (length == l) FindLength.ANY else l }, label = { Text(l.label) }) }
                        items(sitesWithResults) { s -> FilterChip(site == s, { site = if (site == s) null else s }, label = { Text(s) }) }
                    }
                }
            }
        }
        if (siteCount == 0) item(span = { full }) { EmptyState("No searchable sites", "Add video sites in My sites and scan them.") }
        if (!running && allGroups.isEmpty() && siteCount > 0 && progress.isNotEmpty()) item(span = { full }) { EmptyState(emptyTitle, emptyBody) }
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
                TextButton(onClick = { ctx.openBrowser(repo.siteSearchUrl(pr.site, openTerm)) }) { Text("Open") }
            }
        }
    }
}
