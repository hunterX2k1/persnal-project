package com.thesis.memexplorer

import java.io.RandomAccessFile
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min

/**
 * The Android analogue of the Python classifier/search core. Without root an app
 * can only read ITS OWN process (/proc/self/maps + /proc/self/mem) — the per-app
 * UID sandbox and SELinux stop it reading any other app. That is the thesis
 * contrast with Windows, where same-user processes can read each other.
 */
object MemScan {

    data class Region(
        val start: Long, val end: Long, val perms: String, val path: String
    ) {
        val size: Long get() = end - start
        val readable: Boolean get() = perms.isNotEmpty() && perms[0] == 'r'
        val writable: Boolean get() = perms.length > 1 && perms[1] == 'w'
        val executable: Boolean get() = perms.length > 2 && perms[2] == 'x'
        val kind: String
            get() = when {
                path.startsWith("/") && (executable || path.contains(".so")) -> "image"
                path.startsWith("/") || path.startsWith("/dev") -> "mapped"
                else -> "private" // [heap], [stack], [anon:...], ""
            }
    }

    fun entropy(data: ByteArray, len: Int = data.size): Double {
        if (len == 0) return 0.0
        val counts = IntArray(256)
        for (i in 0 until len) counts[data[i].toInt() and 0xFF]++
        var e = 0.0
        for (c in counts) if (c > 0) {
            val p = c.toDouble() / len
            e -= p * (ln(p) / ln(2.0))
        }
        return e
    }

    fun printableRatio(data: ByteArray, len: Int = data.size): Double {
        if (len == 0) return 0.0
        var p = 0
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xFF
            if (b in 0x20..0x7E || b == 0x09 || b == 0x0A || b == 0x0D) p++
        }
        return p.toDouble() / len
    }

    fun zeroRatio(data: ByteArray, len: Int = data.size): Double {
        if (len == 0) return 0.0
        var z = 0
        for (i in 0 until len) if (data[i].toInt() == 0) z++
        return z.toDouble() / len
    }

    /** Content label from byte statistics, matching the Python classifier's labels. */
    fun classify(data: ByteArray, len: Int = data.size, executable: Boolean = false): String {
        if (len == 0) return "empty"
        val z = zeroRatio(data, len)
        val p = printableRatio(data, len)
        val e = entropy(data, len)
        return when {
            z > 0.95 -> "zeros"
            executable -> "code"
            p > 0.85 -> "text"
            len >= 32 && e / min(8.0, log2(len.toDouble())) > 0.9 -> "high-entropy (key/cipher)"
            else -> "binary-struct"
        }
    }

    /**
     * Sliding-window entropy scan (plan §4): offsets whose [window]-byte entropy
     * is near the ceiling for that size — how forensic tools flag keys/IVs with
     * no label. For 32 bytes the ceiling is log2(32)=5 bits; AES keys sit ~4.8+.
     */
    fun keyCandidates(data: ByteArray, window: Int = 32, step: Int = 8): List<Int> {
        val out = ArrayList<Int>()
        val minE = log2(window.toDouble()) - 0.35
        val buf = ByteArray(window)
        var off = 0
        var lastEnd = -1
        while (off + window <= data.size) {
            System.arraycopy(data, off, buf, 0, window)
            var zeros = 0
            for (b in buf) if (b.toInt() == 0) zeros++
            if (zeros <= 2 && entropy(buf) >= minE) {
                if (off >= lastEnd) out.add(off)
                lastEnd = off + window
            }
            off += step
        }
        return out
    }

    fun readMaps(): List<Region> {
        val out = ArrayList<Region>()
        java.io.File("/proc/self/maps").forEachLine { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 2) return@forEachLine
            val range = parts[0].split("-")
            if (range.size != 2) return@forEachLine
            try {
                val start = range[0].toLong(16)
                val end = range[1].toLong(16)
                val path = if (parts.size >= 6) parts[5] else ""
                out.add(Region(start, end, parts[1], path))
            } catch (_: NumberFormatException) { }
        }
        return out
    }

    /** Read up to [maxBytes] from a region of our own process; null if unreadable. */
    fun readRegion(raf: RandomAccessFile, r: Region, maxBytes: Int): ByteArray? {
        if (!r.readable) return null
        val n = min(r.size, maxBytes.toLong()).toInt()
        if (n <= 0) return null
        return try {
            raf.seek(r.start)
            val buf = ByteArray(n)
            val got = raf.read(buf)
            if (got <= 0) null else if (got == n) buf else buf.copyOf(got)
        } catch (_: Exception) {
            null // guard pages / unreadable even in our own process
        }
    }

    /** Count occurrences of [needle] across our own readable memory. */
    fun searchSelf(needle: ByteArray, maxHits: Int = 100): List<Long> {
        val hits = ArrayList<Long>()
        val raf = RandomAccessFile("/proc/self/mem", "r")
        raf.use {
            for (r in readMaps()) {
                if (!r.readable) continue
                val data = readRegion(raf, r, 4 * 1024 * 1024) ?: continue
                var from = 0
                while (true) {
                    val idx = indexOf(data, needle, from)
                    if (idx < 0) break
                    hits.add(r.start + idx)
                    if (hits.size >= maxHits) return hits
                    from = idx + 1
                }
            }
        }
        return hits
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in start..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
