package app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import app.vault.data.Hit
import app.vault.data.HitGroup
import app.vault.data.HitGroups
import app.vault.data.SiteStatus

fun statusText(s: SiteStatus) = when (s) {
    SiteStatus.Searching -> "Searching…"
    is SiteStatus.Found -> "${s.count} match${if (s.count == 1) "" else "es"}"
    SiteStatus.Nothing -> "No matching videos"
    is SiteStatus.Blocked -> "Site blocks app searches — tap Open"
    SiteStatus.NoSearchLink -> "No search page of its own — searched through web search instead"
    is SiteStatus.Failed -> "Couldn't search (${s.reason})"
}

@Composable
fun FullBadge(h: Hit) = when (HitGroups.isFull(h)) {
    true -> Badge("FULL")
    false -> Badge("CLIP", dark = true)
    null -> {}
}

/** One video found on my sites: best copy on top (full version, fastest site), other copies underneath. */
@Composable
fun GroupCard(g: HitGroup) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val h = g.best
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1)) {
        Row(Modifier.fillMaxWidth().clickable { ctx.openBrowser(h.url) }) {
            Box(Modifier.width(160.dp).aspectRatio(16f / 9f)) {
                VImage(listOf(h.thumb) + g.others.map { it.thumb }, Modifier.fillMaxSize())
                h.duration?.let { Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { Badge(it, dark = true) } }
            }
            Column(Modifier.weight(1f).padding(10.dp)) {
                Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FullBadge(h)
                    Badge("${g.all.maxOf { it.score }}%", dark = true)
                    Text(h.siteTitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (g.others.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+${g.others.size} other cop${if (g.others.size == 1) "y" else "ies"}", color = Accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Accent)
            }
            if (open) g.others.forEach { o ->
                Row(Modifier.fillMaxWidth().clickable { ctx.openBrowser(o.url) }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FullBadge(o)
                    Text(o.siteTitle, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    o.duration?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    if (o.ms > 0) Text("${(o.ms / 100) / 10.0}s", color = if (o.ms < 1500) Color(0xFF3DDC84) else Muted, fontSize = 11.sp)
                }
            }
        }
    }
}
