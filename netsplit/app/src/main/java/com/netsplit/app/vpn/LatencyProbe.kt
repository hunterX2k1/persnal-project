package com.netsplit.app.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Measures round-trip time with tiny DNS queries (~40 bytes) to a few servers at once and returns
 * the fastest answer. NetSplit itself is never inside its VPN, so these go straight over the mobile
 * link and sit in the same queue as the game's packets.
 */
class LatencyProbe(targets: List<InetAddress>) : AutoCloseable {
    private val targets = targets.distinct().take(3)
    private val sock = DatagramSocket()
    private val query: ByteArray

    init {
        // Standard query for "google.com" A.
        val name = byteArrayOf(6) + "google".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        query = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + name + byteArrayOf(0, 1, 0, 1)
    }

    /** @return the best RTT in ms, or null if nothing answered within [timeoutMs]. */
    fun probe(timeoutMs: Int): Double? {
        val id = Random.nextInt(0x10000)
        query[0] = (id ushr 8).toByte(); query[1] = id.toByte()
        val start = System.nanoTime()
        for (t in targets) {
            try {
                sock.send(DatagramPacket(query, query.size, InetSocketAddress(t, 53)))
            } catch (_: Exception) {}
        }
        val buf = ByteArray(512)
        val deadline = start + timeoutMs * 1_000_000L
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) return null
            sock.soTimeout = left.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try {
                sock.receive(p)
            } catch (_: SocketTimeoutException) {
                return null
            } catch (_: Exception) {
                return null
            }
            if (p.length >= 2 && Pk.u16(buf, 0) == id) {
                return (System.nanoTime() - start) / 1e6
            }
        }
    }

    override fun close() = sock.close()
}
