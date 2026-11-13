package app.vault.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.AppLock
import app.vault.app

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPrivacyFlags()
        setContent { VaultTheme { Root(onLeave = { finishAndRemoveTask() }) } }
    }

    override fun onResume() {
        super.onResume()
        applyPrivacyFlags()
    }
}

@Composable
private fun Root(onLeave: () -> Unit) {
    val prefs = LocalContext.current.app.prefs
    Surface(Modifier.fillMaxSize(), color = Bg) {
        when {
            !prefs.ageConfirmed -> AgeGate(onEnter = { prefs.ageConfirmed = true; AppLock.locked = false }, onLeave = onLeave)
            prefs.hasPin && AppLock.locked -> PinPad(
                title = "Enter PIN",
                onDone = { pin -> prefs.checkPin(pin).also { if (it) AppLock.locked = false } },
            )
            else -> MainScaffold(onPanic = onLeave)
        }
    }
}

@Composable
private fun AgeGate(onEnter: () -> Unit, onLeave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center,
        ) { Text("18+", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Black, fontSize = 28.sp) }
        Text("Adults only", fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.padding(top = 24.dp))
        Text(
            "This app links to adult material. By entering you confirm you are at least 18 years old " +
                "(or the age of majority where you live) and that viewing adult material is legal for you.",
            color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onEnter, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("I am 18 or older — Enter", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp)) {
            Text("Leave")
        }
    }
}

/** Numeric PIN pad. [onDone] returns true if the PIN was accepted. */
@Composable
fun PinPad(title: String, onDone: (String) -> Boolean, onCancel: (() -> Unit)? = null) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun submit() { if (!onDone(pin)) { error = true; pin = "" } }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, null, tint = Accent, modifier = Modifier.size(40.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(maxOf(4, pin.length)) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < pin.length) Accent else Surface2)
                )
            }
        }
        Text(if (error) "Wrong PIN" else " ", color = MaterialTheme.colorScheme.error)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "<")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { k ->
                    FilledTonalButton(
                        onClick = {
                            error = false
                            when (k) {
                                "C" -> pin = ""
                                "<" -> pin = pin.dropLast(1)
                                else -> if (pin.length < 8) pin += k
                            }
                        },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Surface2),
                    ) {
                        if (k == "<") Icon(Icons.AutoMirrored.Filled.Backspace, null)
                        else Text(k, fontSize = 22.sp)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = ::submit, enabled = pin.length >= 4, shape = RoundedCornerShape(12.dp)) { Text("OK") }
        }
    }
}
