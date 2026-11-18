package app.atlas.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** "Show.Name.S01E03.1080p.mkv" → show "Show Name", season 1, episode 3. */
object EpisodeParser {
    data class Episode(val show: String, val season: Int, val episode: Int)

    private val PATTERNS = listOf(
        Regex("^(.*?)[ ._-]+[Ss](\\d{1,2})[ ._-]?[Ee](\\d{1,3})"),       // Show.Name.S01E03
        Regex("^(.*?)[ ._-]+(\\d{1,2})x(\\d{2,3})(?:\\D|$)"),              // Show Name 1x03
        Regex("^(.*?)[ ._-]+[Ss]eason[ ._-]?(\\d{1,2})[ ._-]+[Ee]p(?:isode)?[ ._-]?(\\d{1,3})", RegexOption.IGNORE_CASE),
    )

    fun parse(fileName: String): Episode? {
        val base = fileName.substringBeforeLast('.')
        for (p in PATTERNS) {
            val m = p.find(base) ?: continue
            val show = clean(m.groupValues[1])
            if (show.isNotBlank()) return Episode(show, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        return null
    }

    fun clean(s: String) = s.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '[', ']', '(', ')')
        .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private val TYPES = listOf(
        "Videos" to setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "wmv", "flv"),
        "Music" to setOf("mp3", "flac", "m4a", "wav", "ogg", "opus", "aac"),
        "Documents" to setOf("pdf", "epub", "mobi", "azw3", "doc", "docx", "txt", "cbz", "cbr", "djvu", "odt", "rtf"),
        "Apps" to setOf("apk", "xapk", "apks", "apkm"),
        "Archives" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz"),
        "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic"),
    )

    /** Type folder for a file name, e.g. "Videos", "Documents" (empty if unknown). */
    fun typeFolder(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return TYPES.firstOrNull { ext in it.second }?.first.orEmpty()
    }

    /** Folder (inside Downloads/Atlas) a file goes into by default: series episodes by show and season, others by type. */
    fun suggestFolder(fileName: String): String = parse(fileName)?.let { "${it.show}/Season ${it.season}" } ?: typeFolder(fileName)

    /** Folder and file names safe for the file system. */
    fun safe(path: String) = path.split('/')
        .map { it.replace(Regex("[\\\\:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }.joinToString("/")
}

data class DownloadRec(
    val id: Long, val fileName: String, val folder: String, val url: String,
    val userAgent: String, val mime: String?, val wifiOnly: Boolean, val time: Long,
)

data class DownloadStatus(val rec: DownloadRec, val state: Int, val done: Long, val total: Long, val reason: Int) {
    val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
    val finished get() = state == DownloadManager.STATUS_SUCCESSFUL
    val failed get() = state == DownloadManager.STATUS_FAILED
}

/**
 * Downloads files a site offers, using Android's downloader (keeps going in the background,
 * resumes after connection drops). Files go to Downloads/Atlas/<folder>/.
 */
class Downloads(private val context: Context) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val sp = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val records = MutableStateFlow(read())
    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", false)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    data class LinkInfo(val fileName: String, val mime: String?, val size: Long)

    /**
     * Checks a pasted link. Returns file details if it points at a file; null if it's a web page
     * (a page link, e.g. a video site's watch page, can't be downloaded as a file).
     */
    suspend fun checkLink(url: String, userAgent: String): Result<LinkInfo?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val client = okhttp3.OkHttpClient.Builder().followRedirects(true).callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-0")
                .apply { CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) } }.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("The server answered ${r.code}")
                val type = r.header("Content-Type")?.substringBefore(';')?.trim()
                if (type == null || type.startsWith("text/html") || type.startsWith("application/xhtml")) return@use null
                val cd = r.header("Content-Disposition")
                val size = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.header("Content-Length")?.toLongOrNull() ?: -1
                LinkInfo(android.webkit.URLUtil.guessFileName(r.request.url.toString(), cd, type), type, size)
            }
        }
    }

    fun recentFolders(): List<String> = records.value.map { it.folder }.filter { it.isNotBlank() }.distinct().take(8)

    fun enqueue(url: String, fileName: String, folder: String, userAgent: String, mime: String?, wifiOnly: Boolean): DownloadRec {
        val name = EpisodeParser.safe(fileName).ifBlank { "download" }
        val dir = EpisodeParser.safe(folder)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription(if (dir.isBlank()) "Atlas" else "Atlas / $dir")
            .setMimeType(mime)
            .addRequestHeader("User-Agent", userAgent)
            .apply { CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) } }
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(if (wifiOnly) DownloadManager.Request.NETWORK_WIFI else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, listOf("Atlas", dir, name).filter { it.isNotBlank() }.joinToString("/"))
        val id = dm.enqueue(req)
        val rec = DownloadRec(id, name, dir, url, userAgent, mime, wifiOnly, System.currentTimeMillis())
        records.value = listOf(rec) + records.value
        write()
        AppLog.i("Downloads", "Queued a file into ${if (dir.isBlank()) "Atlas" else "a folder"}")
        return rec
    }

    fun statuses(): List<DownloadStatus> {
        val recs = records.value
        if (recs.isEmpty()) return emptyList()
        val byId = HashMap<Long, DownloadStatus>()
        dm.query(DownloadManager.Query().setFilterById(*recs.map { it.id }.toLongArray()))?.use { c ->
            val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val iState = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val iDone = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val rec = recs.firstOrNull { it.id == id } ?: continue
                byId[id] = DownloadStatus(rec, c.getInt(iState), c.getLong(iDone), c.getLong(iTotal), c.getInt(iReason))
            }
        }
        // Records Android no longer knows about (cleared by the system) show as failed so they can be retried or removed.
        return recs.map { byId[it.id] ?: DownloadStatus(it, DownloadManager.STATUS_FAILED, 0, 0, 0) }
    }

    fun openUri(rec: DownloadRec): Uri? = dm.getUriForDownloadedFile(rec.id)

    /** Stop a running download, or remove a finished one (also deletes the file). */
    fun remove(rec: DownloadRec) {
        dm.remove(rec.id)
        records.value = records.value.filter { it.id != rec.id }
        write()
    }

    /** Start the same file again (cookies are refreshed from the browser). */
    fun retry(rec: DownloadRec) {
        dm.remove(rec.id)
        records.value = records.value.filter { it.id != rec.id }
        enqueue(rec.url, rec.fileName, rec.folder, rec.userAgent, rec.mime, rec.wifiOnly)
    }

    fun clearFinished() {
        val done = statuses().filter { it.finished }.map { it.rec.id }.toSet()
        records.value = records.value.filter { it.id !in done }   // files stay on the phone
        write()
    }

    private fun read(): List<DownloadRec> = runCatching {
        val a = JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            DownloadRec(o.getLong("id"), o.getString("f"), o.optString("d"), o.getString("u"), o.optString("ua"),
                o.optString("m").takeIf { it.isNotBlank() }, o.optBoolean("w"), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun write() {
        val a = JSONArray()
        records.value.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("f", r.fileName).put("d", r.folder).put("u", r.url).put("ua", r.userAgent)
                .put("m", r.mime ?: "").put("w", r.wifiOnly).put("t", r.time))
        }
        sp.edit().putString("list", a.toString()).apply()
    }
}
