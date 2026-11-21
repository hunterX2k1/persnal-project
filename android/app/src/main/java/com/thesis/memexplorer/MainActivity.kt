package com.thesis.memexplorer

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Memory Explorer (Android) — the root-free, self-inspection analogue of the
 * Windows tool. It reads only THIS app's memory; the Android sandbox prevents
 * reading other apps without root. UI is built in code to keep the project tiny.
 */
class MainActivity : Activity() {

    companion object {
        const val MARKER = "THESIS_MARKER_7f3a9c"
    }

    // Crypto-demo buffers kept as fields so they live in the heap and are findable.
    private var key: ByteArray? = null
    private var iv: ByteArray? = null
    private var plaintext: ByteArray? = null
    private var ciphertext: ByteArray? = null

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        out = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        fun btn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { runInBackground(action) }
            })
        }

        btn("1. Scan my own memory regions") { scanRegions() }
        btn("2. Crypto demo: put key+secret in RAM, find them") { cryptoDemo() }
        btn("3. Wipe the key, search again") { wipeAndRecheck() }
        btn("4. The Android walls (why no other app)") { showWalls() }

        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(out)
        }
        root.addView(sv)
        root.addView(TextView(this).apply {
            text = "Own-process inspection only — your data, your device."
            gravity = Gravity.CENTER
            textSize = 10f
        })
        setContentView(root)

        log("Memory Explorer — PID ${android.os.Process.myPid()}")
        log("Tap a button. This app reads only its OWN memory.\n")
    }

    private fun runInBackground(action: () -> Unit) {
        Thread {
            try {
                action()
            } catch (e: Exception) {
                log("error: ${e.message}")
            }
        }.start()
    }

    private fun log(s: String) = runOnUiThread { out.append(s + "\n") }

    // --- 1. scan own regions ------------------------------------------------
    private fun scanRegions() {
        log("=== my memory regions (/proc/self/maps) ===")
        val regions = MemScan.readMaps()
        val byLabel = HashMap<String, Long>()
        val byKind = HashMap<String, Long>()
        var readable = 0
        RandomAccessFile("/proc/self/mem", "r").use { raf ->
            for (r in regions) {
                byKind[r.kind] = (byKind[r.kind] ?: 0) + r.size
                val sample = MemScan.readRegion(raf, r, 64 * 1024) ?: continue
                readable++
                val label = MemScan.classify(sample, sample.size, r.executable)
                byLabel[label] = (byLabel[label] ?: 0) + r.size
            }
        }
        log("regions: ${regions.size} total, $readable readable\n")
        log("by region kind (OS metadata):")
        byKind.entries.sortedByDescending { it.value }.forEach {
            log("  %-10s %,10d KiB".format(it.key, it.value / 1024))
        }
        log("\nby content (sampled bytes):")
        byLabel.entries.sortedByDescending { it.value }.forEach {
            log("  %-26s %,10d KiB".format(it.key, it.value / 1024))
        }
        log("")
    }

    // --- 2. crypto demo -----------------------------------------------------
    private fun cryptoDemo() {
        val rnd = java.security.SecureRandom()
        val k = ByteArray(32).also { rnd.nextBytes(it) }
        val i = ByteArray(16).also { rnd.nextBytes(it) }
        val pt = ("$MARKER: account=demo-user; password=Fake-Passw0rd!; synthetic test data")
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(i))
        val ct = cipher.doFinal(pt)
        key = k; iv = i; plaintext = pt; ciphertext = ct

        log("=== crypto demo: plaintext + AES-256 key + IV + ciphertext now in RAM ===")
        log("key (hex) : ${k.toHex()}")
        log("marker    : $MARKER\n")
        val markerHits = MemScan.searchSelf(MARKER.toByteArray(Charsets.UTF_8))
        val keyHits = MemScan.searchSelf(k)
        log("marker found at ${markerHits.size} address(es) in my own RAM")
        keyHits.take(3).forEach { log("  marker/key @ 0x%x".format(it)) }
        log("key bytes found at ${keyHits.size} address(es)")
        log("ART, like Python, may keep hidden copies — note how many. Now try button 3.\n")
    }

    // --- 3. wipe + recheck --------------------------------------------------
    private fun wipeAndRecheck() {
        val k = key
        if (k == null) { log("run the crypto demo (button 2) first.\n"); return }
        log("=== wiping key + plaintext (fill with zeros) ===")
        java.util.Arrays.fill(k, 0)
        plaintext?.let { java.util.Arrays.fill(it, 0) }
        val keyHits = MemScan.searchSelf(k) // all-zero now; expect the buffer itself gone
        val markerHits = MemScan.searchSelf(MARKER.toByteArray(Charsets.UTF_8))
        log("after wipe: original key bytes found at ${keyHits.size} address(es)")
        log("after wipe: marker still found at ${markerHits.size} address(es)")
        log(if (markerHits.isEmpty())
            "clean — no copies remain."
        else
            "leftover copies remain (ART temporaries / String interning) — a thesis finding: " +
            "wiping your own buffer does not erase every copy on a managed runtime.\n")
    }

    // --- 4. the walls -------------------------------------------------------
    private fun showWalls() {
        log(
            """
            === the Android isolation walls (plan section 6, Android side) ===

            * Per-app UID sandbox: every app runs as its own Linux user. Without
              root, /proc/<other-pid>/mem is denied — you CANNOT read another
              app, which is why this tool is self-only. (Windows: same-user apps
              can read each other — the thesis contrast.)
            * SELinux adds mandatory access control on top of the UID check.
            * ptrace_scope / Yama restricts debugger-style attach even same-UID.
            * Android Keystore: keys live in the TEE (hardware-backed) or, with
              StrongBox, a dedicated secure element — never in your app's RAM.
              You get a handle, not the key bytes. TEE == Windows TPM/VBS.
            * To read other apps you would need root (userdebug/Magisk) or a full
              RAM capture off-device — out of scope for own-device, non-root work.

            So on stock Android the honest demo is: see your OWN data clearly in
            RAM, and show that the OS wall stops everything else.
            """.trimIndent() + "\n"
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
