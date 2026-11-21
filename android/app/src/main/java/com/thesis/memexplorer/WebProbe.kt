package com.thesis.memexplorer

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * §11 web/network module. Fetches one of YOUR OWN test URLs with the platform
 * HttpURLConnection (no extra dependency). The raw response bytes land in this
 * app's heap, so they can then be located in /proc/self/mem — proving that
 * network data becomes RAM bytes, and that TLS protects the wire, not your own
 * process (plaintext exists in RAM before encrypt / after decrypt).
 *
 * No TLS verification is disabled. A self-signed test cert needs your own CA
 * installed on the device (per the plan) — we never bypass validation.
 */
object WebProbe {

    data class Response(
        val status: String,
        val headers: String,
        val body: ByteArray,
        val contentType: String,
        val https: Boolean,
    )

    fun fetch(urlStr: String, maxBody: Int = 256 * 1024): Response {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MemExplorer-thesis/0.1")
        }
        try {
            val code = conn.responseCode
            val status = "$code ${conn.responseMessage}"
            val headers = buildString {
                conn.headerFields.forEach { (k, v) ->
                    if (k != null) append(k).append(": ").append(v.joinToString(",")).append("\n")
                }
            }
            val stream = (if (code in 200..399) conn.inputStream else conn.errorStream) ?: ByteArray(0).inputStream()
            val body = BufferedInputStream(stream).use { bis ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < maxBody) {
                    val n = bis.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); total += n
                }
                out.toByteArray()
            }
            return Response(status, headers, body, conn.contentType ?: "", url.protocol == "https")
        } finally {
            conn.disconnect()
        }
    }

    /** Rough categorisation of a response body (plan §11 categorizer). */
    fun categorize(contentType: String, body: ByteArray): String {
        val head = String(body.copyOf(minOf(body.size, 512)), Charsets.ISO_8859_1).trimStart()
        return when {
            contentType.contains("json") || head.startsWith("{") || head.startsWith("[") -> "JSON"
            contentType.contains("html") || head.startsWith("<!") || head.startsWith("<html", true) -> "HTML"
            contentType.contains("javascript") -> "JavaScript"
            contentType.startsWith("image/") || contentType.startsWith("application/octet") -> "binary"
            MemScan.printableRatio(body) > 0.85 -> "text"
            else -> "binary/other"
        }
    }
}
