package com.blockbridge

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnStatusTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()

    private fun set(field: String, v: Boolean) =
        ShieldVpnService::class.java.getDeclaredField(field).apply { isAccessible = true }.setBoolean(null, v)

    @After fun reset() {
        set("isRunning", false); set("fullTunnel", false)
        VpnClient::class.java.getDeclaredField("tunnels").apply { isAccessible = true }.set(VpnClient, emptyList<Tunnel>())
    }

    @Test fun reportsTheRealState() {
        val s = Settings(ctx)
        assertEquals(VpnStatus.Link.OFF, VpnStatus.of(ctx).link)

        s.vpnEnabled = true
        assertEquals(VpnStatus.Link.NO_SERVER, VpnStatus.of(ctx).link)

        val p = VpnProfiles.add(ctx, "NL-FREE-12", "[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y\nEndpoint=1.2.3.4:51820")
        assertEquals(VpnStatus.Link.PROTECTION_OFF, VpnStatus.of(ctx).link)

        set("isRunning", true) // protection on, but in DNS-only mode
        assertEquals(VpnStatus.Link.UNAVAILABLE, VpnStatus.of(ctx).link)

        set("fullTunnel", true)
        val t = Tunnel(p).apply { upstream = SocksUpstream(1, "u", "p") }
        VpnClient::class.java.getDeclaredField("tunnels").apply { isAccessible = true }.set(VpnClient, listOf(t))
        assertEquals(VpnStatus.Link.CONNECTING, VpnStatus.of(ctx).link) // no handshake yet

        t.lastHandshake = System.currentTimeMillis()
        val st = VpnStatus.of(ctx)
        assertEquals(VpnStatus.Link.CONNECTED, st.link)
        assertEquals("🇳🇱", st.flag)

        t.stop()
        t.error = "handshake timed out"
        val f = VpnStatus.of(ctx)
        assertEquals(VpnStatus.Link.FAILED, f.link)
        assertTrue(f.text, f.text.contains("handshake timed out"))
    }
}
