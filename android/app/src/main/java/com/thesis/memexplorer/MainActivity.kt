package com.thesis.memexplorer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    companion object { const val MARKER = "THESIS_MARKER_7f3a9c" }

    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var tabBar: LinearLayout

    // live buffers kept as fields so they stay in the heap and are findable
    private val planted = ArrayList<ByteArray>()
    private var key: ByteArray? = null
    private var plaintext: ByteArray? = null
    private var ciphertext: ByteArray? = null
    private var lastWeb: WebProbe.Response? = null

    private val tabs = listOf("Scan", "Plant & Find", "Crypto", "Storage", "Keystore", "Web", "Walls", "Logs")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        title = "Memory Explorer"

        val rootScroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8) }
        tabs.forEach { name ->
            tabBar.addView(Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { selectTab(name) }
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tabBar) })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 48)
        }
        root.addView(content)
        rootScroll.addView(root)
        setContentView(rootScroll)

        if (AppLog.hadCrash()) {
            AppLog.log("NOTE: a previous run left a crash log. Open the Logs tab to view/share it.")
        }
        AppLog.log("started, pid ${android.os.Process.myPid()}")
        selectTab("Scan")
    }

    // ---- UI scaffolding ----------------------------------------------------
    private fun selectTab(name: String) {
        content.removeAllViews()
        for (i in 0 until tabBar.childCount) {
            (tabBar.getChildAt(i) as Button).setTypeface(null,
                if ((tabBar.getChildAt(i) as Button).text == name) Typeface.BOLD else Typeface.NORMAL)
        }
        when (name) {
            "Scan" -> tabScan()
            "Plant & Find" -> tabPlant()
            "Crypto" -> tabCrypto()
            "Storage" -> tabStorage()
            "Keystore" -> tabKeystore()
            "Web" -> tabWeb()
            "Walls" -> tabWalls()
            "Logs" -> tabLogs()
        }
    }

    private fun heading(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 8)
    })

    private fun note(t: String) = content.addView(TextView(this).apply { text = t; textSize = 13f; setPadding(0, 2, 0, 8) })

    private fun button(label: String, action: () -> Unit) = content.addView(Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    })

    private fun mono(): TextView = TextView(this).apply {
        typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
        movementMethod = ScrollingMovementMethod()
    }

    private fun input(hint: String, number: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun bg(out: TextView, action: () -> Unit) {
        progress.visibility = View.VISIBLE
        Thread {
            try { action() }
            catch (e: Exception) {
                val msg = "error: ${e.javaClass.simpleName}: ${e.message}"
                AppLog.log(msg); append(out, msg)
            } finally { runOnUiThread { progress.visibility = View.GONE } }
        }.start()
    }

    private fun append(tv: TextView, s: String) = runOnUiThread { tv.append(s + "\n") }
    private fun set(tv: TextView, cs: CharSequence) = runOnUiThread { tv.text = cs }

    // ---- colored hex dump (plan §2/§4) -------------------------------------
    private fun hexDump(data: ByteArray, base: Long, width: Int = 16): CharSequence {
        val sb = SpannableStringBuilder()
        var off = 0
        while (off < data.size) {
            val row = data.copyOfRange(off, minOf(off + width, data.size))
            val label = MemScan.classify(row, row.size)
            val color = when {
                label == "zeros" -> Color.parseColor("#9E9E9E")
                label == "text" -> Color.parseColor("#2E7D32")
                label.startsWith("high-entropy") -> Color.parseColor("#C62828")
                label == "code" -> Color.parseColor("#1565C0")
                else -> Color.parseColor("#424242")
            }
            val hex = row.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }.padEnd(width * 3)
            val asc = row.map { val b = it.toInt() and 0xFF; if (b in 0x20..0x7e) b.toChar() else '.' }.joinToString("")
            val start = sb.length
            sb.append("%012x  %s %s\n".format(base + off, hex, asc))
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            off += width
        }
        return sb
    }

    // ---- tabs --------------------------------------------------------------
    private fun tabScan() {
        heading("Scan my own memory")
        note("Reads /proc/self/maps + /proc/self/mem. Tap a region to see a colored hex dump " +
            "(green=text, red=high-entropy/key, gray=zeros, blue=code).")
        val out = mono()
        button("Scan regions") {
            bg(out) {
                val regions = MemScan.readMaps()
                val byLabel = HashMap<String, Long>(); val byKind = HashMap<String, Long>()
                var readable = 0
                RandomAccessFile("/proc/self/mem", "r").use { raf ->
                    for (r in regions) {
                        byKind[r.kind] = (byKind[r.kind] ?: 0) + r.size
                        val s = MemScan.readRegion(raf, r, 64 * 1024) ?: continue
                        readable++
                        val l = MemScan.classify(s, s.size, r.executable)
                        byLabel[l] = (byLabel[l] ?: 0) + r.size
                    }
                }
                val sb = StringBuilder("regions: ${regions.size} ($readable readable)\n\nby kind:\n")
                byKind.entries.sortedByDescending { it.value }.forEach { sb.append("  %-10s %,10d KiB\n".format(it.key, it.value / 1024)) }
                sb.append("\nby content:\n")
                byLabel.entries.sortedByDescending { it.value }.forEach { sb.append("  %-26s %,10d KiB\n".format(it.key, it.value / 1024)) }
                set(out, sb)
                AppLog.log("scan: ${regions.size} regions, $readable readable")
                // clickable region rows
                runOnUiThread {
                    val readableList = regions.filter { it.readable }.take(300)
                    content.addView(TextView(this).apply { text = "\nTap a region (${readableList.size} shown):"; setTypeface(null, Typeface.BOLD) })
                    readableList.forEach { r ->
                        content.addView(Button(this).apply {
                            text = "0x%x  %-5s %-8s %,d KiB %s".format(r.start, r.perms, r.kind, r.size / 1024,
                                r.path.substringAfterLast('/').take(20))
                            isAllCaps = false; textSize = 10f
                            setOnClickListener { dumpRegion(r) }
                        })
                    }
                }
            }
        }
        content.addView(out)
    }

    private fun dumpRegion(r: MemScan.Region) {
        val out = mono()
        content.addView(TextView(this).apply { text = "\ndump of 0x%x (%s):".format(r.start, r.kind); setTypeface(null, Typeface.BOLD) })
        content.addView(out)
        bg(out) {
            RandomAccessFile("/proc/self/mem", "r").use { raf ->
                val data = MemScan.readRegion(raf, r, 1024) ?: throw java.io.IOException("unreadable (guard page)")
                set(out, hexDump(data, r.start))
            }
        }
    }

    private fun tabPlant() {
        heading("Plant data, then find it in RAM")
        note("Type anything, plant it, then search your own memory for it. Exercise §9.1: " +
            "relaunch the app and the address changes — that is ASLR.")
        val field = input("text / number / fake key to plant")
        val findField = input("word to find in my RAM")
        val out = mono()
        content.addView(field)
        button("Plant in RAM") {
            val s = field.text.toString()
            if (s.isEmpty()) { append(out, "type something first"); return@button }
            val b = s.toByteArray(Charsets.UTF_8); planted.add(b)
            findField.setText(s)
            append(out, "planted ${b.size} bytes; now held in the app heap (${planted.size} planted so far)")
            AppLog.log("planted ${b.size} bytes")
        }
        content.addView(findField)
        button("Find in my RAM") {
            val s = findField.text.toString()
            if (s.isEmpty()) { append(out, "enter a word"); return@button }
            bg(out) {
                val hits = MemScan.searchSelf(s.toByteArray(Charsets.UTF_8))
                append(out, "\n'$s' found at ${hits.size} address(es):")
                hits.take(10).forEach { append(out, "  0x%x".format(it)) }
                if (hits.isEmpty()) append(out, "  (not found — plant it first, or it may be UTF-16 only)")
                AppLog.log("find '$s' -> ${hits.size} hits")
            }
        }
        content.addView(out)
    }

    private fun tabCrypto() {
        heading("Crypto demo")
        note("Plant an AES-256 key + marked plaintext + ciphertext in RAM, locate them, then wipe.")
        val out = mono()
        button("Run crypto demo") {
            bg(out) {
                val rnd = java.security.SecureRandom()
                val k = ByteArray(32).also { rnd.nextBytes(it) }
                val iv = ByteArray(16).also { rnd.nextBytes(it) }
                val pt = "$MARKER: account=demo-user; password=Fake-Passw0rd!; synthetic data".toByteArray()
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
                val ct = c.doFinal(pt)
                key = k; plaintext = pt; ciphertext = ct
                val mh = MemScan.searchSelf(MARKER.toByteArray()); val kh = MemScan.searchSelf(k)
                set(out, "key: ${k.joinToString(""){ "%02x".format(it.toInt() and 0xFF) }}\n\n" +
                    "marker in RAM: ${mh.size} hit(s)\nkey bytes in RAM: ${kh.size} hit(s)\n" +
                    "key-like entropy windows in key buffer: ${MemScan.keyCandidates(k).size}\n" +
                    "ART may keep hidden copies — note the counts, then wipe.")
                AppLog.log("crypto: marker=${mh.size} key=${kh.size}")
            }
        }
        button("Wipe key + plaintext, re-scan") {
            val k = key ?: run { append(out, "\nrun the demo first"); return@button }
            bg(out) {
                java.util.Arrays.fill(k, 0); plaintext?.let { java.util.Arrays.fill(it, 0) }
                val mh = MemScan.searchSelf(MARKER.toByteArray())
                append(out, "\nafter wipe: marker still found at ${mh.size} address(es)")
                append(out, if (mh.isEmpty()) "clean." else "leftover copies remain (ART temporaries) — a thesis finding.")
                AppLog.log("wipe: marker=${mh.size}")
            }
        }
        content.addView(out)
    }

    private fun tabStorage() {
        heading("Storage: one secret, three places (§5)")
        val out = mono()
        button("Write RAM / plaintext file / Keystore-encrypted file") {
            bg(out) {
                val secret = "$MARKER: account=demo-user; password=Fake-Passw0rd!; synthetic data".toByteArray()
                val inRam = secret.copyOf(); planted.add(inRam)
                val plain = java.io.File(filesDir, "secret_plaintext.txt").apply { writeBytes(secret) }
                val enc = KeystoreDemo.encrypt(secret)
                val encFile = java.io.File(filesDir, "secret_keystore.bin").apply { writeBytes(enc.blob) }
                val ram = MemScan.searchSelf(MARKER.toByteArray())
                val inCipher = String(encFile.readBytes(), Charsets.ISO_8859_1).contains(MARKER)
                set(out, buildString {
                    append("%-20s %-9s %-9s %s\n".format("where", "persists", "encrypted", "readable by"))
                    append("%-20s %-9s %-9s %s\n".format("(a) RAM var", "no", "no", "this app"))
                    append("%-20s %-9s %-9s %s\n".format("(b) ${plain.name}", "YES", "no", "this app"))
                    append("%-20s %-9s %-9s %s\n".format("(c) ${encFile.name}", "YES", "yes", "TEE key holder"))
                    append("\n(a) marker in RAM: ${ram.size} hit(s)\n")
                    append("(b) first bytes: ${String(plain.readBytes().copyOf(40), Charsets.ISO_8859_1)}\n")
                    append("(c) first bytes: ${encFile.readBytes().copyOf(24).joinToString(" "){ "%02x".format(it.toInt() and 0xFF) }}\n")
                    append("(c) marker visible in ciphertext? ${if (inCipher) "yes" else "NO"}\n")
                    append("(c) ${enc.note}")
                })
                AppLog.log("storage demo written to ${filesDir}")
            }
        }
        content.addView(out)
    }

    private fun tabKeystore() {
        heading("Keystore (TEE) key is NOT in RAM (§6)")
        val out = mono()
        button("Prove Keystore key is non-exportable") {
            bg(out) {
                val r = KeystoreDemo.encrypt("$MARKER-keystore-probe".toByteArray())
                set(out, "software key (Crypto tab): findable as raw bytes in RAM.\n\n" +
                    "Keystore key exportable = ${r.keyExportable}\n${r.note}\n\n" +
                    "No key bytes exist in the app to search for — the hardware boundary holds " +
                    "(Android's TPM/VBS equivalent).")
                AppLog.log("keystore: exportable=${r.keyExportable}")
            }
        }
        content.addView(out)
    }

    private fun tabWeb() {
        heading("Web: fetch a site, find it in my RAM (§11)")
        note("Use YOUR OWN test URL (e.g. http://192.168.x.x:8000/). The response lands in this " +
            "app's RAM; search for a word from it. HTTPS is encrypted on the wire but plaintext " +
            "here — TLS protects the wire, not your process.")
        val url = input("http://192.168.1.50:8000/").apply { setText("https://example.com/") }
        val word = input("word from the page to find in RAM")
        val out = mono()
        content.addView(url)
        button("Fetch") {
            val u = url.text.toString()
            bg(out) {
                val r = WebProbe.fetch(u); lastWeb = r
                val cat = WebProbe.categorize(r.contentType, r.body)
                set(out, "GET $u\nstatus: ${r.status}  (${if (r.https) "HTTPS" else "HTTP"})\n\n" +
                    "headers:\n${r.headers}\nbody: ${r.body.size} bytes, category=$cat\n\n" +
                    String(r.body.copyOf(minOf(r.body.size, 600)), Charsets.ISO_8859_1))
                AppLog.log("fetched $u -> ${r.status}, ${r.body.size} bytes")
            }
        }
        content.addView(word)
        button("Find response bytes in my RAM") {
            val w = word.text.toString()
            if (lastWeb == null) { append(out, "\nfetch something first"); return@button }
            if (w.isEmpty()) { append(out, "\nenter a word from the page"); return@button }
            bg(out) {
                val hits = MemScan.searchSelf(w.toByteArray(Charsets.UTF_8))
                append(out, "\n'$w' found in my RAM at ${hits.size} address(es)")
                hits.take(8).forEach { append(out, "  0x%x".format(it)) }
                append(out, if (hits.isEmpty()) "not found as UTF-8 bytes" else
                    "-> network data is now RAM bytes you can read (the §11 point).")
                AppLog.log("web-find '$w' -> ${hits.size}")
            }
        }
        content.addView(out)
    }

    private fun tabWalls() {
        heading("The Android isolation walls (§10)")
        content.addView(mono().apply {
            text = """
                * Per-app UID sandbox: each app is its own Linux user. Without root,
                  /proc/<other-pid>/mem is denied — you cannot read another app.
                  (Windows: same-user apps CAN read each other — the thesis contrast.)
                * SELinux adds mandatory access control on top of the UID check.
                * ptrace_scope / Yama restricts debugger attach even same-UID.
                * Android Keystore: keys live in the TEE, or StrongBox (secure element);
                  you get a handle, never the bytes. TEE == Windows TPM/VBS.
                * WebView/renderer content and other apps are out of reach without root.

                These limits ARE the result: they prove the isolation model works.
            """.trimIndent()
        })
    }

    private fun tabLogs() {
        heading("Logs (send these if something breaks)")
        note("Every action and any crash is written to session.log / crash.log. " +
            "Share or copy the text and send it to me so I can see the exact error.")
        val out = mono()
        button("Refresh") { set(out, AppLog.readAll()) }
        button("Share logs") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "MemExplorer logs")
                putExtra(Intent.EXTRA_TEXT, AppLog.readAll())
            }, "Share logs"))
        }
        button("Copy logs") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("MemExplorer logs", AppLog.readAll()))
            append(out, "copied to clipboard")
        }
        button("Clear crash log") { AppLog.clearCrash(); append(out, "crash log cleared") }
        content.addView(out)
        set(out, AppLog.readAll())
    }
}
