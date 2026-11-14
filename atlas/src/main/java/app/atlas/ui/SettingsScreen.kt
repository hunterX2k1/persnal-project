package app.atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.atlas.app
import app.atlas.data.AdBlock
import app.atlas.data.AppLog
import app.atlas.data.Store
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.app
    val store = app.store
    val scope = rememberCoroutineScope()
    val refreshing by app.repo.refreshing.collectAsState()
    var updatingLists by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableLongStateOf(app.repo.lastUpdated()) }
    var report by remember { mutableStateOf<String?>(null) }
    val open = LocalOpen.current

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp))

        Section("Browser")
        Toggle(store.adblock, "Block ads & trackers", "${AdBlock.adHostCount} known ad, tracker and malware hosts. Makes video sites smoother and safer.")
        Toggle(store.blockRedirects, "Block pop-ups & redirects", "Stops sites from jumping to other sites unless you tap something.")
        Toggle(store.warnUnsafe, "Warn about unsafe sites", "${AdBlock.unsafeCount} sites FMHY lists as unsafe show a warning first.")
        Toggle(store.desktopMode, "Desktop mode by default", "Requests the full desktop version of sites.")
        Toggle(store.clearOnClose, "Clear browser data on close", "Logs you out of sites every time. Off keeps logins and settings.")
        OutlinedButton(enabled = !updatingLists, onClick = {
            updatingLists = true
            scope.launch { AdBlock.load(File(ctx.filesDir, "blocklists"), force = true); updatingLists = false }
        }) { Text(if (updatingLists) "Updating lists…" else "Update block lists") }

        Section("FMHY index")
        Text(
            (if (lastUpdated > 0) "Downloaded ${DateFormat.getDateTimeInstance().format(Date(lastUpdated))}. " else "") +
                "Pages refresh automatically once a day and work offline.",
            color = Muted, fontSize = 13.sp,
        )
        OutlinedButton(enabled = !refreshing, modifier = Modifier.padding(top = 8.dp), onClick = {
            scope.launch { app.repo.loadAll(force = true); lastUpdated = app.repo.lastUpdated() }
        }) { Text(if (refreshing) "Refreshing…" else "Refresh now") }

        Section("Diagnostics")
        Text("Errors and crashes are recorded on this phone only. Copy the report and paste it to Claude for debugging.", color = Muted, fontSize = 13.sp)
        if (AppLog.lastCrash() != null) Text("⚠ A crash was recorded.", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { report = buildReport(app) }) { Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp)); Text("View report") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear logs") }
        }

        Section("About")
        Text(
            "Atlas is an unofficial reader for FMHY (fmhy.net), a community-run index of free sites and tools. " +
                "The list is downloaded from FMHY's public GitHub repository. Atlas hosts nothing itself, " +
                "and sites listed there are run by third parties.",
            color = Muted, fontSize = 13.sp,
        )
        TextButton(onClick = { open("https://fmhy.net/", "FMHY") }) { Text("Open fmhy.net") }
        Spacer(Modifier.height(32.dp))
    }

    report?.let { r ->
        Dialog(onDismissRequest = { report = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { report = null }) { Icon(Icons.Default.Close, "Close") }
                        Text("Debug report", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { ctx.copy(r, "Report copied — paste it to Claude") }) { Icon(Icons.Default.ContentCopy, "Copy") }
                        IconButton(onClick = { ctx.shareText(r, "Atlas debug report") }) { Icon(Icons.Default.Share, "Share") }
                    }
                    SelectionContainer {
                        Text(r, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState()).padding(12.dp))
                    }
                }
            }
        }
    }
}

fun buildReport(app: app.atlas.App): String = AppLog.report(
    "Pages cached: ${app.repo.counts.value.size} · Sites: ${app.repo.counts.value.values.sum()}\n" +
        "Ad hosts: ${AdBlock.adHostCount} · Unsafe: ${AdBlock.unsafeCount} · Bookmarks: ${app.store.bookmarks.value.size}\n" +
        "Adblock ${app.store.adblock.value} · Redirect block ${app.store.blockRedirects.value} · Warn unsafe ${app.store.warnUnsafe.value} · Desktop ${app.store.desktopMode.value} · Clear on close ${app.store.clearOnClose.value}"
)

@Composable
private fun Section(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))

@Composable
private fun Toggle(flag: Store.Flag, title: String, sub: String) {
    val on by flag.state.collectAsState()
    Row(Modifier.fillMaxWidth().clickable { flag.value = !on }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(on, { flag.value = it })
    }
}
