package app.vault.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.app
import app.vault.data.Performer
import app.vault.data.age
import app.vault.data.isActive
import coil.compose.SubcomposeAsyncImage

fun Context.openBrowser(url: String) =
    startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))

fun Context.playVideo(url: String, title: String) =
    startActivity(
        Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
    )

/** Remote image that honours the "blur images" privacy setting. */
@Composable
fun VImage(url: String?, modifier: Modifier = Modifier, forceReveal: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val blur = ctx.app.prefs.blurImages && !forceReveal
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Surface2).then(if (blur) Modifier.blur(28.dp) else Modifier),
        error = { Placeholder() },
        loading = { Box(Modifier.fillMaxSize().background(Surface2)) },
    )
}

@Composable
private fun Placeholder() = Box(Modifier.fillMaxSize().background(Surface2), Alignment.Center) {
    Icon(Icons.Default.Person, null, tint = Muted, modifier = Modifier.size(48.dp))
}

@Composable
fun PerformerCard(p: Performer, favorite: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp))) {
            VImage(p.thumb ?: p.image, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                p.age()?.let { Badge(text = "$it") }
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (p.isActive()) Live else Muted)
                )
            }
            if (favorite) Icon(
                Icons.Default.Favorite, null, tint = Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp)
            )
            p.sceneCount?.let {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { Badge(text = "$it scenes", dark = true) }
            }
        }
        Text(
            p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
        Text(
            listOfNotNull(p.nationality, p.ethnicity).joinToString(" • ").ifEmpty { " " },
            color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
        )
    }
}

@Composable
fun Badge(text: String, dark: Boolean = false) {
    Text(
        text,
        color = if (dark) Color.White else Color.Black,
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (dark) Color(0xB3000000) else Accent)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(
    text, fontWeight = FontWeight.Bold, fontSize = 16.sp,
    modifier = modifier.padding(top = 18.dp, bottom = 8.dp)
)

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(body, color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}
