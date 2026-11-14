package app.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.app
import app.atlas.data.PAGES
import app.atlas.data.PageInfo

@Composable
fun HomeScreen(onPage: (String) -> Unit, onSearch: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    val counts by repo.counts.collectAsState()
    val history by ctx.app.store.history.collectAsState()
    val open = LocalOpen.current
    // Warm everything up in the background so counts and search are ready.
    LaunchedEffect(Unit) { runCatching { repo.loadAll() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Atlas", fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp))
        Text(
            if (counts.isEmpty()) "Loading the FMHY index…" else "${counts.values.sum()} sites · from FMHY",
            color = Muted, fontSize = 13.sp,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp).clip(RoundedCornerShape(24.dp)).background(Surface1)
                .clickable(onClick = onSearch).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = Muted)
            Text("Search ${if (counts.isEmpty()) "" else "${counts.values.sum()} "}sites…", color = Muted, modifier = Modifier.padding(start = 10.dp))
        }

        if (history.isNotEmpty()) {
            Text("Recently opened", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history.take(15), key = { it.url }) { h ->
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Surface1).clickable { open(h.url, h.name) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(h.url, 18)
                        Text(h.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp))
                    }
                }
            }
        }

        PAGES.groupBy { it.group }.forEach { (group, pages) ->
            Text(group, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            pages.chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { p -> Tile(p, counts[p.slug], Modifier.weight(1f)) { onPage(p.slug) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(p: PageInfo, count: Int?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(96.dp).clip(RoundedCornerShape(14.dp))
            .background(if (p.slug == "unsafe") androidx.compose.ui.graphics.Color(0x22FF6B6B) else Surface1)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(p.emoji, fontSize = 24.sp)
        Column {
            Text(p.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count?.let { "$it sites" } ?: " ", color = Muted, fontSize = 11.sp)
        }
    }
}
