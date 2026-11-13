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
    var syncing by remember { mutableStateOf(false) }
    var pinStep by remember { mutableStateOf(0) } // 0 none, 1 enter new, 2 confirm
    var firstPin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp))

        SectionTitle("Database")
        OutlinedTextField(
            key, { key = it }, label = { Text("ThePornDB API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { prefs.apiKey = key.trim() }, enabled = key.trim() != prefs.apiKey) { Text("Save key") }
            OutlinedButton(onClick = { ctx.openBrowser("https://theporndb.net/register") }) { Text("Get a free key") }
        }
        Text(
            "$total profiles saved on this phone. Updates automatically every 12 hours in the background." +
                (if (prefs.lastSync > 0) "\nLast update: ${DateFormat.getDateTimeInstance().format(Date(prefs.lastSync))} · ${prefs.lastSyncMessage}" else ""),
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedButton(
            enabled = !syncing && prefs.apiKey.isNotBlank(),
            onClick = {
                syncing = true
                scope.launch {
                    prefs.lastSyncMessage = runCatching { repo.syncStep(pages = 10) }.getOrElse { "Failed: ${it.message}" }
                    syncing = false
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (syncing) "Updating…" else "Update now") }

        SectionTitle("Privacy")
        ToggleRow("Hide from Recent apps & block screenshots", "Shows a blank card in the app switcher", prefs.hideInRecents) { prefs.hideInRecents = it }
        ToggleRow("Blur all images", "Tap a profile photo to reveal it", prefs.blurImages) { prefs.blurImages = it }
        ToggleRow("Clear browser data on close", "Cookies, history and cache are wiped each time", prefs.clearBrowserOnExit) { prefs.clearBrowserOnExit = it }
        ToggleRow("PIN lock", "Asks for a PIN whenever the app is reopened", prefs.hasPin) { on ->
            if (on) pinStep = 1 else prefs.setPin(null)
        }

        SectionTitle("App icon & name")
        Text("Choose how the app appears on your home screen. Your launcher may take a few seconds to update.", color = Muted, fontSize = 13.sp)
        ICON_ALIASES.forEach { name ->
            Row(Modifier.fillMaxWidth().clickable { setAlias(ctx, name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = prefs.iconAlias == name, onClick = { setAlias(ctx, name) })
                Text(name)
            }
        }

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
