package com.blockbridge

import android.content.Context
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Checks the repo's "BlockBridge (latest build)" GitHub release for a newer APK. The release
 * holds both apps: BlockBridge.apk ("versionCode=N" in the notes) and BlockBridgeVideo.apk
 * ("videoVersionCode=N").
 */
object Updates {
    const val REPO = "hunterx2k1/persnal-project"
    private const val TAG = "blockbridge-latest"
    const val RELEASE_PAGE = "https://github.com/$REPO/releases/tag/$TAG"

    class Channel(val asset: String, val key: String)
    val MAIN = Channel("BlockBridge.apk", "versionCode")
    val VIDEO = Channel("BlockBridgeVideo.apk", "videoVersionCode")

    /** The version number for [ch] in the release notes ("versionCode" doesn't match "videoVersionCode"). */
    internal fun versionIn(notes: String, ch: Channel): Long? =
        Regex("(?<![A-Za-z])${ch.key}=(\\d+)").find(notes)?.groupValues?.get(1)?.toLongOrNull()

    class Available(val versionCode: Long, val url: String, val notes: String)

    @Volatile var available: Available? = null
        private set

    private fun myVersion(ctx: Context): Long = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    }.getOrDefault(0)

    /** Blocking. Returns the newer release, or null if up to date / unreachable. */
    fun check(ctx: Context, ch: Channel = MAIN): Available? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/tags/$TAG").openConnection() as HttpsURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "BlockBridge")
        val json = try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
        val body = json.optString("body")
        val code = versionIn(body, ch) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        val apk = all.firstOrNull { it.optString("name") == ch.asset } ?: return null
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong("update_checked", System.currentTimeMillis()).apply()
        val result = if (code > myVersion(ctx)) Available(code, apk.getString("browser_download_url"), body) else null
        available = result
        return result
    }

    /** At most once a day, in the background. */
    fun checkDaily(ctx: Context, ch: Channel = MAIN, onFound: (Available) -> Unit) {
        val last = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("update_checked", 0)
        if (System.currentTimeMillis() - last < 20 * 60 * 60 * 1000L) return
        kotlin.concurrent.thread(name = "update-check", isDaemon = true) {
            runCatching { check(ctx, ch) }.getOrNull()?.let(onFound)
        }
    }
}
