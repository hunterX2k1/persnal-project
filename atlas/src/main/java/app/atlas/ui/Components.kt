package app.atlas.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.app
import app.atlas.data.Entry
import app.atlas.data.PAGE_BY_SLUG
import coil.compose.AsyncImage

/** How the app opens any link: FMHY-internal links navigate, everything else opens in the browser. */
val LocalOpen = staticCompositionLocalOf<(url: String, title: String) -> Unit> { { _, _ -> } }

fun Context.openBrowser(url: String, title: String = "") {
    app.store.addHistory(title.ifBlank { Uri.parse(url).host.orEmpty() }, url)
    startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))
}

fun Context.copy(text: String, label: String = "Copied") {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: String, subject: String = "") {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), "Share"))
}

fun Context.openExternal(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

private val INLINE = Regex("\\[([^\\]]*)\\]\\(([^)\\s]+)\\)|\\*\\*([^*]+)\\*\\*|`([^`]+)`|\\*([^*\\s][^*]*)\\*")

/** Renders FMHY inline Markdown (links, bold, code, italics) with tappable links. */
@Composable
fun MdText(md: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, maxLines: Int = Int.MAX_VALUE) {
    val open = LocalOpen.current
    val text = remember(md) {
        buildAnnotatedString {
            var i = 0
            for (m in INLINE.findAll(md)) {
                append(md.substring(i, m.range.first))
                val g = m.groupValues
                when {
                    g[2].isNotEmpty() -> {
                        val url = g[2]; val label = g[1].replace("**", "").ifBlank { url }
                        withLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = Accent))) { open(url, label) }) { append(label) }
                    }
                    g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[3]) }
                    g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2)) { append(g[4]) }
                    else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(g[5]) }
                }
                i = m.range.last + 1
            }
            append(md.substring(i))
        }
    }
    Text(text, modifier = modifier, color = color, fontSize = fontSize, lineHeight = fontSize * 1.4, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Favicon(url: String?, size: Int = 22) {
    val host = remember(url) { url?.let { runCatching { Uri.parse(it).host }.getOrNull() } }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(Surface2), Alignment.Center) {
        if (host != null) AsyncImage(
            model = "https://icons.duckduckgo.com/ip3/$host.ico", contentDescription = null,
            modifier = Modifier.size((size - 4).dp),
        ) else Icon(Icons.Default.Link, null, tint = Muted, modifier = Modifier.size((size - 6).dp))
    }
}

@Composable
fun RatingBadge(rating: Int) {
    if (rating == 0) return
    val (label, bg) = if (rating == 2) "🌟 Top pick" to Color(0x33FFC94D) else "⭐ Recommended" to Color(0x228AB4FF)
    Text(
        label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (rating == 2) Gold else Accent,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun EntryCard(e: Entry, showPlace: Boolean = false) {
    val ctx = LocalContext.current
    val open = LocalOpen.current
    val store = ctx.app.store
    val bookmarks by store.bookmarks.collectAsState()
    val saved = e.url != null && bookmarks.any { it.url == e.url }
    var menu by remember { mutableStateOf(false) }

    if (e.note) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0x1A8AB4FF)).padding(12.dp)
        ) {
            Icon(Icons.Outlined.Info, null, tint = Accent, modifier = Modifier.size(18.dp))
            MdText(e.desc, Modifier.padding(start = 8.dp), color = Color(0xFFC9D3E6), fontSize = 13.sp)
        }
        return
    }

    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1)
                .combinedClickable(onClick = { e.url?.let { open(it, e.name) } }, onLongClick = { menu = true })
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) { Favicon(e.url) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    RatingBadge(e.rating)
                }
                if (showPlace) Text(
                    listOfNotNull(PAGE_BY_SLUG[e.page]?.let { "${it.emoji} ${it.title}" }, e.section.substringAfterLast(" › ").takeIf { it.isNotBlank() }).joinToString(" › "),
                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (e.desc.isNotBlank()) MdText(e.desc, Modifier.padding(top = 2.dp), color = Muted, fontSize = 13.sp, maxLines = 4)
                if (e.alts.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    e.alts.forEach { l ->
                        Text(
                            if (l.text.all(Char::isDigit)) "Mirror ${l.text}" else l.text,
                            fontSize = 12.sp, color = Accent,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Surface2)
                                .combinedClickable(onClick = { open(l.url, "${e.name} (${l.text})") }, onLongClick = { ctx.copy(l.url, "Link copied") })
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (e.url != null) IconButton(onClick = { store.toggleBookmark(e) }) {
                Icon(if (saved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark", tint = if (saved) Accent else Muted)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            e.url?.let { url ->
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; open(url, e.name) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                DropdownMenuItem(text = { Text("Open in phone browser") }, onClick = { menu = false; ctx.openExternal(url) }, leadingIcon = { Icon(Icons.Default.Public, null) })
                DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; ctx.copy(url, "Link copied") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; ctx.shareText("${e.name}: $url") }, leadingIcon = { Icon(Icons.Default.Share, null) })
                DropdownMenuItem(text = { Text(if (saved) "Remove bookmark" else "Bookmark") }, onClick = { menu = false; store.toggleBookmark(e) }, leadingIcon = { Icon(Icons.Default.Bookmark, null) })
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(body, color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}
