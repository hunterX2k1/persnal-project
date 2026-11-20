/*
 * Call Keeper - a FORK (edited clone) of ShizuCallRecorder, available at https://github.com/kitsumed/ShizuCallRecorder
 *  Original work Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File

/**
 * Call Keeper: recordings are stored privately inside the app (internal storage), not in a user folder.
 * They are invisible to file managers and music apps, and are exposed to other apps only through
 * temporary, per-request FileProvider grants (play with / share).
 *
 * Uninstalling the app deletes every recording.
 */
object PrivateRecordingStorage {

    private const val DIR_NAME = "recordings"

    fun authority(context: Context) = "${context.packageName}.recordings"

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Creates a new, empty audio file and opens it read-write (MediaMuxer seeks back to write headers).
     * [filePath] may contain sub-folders separated by [SafHelper.PATH_SEPARATOR].
     */
    fun createAudioFile(context: Context, filePath: String): SafHelper.SafResult? {
        return try {
            val root = directory(context)
            val segments = filePath.split(SafHelper.PATH_SEPARATOR).filter { it.isNotBlank() && it != "." && it != ".." }
            if (segments.isEmpty()) return null
            var dir = root
            for (d in segments.dropLast(1)) {
                dir = File(dir, d).apply { mkdirs() }
            }
            var file = File(dir, segments.last())
            // Never overwrite an existing recording.
            if (file.exists()) {
                val base = file.nameWithoutExtension
                val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
                var n = 1
                while (file.exists()) file = File(dir, "${base}_${n++}$ext")
            }
            if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) return null

            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )
            SafHelper.SafResult(uriFor(context, file), pfd, file.relativeTo(root).path)
        } catch (e: Exception) {
            AppLogger.e("PrivateRecordingStorage: failed to create recording file", e)
            null
        }
    }

    /** Lists every recording file (newest first by modification time). */
    fun listFiles(context: Context): List<File> =
        directory(context).walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .toList()

    private fun isOurs(context: Context, uri: Uri) = uri.authority == authority(context)

    /** Deletes a recording. Works for private recordings and, as a fallback, for older SAF recordings. */
    fun delete(context: Context, uri: Uri): Boolean = try {
        if (isOurs(context, uri)) {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            DocumentFile.fromSingleUri(context, uri)?.delete() == true
        }
    } catch (e: Exception) {
        AppLogger.w("PrivateRecordingStorage: failed to delete $uri", e)
        false
    }

    /** Size in bytes of the recording behind [uri], or 0 if it doesn't exist. */
    fun length(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "3gp", "amr" -> "audio/3gpp"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }
}
