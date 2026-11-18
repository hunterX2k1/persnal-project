package app.vault.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vault.AppLock
import app.vault.app
import app.vault.data.attempt
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

val ICON_ALIASES = listOf("Vault", "Notes", "Files")

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val repo = ctx.app.repo
    val scope = rememberCoroutineScope()
    val total by repo.performers.count().collectAsState(initial = 0)
    var key by remember { mutableStateOf(prefs.apiKey) }
    val syncing by repo.syncing.collectAsState()
    val progress by repo.syncProgress.collectAsState()
    var pinStep by remember { mutableStateOf(0) } // 0 none, 1 enter new, 2 confirm
    var firstPin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))

        SectionTitle("Database")
        Text(
            "Profiles come from Wikidata, a free public database. No account or key needed.",
            fontSize = 13.sp, color = Muted,
        )
        Text(
            "$total profiles saved on this phone. Updates automatically every 12 hours in the background." +
                (if (prefs.lastSync > 0) "\nLast update: ${DateFormat.getDateTimeInstance().format(Date(prefs.lastSync))} · ${prefs.lastSyncMessage}" else ""),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedButton(
            enabled = !syncing,
            onClick = { repo.startSync() },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (syncing) (progress ?: "Updating…") else "Update now") }

        SectionTitle("Optional: ThePornDB key")
        Text(
            "Adds more profiles, extra details (measurements, tattoos…), scene lists with previews and ✓ matches on your sites.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            key, { key = it }, label = { Text("ThePornDB API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { prefs.apiKey = key.trim(); if (prefs.apiKey.isNotBlank()) repo.startSync() }, enabled = key.trim() != prefs.apiKey) { Text("Save key") }
            OutlinedButton(onClick = { ctx.openBrowser("https://theporndb.net/register") }) { Text("Get a free key") }
        }
        SectionTitle("Privacy")
        ToggleRow("Hide from Recent apps & block screenshots", "Shows a blank card in the app switcher", prefs.hideInRecents) { prefs.hideInRecents = it }
        ToggleRow("Blur all images", "Tap a profile photo to reveal it", prefs.blurImages) { prefs.blurImages = it }
        ToggleRow("Clear browser data on close", "Cookies, history and cache are wiped each time", prefs.clearBrowserOnExit) { prefs.clearBrowserOnExit = it }

        SectionTitle("New releases")
        val unseenNew by repo.releases.unseen.collectAsState()
        var checkingNew by remember { mutableStateOf(false) }
        Text("Follows your favourite performers (needs the ThePornDB key). Checked daily; new videos appear in Categories → New for you. No notifications.",
            color = Muted, fontSize = 13.sp)
        OutlinedButton(enabled = !checkingNew, modifier = Modifier.padding(top = 6.dp), onClick = {
            checkingNew = true
            scope.launch { val n = attempt { repo.checkNewReleases() }.getOrDefault(0); checkingNew = false; android.widget.Toast.makeText(ctx, "$n new videos", android.widget.Toast.LENGTH_SHORT).show() }
        }) { Text(if (checkingNew) "Checking…" else "Check now" + if (unseenNew > 0) " · $unseenNew new" else "") }

        SectionTitle("Finding new sites")
        ToggleRow("Look for new sites while searching", "When you search your sites for a video, also search the web for it", prefs.discoverSites) { prefs.discoverSites = it }
        ToggleRow("Add sites that have the video", "New sites with a matching video are added to My sites (marked New)", prefs.autoAddDiscovered) { prefs.autoAddDiscovered = it }
        TextButton(onClick = { repo.health.reset(); android.widget.Toast.makeText(ctx, "Site health reset", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Reset site health scores") }

        SectionTitle("Ad blocker")
        ToggleRow("Block ads & trackers", "${app.vault.data.AdBlock.adHostCount} ad, tracker and malware hosts, including the pop-up networks adult sites use", prefs.adblock) { prefs.adblock = it }
        ToggleRow("Block pop-ups & redirects", "Stops sites jumping to other sites unless you tap something; you can still open them from the message", prefs.blockRedirects) { prefs.blockRedirects = it }
        ToggleRow("Warn about malware sites", "${app.vault.data.AdBlock.unsafeCount} known scam / malware sites show a warning first", prefs.warnUnsafe) { prefs.warnUnsafe = it }
        if (prefs.allowAds.isNotEmpty()) Text("Ads allowed on: ${prefs.allowAds.sorted().joinToString(", ")}", color = Muted, fontSize = 12.sp)
        var updatingLists by remember { mutableStateOf(false) }
        OutlinedButton(enabled = !updatingLists, onClick = {
            updatingLists = true
            scope.launch { app.vault.data.AdBlock.load(java.io.File(ctx.filesDir, "blocklists"), force = true); updatingLists = false }
        }) { Text(if (updatingLists) "Updating lists…" else "Update block lists") }
        ToggleRow("PIN lock", "Asks for a PIN when the app is reopened", prefs.hasPin) { on ->
            if (on) pinStep = 1 else prefs.setPin(null)
        }
        if (prefs.hasPin) {
            Text("Lock again after", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf(0 to "Immediately", 1 to "1 min", 5 to "5 min", 15 to "15 min").forEach { (m, label) ->
                    FilterChip(prefs.lockAfterMin == m, { prefs.lockAfterMin = m }, label = { Text(label) })
                }
            }
            val activity = ctx as? androidx.fragment.app.FragmentActivity
            if (activity != null && Biometrics.available(activity))
                ToggleRow("Unlock with fingerprint", "Use your fingerprint or face instead of typing the PIN", prefs.biometric) { prefs.biometric = it }
        }

        SectionTitle("App icon & name")
        Text("Choose how the app appears on your home screen. Your launcher may take a few seconds to update.", color = Muted, fontSize = 13.sp)
        ICON_ALIASES.forEach { name ->
            Row(Modifier.fillMaxWidth().clickable { setAlias(ctx, name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = prefs.iconAlias == name, onClick = { setAlias(ctx, name) })
                Text(name)
            }
        }

        DiagnosticsSection()

        SectionTitle("About")
        Text(
            "Performer data comes from ThePornDB. Only adults (18+) are shown. This app stores no content itself; " +
                "everything opens from the original websites.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(32.dp))
    }

    if (pinStep > 0) Dialog(onDismissRequest = { pinStep = 0 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (pinStep == 1) PinPad("Choose a PIN (4–8 digits)", onDone = { firstPin = it; pinStep = 2; true }, onCancel = { pinStep = 0 })
            else PinPad("Repeat PIN", onDone = { p ->
                (p == firstPin).also { if (it) { prefs.setPin(p); AppLock.locked = false; pinStep = 0 } }
            }, onCancel = { pinStep = 0 })
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun setAlias(ctx: android.content.Context, name: String) {
    val pm = ctx.packageManager
    ICON_ALIASES.forEach { a ->
        pm.setComponentEnabledSetting(
            ComponentName(ctx, "app.vault.Alias$a"),
            if (a == name) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
    ctx.app.prefs.iconAlias = name
}
