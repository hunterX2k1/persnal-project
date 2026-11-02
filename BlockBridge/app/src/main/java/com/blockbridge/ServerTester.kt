package com.blockbridge

import android.content.Context
import org.json.JSONObject

/**
 * Tests VPN servers before you connect: for each one it connects briefly, measures ping
 * and a short download, and reads which country/IP websites see. Results are kept so the
 * VPN tab can show them next to each server.
 */
object ServerTester {
    class Result(
        val time: Long, val pingMs: Double, val downMbps: Double,
        val exit: String, val ip: String, val error: String?,
    ) {
        val ok get() = error == null
    }

    @Volatile var running = false
        private set
    @Volatile var current: String? = null
        private set
    @Volatile private var stop = false

    fun cancel() { stop = true }

    fun result(ctx: Context, id: String): Result? = runCatching {
        val o = JSONObject(prefs(ctx).getString("test_$id", null) ?: return null)
        Result(o.getLong("t"), o.getDouble("ping"), o.getDouble("down"), o.optString("exit"),
            o.optString("ip"), o.optString("error").ifEmpty { null })
    }.getOrNull()

    private fun save(ctx: Context, id: String, r: Result) = prefs(ctx).edit().putString("test_$id",
        JSONObject().put("t", r.time).put("ping", r.pingMs).put("down", r.downMbps).put("exit", r.exit)
            .put("ip", r.ip).put("error", r.error ?: "").toString()).apply()

    /**
     * Tests [profiles] one by one. Blocking. Servers in use by the running VPN are skipped
     * (a WireGuard key can't be connected twice, and only one Tor can run).
     */
    fun testAll(ctx: Context, profiles: List<VpnProfile>, onUpdate: () -> Unit) {
        if (running) return
        running = true
        stop = false
        try {
            val inUse = VpnClient.tunnels.map { it.profile.id }.toSet()
            val torBusy = VpnClient.tunnels.any { it.isTor }
            for (p in profiles) {
                if (stop) break
                if (p.id in inUse || (TorClient.isTor(p) && torBusy)) continue
                current = p.id
                onUpdate()
                save(ctx, p.id, testOne(ctx, p))
                onUpdate()
            }
        } finally {
            current = null
            running = false
            onUpdate()
        }
    }

    private fun testOne(ctx: Context, p: VpnProfile): Result {
        val t = Tunnel(p)
        val now = System.currentTimeMillis()
        try {
            if (!t.start(ctx)) return Result(now, -1.0, 0.0, "", "", t.error ?: "couldn't connect")
            // WireGuard: wait for the server to answer
            val deadline = System.currentTimeMillis() + 15_000
            while (!t.isTor && t.lastHandshake == 0L && System.currentTimeMillis() < deadline && !stop) Thread.sleep(250)
            if (!t.isTor && t.lastHandshake == 0L) return Result(now, -1.0, 0.0, "", "", "server didn't answer")
            val up = t.upstream ?: return Result(now, -1.0, 0.0, "", "", "not connected")
            val r = SpeedTest(Dialers.via(up), streams = 2, phaseMillis = 4_000).run(p.name, withUpload = false)
            DiagLog.add("tester", "${p.name}: ${"%.0f".format(r.pingMs)} ms, ${"%.1f".format(r.downMbps)} Mbps, ${r.server}")
            return Result(now, r.pingMs, r.downMbps, r.server, r.ip, null)
        } catch (e: Exception) {
            DiagLog.add("tester", "${p.name}: failed ${e.message}")
            return Result(now, -1.0, 0.0, "", "", e.message ?: e.javaClass.simpleName)
        } finally {
            t.stop()
            if (t.isTor) TorClient.stop()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("server_tests", Context.MODE_PRIVATE)
}
