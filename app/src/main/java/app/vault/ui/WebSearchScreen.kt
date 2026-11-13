package app.vault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.app
import app.vault.data.Prefs

@Composable
fun WebSearchScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val sites by ctx.app.repo.sites.all().collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    fun go() {
        val t = q.trim(); if (t.isEmpty()) return
        val looksLikeUrl = !t.contains(' ') && t.contains('.')
        ctx.openBrowser(if (looksLikeUrl) (if (t.startsWith("http")) t else "https://$t") else prefs.webSearchUrl(t))
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Search the web", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))
        Text("Opens in the private in-app browser.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("Search or type a web address") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = ::go) { Icon(Icons.Default.ArrowCircleRight, "Go", tint = Accent) } },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.ENGINES.forEach { (k, label) ->
                FilterChip(selected = prefs.searchEngine == k, onClick = { prefs.searchEngine = k }, label = { Text(label) })
            }
        }
        if (sites.isNotEmpty()) {
            SectionTitle("Quick open")
            LazyColumn {
                items(sites, key = { it.domain }) { s -> SiteRow(s, false) { ctx.openBrowser(s.url) } }
            }
        }
    }
}
