package app.atlas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.app
import app.atlas.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val repo = ctx.app.repo
    var q by rememberSaveable { mutableStateOf("") }
    var index by remember { mutableStateOf<List<Entry>?>(null) }
    var results by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { index = runCatching { repo.searchIndex() }.getOrDefault(emptyList()); runCatching { focus.requestFocus() } }
    LaunchedEffect(q, index, starredOnly) {
        val idx = index ?: return@LaunchedEffect
        delay(150)
        results = withContext(Dispatchers.Default) { repo.search(idx, q, starredOnly) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("Search every FMHY page") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, null) } },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(starredOnly, { starredOnly = !starredOnly }, label = { Text("⭐ Recommended only") })
        }
        when {
            index == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            q.trim().length < 2 -> EmptyState("Search everything", "Names, descriptions and sections across all ${index!!.size} sites. Try \"anime\", \"pdf\" or \"adblock\".")
            results.isEmpty() -> EmptyState("No results", "Try another word.")
            else -> {
                Text("${results.size}${if (results.size >= 300) "+" else ""} results", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(results, key = { it.key }) { EntryCard(it, showPlace = true) }
                }
            }
        }
    }
}
