/*
 * Call Keeper - a FORK (edited clone) of ShizuCallRecorder, available at https://github.com/kitsumed/ShizuCallRecorder
 *  Original work Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.recording.RecordingIndex
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.system.storage.PrivateRecordingStorage
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ORIGINAL_PROJECT_URL = "https://github.com/kitsumed/ShizuCallRecorder"

/** One row in the Recordings list. */
data class RecordingItem(
    val uri: Uri,
    val fileName: String,
    val mimeType: String?,
    val displayName: String,
    val phoneNumber: String?,
    val direction: CallDirection?,
    val timeMillis: Long,
    val durationMillis: Long?,
    val sizeBytes: Long
)

data class RecordingsUiState(
    val loading: Boolean = true,
    val items: List<RecordingItem> = emptyList(),
    val shizukuReady: Boolean = true
)

/** State of the built-in player: which recording is loaded and where playback is. */
data class PlayerState(
    val uri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

class RecordingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(RecordingsUiState())
    val state = _state.asStateFlow()

    private val _player = MutableStateFlow(PlayerState())
    val player = _player.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    fun refresh() {
        val context = getApplication<Application>()
        _state.value = _state.value.copy(
            loading = true,
            shizukuReady = ShizukuConnectionManager.isAvailable() && ShizukuConnectionManager.hasPermission()
        )
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { loadRecordings(context) }
            _state.value = _state.value.copy(loading = false, items = items)
        }
    }

    fun delete(item: RecordingItem) {
        val context = getApplication<Application>()
        if (_player.value.uri == item.uri) stopPlayback()
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { PrivateRecordingStorage.delete(context, item.uri) }
            if (deleted) {
                withContext(Dispatchers.IO) { RecordingIndex.remove(context, item.uri.toString()) }
                _state.value = _state.value.copy(items = _state.value.items.filterNot { it.uri == item.uri })
            } else {
                Toast.makeText(context, "Could not delete the recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Play/pause toggle. Tapping a different recording switches to it. */
    fun togglePlay(item: RecordingItem) {
        val current = _player.value
        val mp = mediaPlayer
        if (current.uri == item.uri && mp != null) {
            if (mp.isPlaying) {
                mp.pause()
                _player.value = current.copy(isPlaying = false, positionMs = mp.currentPosition)
            } else {
                mp.start()
                _player.value = current.copy(isPlaying = true)
                startProgressUpdates()
            }
            return
        }
        stopPlayback()
        val context = getApplication<Application>()
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, item.uri)
                prepare()
                setOnCompletionListener {
                    _player.value = _player.value.copy(isPlaying = false, positionMs = 0)
                    progressJob?.cancel()
                    runCatching { it.seekTo(0) }
                }
                start()
            }
            mediaPlayer = player
            _player.value = PlayerState(uri = item.uri, isPlaying = true, positionMs = 0, durationMs = player.duration.coerceAtLeast(0))
            startProgressUpdates()
        } catch (e: Exception) {
            AppLogger.e("RecordingsScreen: could not play ${item.fileName}", e)
            Toast.makeText(context, "Could not play this recording", Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
    }

    fun seekTo(positionMs: Int) {
        val mp = mediaPlayer ?: return
        runCatching { mp.seekTo(positionMs) }
        _player.value = _player.value.copy(positionMs = positionMs)
    }

    fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.let { runCatching { it.stop() }; it.release() }
        mediaPlayer = null
        _player.value = PlayerState()
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val mp = mediaPlayer ?: break
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                if (!playing) break
                _player.value = _player.value.copy(positionMs = runCatching { mp.currentPosition }.getOrDefault(0))
                delay(250)
            }
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }

    private fun loadRecordings(context: Context): List<RecordingItem> {
        val files = PrivateRecordingStorage.listFiles(context)
        val index = RecordingIndex.getAll(context)
        val contactCache = HashMap<String, String?>()
        val canReadContacts = PermissionChecks.hasContactsPermission(context)

        return files.map { file ->
            val uri = PrivateRecordingStorage.uriFor(context, file)
            val entry = index[uri.toString()]
            val number = entry?.phoneNumber?.ifBlank { null }
            // Look the contact up again by number: this picks up contacts saved or renamed after the call,
            // and Android's PhoneLookup matches numbers saved with or without the country code.
            val liveName = if (number != null && canReadContacts) {
                contactCache.getOrPut(number) { lookupContactName(context, number) }
            } else null
            val displayName = liveName
                ?: entry?.callerName
                ?: number
                ?: if (entry != null) "Private / Unknown number" else file.nameWithoutExtension

            RecordingItem(
                uri = uri,
                fileName = file.name,
                mimeType = PrivateRecordingStorage.mimeTypeFor(file),
                displayName = displayName,
                phoneNumber = number,
                direction = entry?.direction,
                timeMillis = entry?.startedAtMillis?.takeIf { it > 0 } ?: file.lastModified(),
                durationMillis = entry?.durationMillis?.takeIf { it > 0 },
                sizeBytes = file.length()
            )
        }.sortedByDescending { it.timeMillis }
    }

    private fun lookupContactName(context: Context, number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        AppLogger.w("RecordingsScreen: contact lookup failed", e)
        null
    }
}

@Composable
fun RecordingsScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val player by viewModel.player.collectAsState()
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<RecordingItem?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPlayback() }

    val filtered = remember(state.items, query) {
        val q = query.trim()
        if (q.isEmpty()) state.items else {
            val digits = q.filter { it.isDigit() }
            state.items.filter { item ->
                item.displayName.contains(q, ignoreCase = true) ||
                    item.fileName.contains(q, ignoreCase = true) ||
                    (digits.isNotEmpty() && item.phoneNumber?.filter { it.isDigit() }?.contains(digits) == true)
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.safeDrawing
                .add(WindowInsets(left = 16.dp, right = 16.dp, top = 0.dp, bottom = 0.dp))
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Recordings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            if (!state.shizukuReady) {
                item { ShizukuWarningCard(context) }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search name or number") }
                )
            }

            when {
                state.loading && state.items.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                filtered.isEmpty() -> item {
                    InfoText(if (query.isBlank()) "No recordings yet. Every call you make or answer in your normal Phone app is recorded automatically and appears here when it ends." else "No recordings match \"$query\".")
                }
                else -> items(filtered, key = { it.uri.toString() }) { item ->
                    RecordingRow(
                        item = item,
                        player = if (player.uri == item.uri) player else null,
                        onPlay = { viewModel.togglePlay(item) },
                        onSeek = { viewModel.seekTo(it) },
                        onShare = { share(context, item) },
                        onDelete = { pendingDelete = item }
                    )
                }
            }

            item {
                ForkNotice(context)
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("The recording with ${item.displayName} from ${formatDate(context, item.timeMillis)} will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(item); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    player: PlayerState?,
    onPlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, label) = when (item.direction) {
                CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived to "Incoming"
                CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade to "Outgoing"
                null -> Icons.Default.PlayArrow to "Recording"
            }
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.phoneNumber != null && item.phoneNumber != item.displayName) {
                    Text(item.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                val details = buildList {
                    add(formatDate(context, item.timeMillis))
                    item.durationMillis?.let { add(DateUtils.formatElapsedTime(it / 1000)) }
                }.joinToString("  ·  ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onPlay) {
                if (player?.isPlaying == true) Icon(Icons.Default.Pause, contentDescription = "Pause")
                else Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
        if (player != null && player.durationMs > 0) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(DateUtils.formatElapsedTime(player.positionMs / 1000L), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = player.positionMs.toFloat().coerceIn(0f, player.durationMs.toFloat()),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..player.durationMs.toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(DateUtils.formatElapsedTime(player.durationMs / 1000L), style = MaterialTheme.typography.labelSmall)
            }
        }
        }
    }
}

@Composable
private fun ShizukuWarningCard(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Calls are NOT being recorded", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("Shizuku is not running (this happens after every restart). Open Shizuku and start it, or turn on \"Start on boot\" in Shizuku so it starts by itself once Wi-Fi connects.")
            FilledTonalButton(onClick = { openShizuku(context) }) { Text("Open Shizuku") }
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
    )
}

@Composable
private fun ForkNotice(context: Context) {
    Text(
        text = "Call Keeper is a FORK (edited clone) of ShizuCallRecorder by kitsumed, available at $ORIGINAL_PROJECT_URL. It is not the original app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, ORIGINAL_PROJECT_URL) }
            .padding(vertical = 16.dp)
    )
}

private fun formatDate(context: Context, millis: Long): String =
    DateUtils.formatDateTime(
        context, millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR
    )

private fun share(context: Context, item: RecordingItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType ?: "audio/*"
        putExtra(Intent.EXTRA_STREAM, item.uri)
        putExtra(Intent.EXTRA_SUBJECT, "Call recording - ${item.displayName}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launchSafely(context, Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openShizuku(context: Context) {
    val pkg = ShizukuConnectionManager.getPackageName(context)
    val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (launch != null) {
        launchSafely(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else {
        openUrl(context, "https://github.com/thedjchi/Shizuku/releases/latest")
    }
}

private fun openUrl(context: Context, url: String) {
    launchSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun launchSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        AppLogger.w("RecordingsScreen: no app could handle intent ${intent.action}", e)
        Toast.makeText(context, "No app found to open this", Toast.LENGTH_SHORT).show()
    }
}
