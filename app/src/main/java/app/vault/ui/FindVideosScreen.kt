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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Searches every searchable site for one performer and merges the results (duplicates grouped, full versions first). */
class FindVideosVm(app: Application, val performerId: String) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val performer = MutableStateFlow<Performer?>(null)
    val progress = MutableStateFlow<Map<String, SiteProgress>>(emptyMap())
    val running = MutableStateFlow(false)
    val siteCount = MutableStateFlow(-1)
    private var job: Job? = null

    init { viewModelScope.launch { performer.value = repo.performers.get(performerId); search() } }

    fun search() {
        val p = performer.value ?: return
        job?.cancel(); progress.value = emptyMap()
        job = viewModelScope.launch {
            running.value = true
            val sites = repo.videoSearchSites()
            siteCount.value = sites.size
            AppLog.i("FindVideos", "Searching ${sites.size} sites for a performer")
            val names = listOf(p.name) + p.aliases?.split(',')?.map { it.trim() }?.filter { it.length >= 4 }.orEmpty().take(2)
            val q = VideoQuery(p.name, names)
            launch { repo.discover(q).forEach { pr -> progress.value = progress.value.merged(pr) } }
            repo.siteSearch.searchAll(sites, q).collect { pr -> progress.value = progress.value.merged(pr) }
        }
        job?.invokeOnCompletion { running.value = false }
    }
}

@Composable
fun FindVideosScreen(performerId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val prefs = ctx.app.prefs
    val vm: FindVideosVm = viewModel(key = "f:$performerId", factory = viewModelFactory { initializer { FindVideosVm(app, performerId) } })
    val p by vm.performer.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val siteCount by vm.siteCount.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(p?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.search() }, enabled = !running) { Icon(Icons.Default.Refresh, "Search again") }
        }
        SiteResultsPane(
            progress = progress, running = running, siteCount = siteCount,
            cols = prefs.colsFind, onCols = { prefs.colsFind = it },
            openTerm = p?.name.orEmpty(),
            emptyTitle = "Nothing found", emptyBody = "None of your sites had videos for this name.",
        )
    }
}
