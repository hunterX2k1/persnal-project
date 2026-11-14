package app.atlas.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF8AB4FF)
val Gold = Color(0xFFFFC94D)
val Bg = Color(0xFF0E1014)
val Surface1 = Color(0xFF161A21)
val Surface2 = Color(0xFF1F2530)
val Muted = Color(0xFF98A2B3)
val Danger = Color(0xFFFF6B6B)

private val Scheme = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF0A1530),
    primaryContainer = Color(0xFF1E3157), onPrimaryContainer = Accent,
    secondary = Accent, onSecondary = Color(0xFF0A1530),
    secondaryContainer = Color(0xFF1E3157), onSecondaryContainer = Accent,
    background = Bg, onBackground = Color(0xFFEDEFF3),
    surface = Bg, onSurface = Color(0xFFEDEFF3),
    surfaceVariant = Surface2, onSurfaceVariant = Muted,
    surfaceContainer = Surface1, surfaceContainerLow = Surface1,
    surfaceContainerHigh = Surface2, surfaceContainerHighest = Color(0xFF283041),
    outline = Color(0xFF353D4C), outlineVariant = Color(0xFF283041),
    error = Danger,
)

@Composable
fun AtlasTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
