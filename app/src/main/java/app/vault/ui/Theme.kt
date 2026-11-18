package app.vault.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.vault.app

val Accent = Color(0xFFFF9F1C)
val Bg = Color(0xFF0D0D0F)
val Surface1 = Color(0xFF17171A)
val Surface2 = Color(0xFF222226)
val Muted = Color(0xFF9A9AA2)
val Live = Color(0xFF3DDC84)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2606),
    onPrimaryContainer = Accent,
    secondary = Accent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A2606),
    onSecondaryContainer = Accent,
    background = Bg,
    onBackground = Color(0xFFF2F2F4),
    surface = Bg,
    onSurface = Color(0xFFF2F2F4),
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerLow = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF2A2A2F),
    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF2A2A2F),
)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}

/** Blank the app's preview in Recent apps and block screenshots (setting, on by default). */
fun Activity.applyPrivacyFlags() {
    if (app.prefs.hideInRecents) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
