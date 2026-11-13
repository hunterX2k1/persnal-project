package app.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import app.vault.app
import app.vault.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Full debug report: device, app state summary (no key, no site names), last crash, recent log. */
suspend fun buildReport(ctx: Context): String = withContext(Dispatchers.IO) {
    val prefs = ctx.app.prefs
    val repo = ctx.app.repo
    val state = buildString {
        appendLine("API key set: ${prefs.apiKey.isNotBlank()}")
        appendLine("Profiles: ${repo.performers.count().first()} · Favourites: ${repo.performers.favoriteIds().size} · Sites: ${repo.sites.list().size}")
        appendLine("Sync cursor: ${prefs.syncCursor} · Last sync: ${if (prefs.lastSync > 0) java.util.Date(prefs.lastSync) else "never"} · ${prefs.lastSyncMessage}")
        appendLine("PIN: ${prefs.hasPin} · Hide recents: ${prefs.hideInRecents} · Blur: ${prefs.blurImages} · Engine: ${prefs.searchEngine} · Icon: ${prefs.iconAlias}")
    }
    AppLog.report(state)
}

fun Context.copyText(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vault report", text))
    Toast.makeText(this, "Report copied — paste it to Claude", Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Vault debug report")
        .putExtra(Intent.EXTRA_TEXT, text.takeLast(400_000))
    startActivity(Intent.createChooser(send, "Share report"))
}

@Composable
fun DiagnosticsSection() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var hasCrash by remember { mutableStateOf(AppLog.lastCrash() != null) }
    LaunchedEffect(showLog) { if (showLog) report = buildReport(ctx) }

    SectionTitle("Diagnostics")
    Text(
        "Crashes and errors are recorded on this phone only. If something goes wrong, copy the report and paste it to Claude for debugging.",
        color = Muted, fontSize = 13.sp,
    )
    if (hasCrash) Text("⚠ A crash was recorded.", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { showLog = true }) { Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp)); Text("View report") }
        OutlinedButton(onClick = { AppLog.clear(); hasCrash = false; Toast.makeText(ctx, "Logs cleared", Toast.LENGTH_SHORT).show() }) { Text("Clear logs") }
    }

    if (showLog) ReportDialog(report, onDismiss = { showLog = false; report = null })
}

@Composable
fun ReportDialog(report: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Text("Debug report", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { report?.let(ctx::copyText) }, enabled = report != null) { Icon(Icons.Default.ContentCopy, "Copy") }
                    IconButton(onClick = { report?.let(ctx::shareText) }, enabled = report != null) { Icon(Icons.Default.Share, "Share") }
                }
                if (report == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else SelectionContainer {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE))
                            .horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
    }
}

/** Shown once after the app crashed, offering the report. */
@Composable
fun CrashPrompt() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(AppLog.hasUnseenCrash()) }
    var report by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(show) { if (show) report = buildReport(ctx) }
    if (show) AlertDialog(
        onDismissRequest = { AppLog.markCrashSeen(); show = false },
        icon = { Icon(Icons.Default.BugReport, null) },
        title = { Text("Vault closed unexpectedly") },
        text = { Text("A crash report was saved. Copy it and paste it to Claude so the problem can be fixed.") },
        confirmButton = {
            TextButton(onClick = { report?.let(ctx::copyText); AppLog.markCrashSeen(); show = false }, enabled = report != null) { Text("Copy report") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { AppLog.markCrashSeen(); show = false; open = true }) { Text("View") }
                TextButton(onClick = { AppLog.markCrashSeen(); show = false }) { Text("Dismiss") }
            }
        },
    )
    if (open) ReportDialog(report) { open = false }
}
