/*
 * Call Keeper - a FORK (edited clone) of ShizuCallRecorder, available at https://github.com/kitsumed/ShizuCallRecorder
 *  Original work Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data.recording

import android.content.Context
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.utils.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * Small persistent index of the recordings made by the app, keyed by the recording document URI.
 *
 * File names cannot reliably hold the contact name (contacts get renamed, numbers are saved in
 * different formats, names contain characters that are stripped). The index keeps the call metadata
 * exactly as it was known at call time so the Recordings screen can always show who the call was with.
 */
object RecordingIndex {

    data class Entry(
        val uri: String,
        val phoneNumber: String,
        val callerName: String?,
        val direction: CallDirection,
        val startedAtMillis: Long,
        val durationMillis: Long
    )

    private const val FILE_NAME = "recordings_index.json"
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun readJson(context: Context): JSONObject = try {
        val f = file(context)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        AppLogger.w("RecordingIndex: could not read index, starting fresh", e)
        JSONObject()
    }

    private fun writeJson(context: Context, json: JSONObject) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(json.toString())
            tmp.delete()
        }
    }

    fun add(context: Context, entry: Entry) {
        synchronized(lock) {
            try {
                val json = readJson(context)
                json.put(entry.uri, JSONObject().apply {
                    put("number", entry.phoneNumber)
                    put("name", entry.callerName ?: JSONObject.NULL)
                    put("direction", entry.direction.name)
                    put("startedAt", entry.startedAtMillis)
                    put("duration", entry.durationMillis)
                })
                writeJson(context, json)
            } catch (e: Exception) {
                AppLogger.e("RecordingIndex: failed to add entry", e)
            }
        }
    }

    fun remove(context: Context, uri: String) {
        synchronized(lock) {
            try {
                val json = readJson(context)
                if (json.has(uri)) {
                    json.remove(uri)
                    writeJson(context, json)
                }
            } catch (e: Exception) {
                AppLogger.e("RecordingIndex: failed to remove entry", e)
            }
        }
    }

    fun getAll(context: Context): Map<String, Entry> {
        synchronized(lock) {
            val json = readJson(context)
            val result = HashMap<String, Entry>(json.length())
            for (key in json.keys()) {
                val o = json.optJSONObject(key) ?: continue
                result[key] = Entry(
                    uri = key,
                    phoneNumber = o.optString("number", ""),
                    callerName = if (o.isNull("name")) null else o.optString("name").ifBlank { null },
                    direction = runCatching { CallDirection.valueOf(o.optString("direction")) }
                        .getOrDefault(CallDirection.INCOMING),
                    startedAtMillis = o.optLong("startedAt", 0L),
                    durationMillis = o.optLong("duration", 0L)
                )
            }
            return result
        }
    }
}
