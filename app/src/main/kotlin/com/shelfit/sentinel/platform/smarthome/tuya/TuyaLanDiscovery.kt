package com.shelfit.sentinel.platform.smarthome.tuya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Listens for Tuya devices announcing themselves on the local network.
 *
 * Read-only and passive: devices broadcast unprompted every few seconds, so this opens a
 * socket and waits. Nothing is sent, nothing is probed, no credential is involved — which is
 * why it works before any cloud setup has been done.
 *
 * The flow is **cold and cancellation-safe**, like the audio input: collecting opens the
 * sockets, cancelling closes them. Same discipline as the microphone, for the same reason — a
 * listener left open on a phone running for weeks is a leak.
 *
 * What this yields is the device id, the current IP and the protocol version. The **local
 * key** is not broadcast and cannot be obtained here; see `docs/tuya-lan.md`.
 */
class TuyaLanDiscovery {

    /**
     * Announcements from every port Tuya uses, merged.
     *
     * Duplicates are expected — a device rebroadcasts, and may appear on more than one port.
     * De-duplicating belongs to the caller, which knows whether it wants a live list or a
     * one-shot scan.
     */
    fun announcements(): Flow<TuyaLanAnnouncement> = merge(
        listen(PORT_LEGACY),
        listen(PORT_ENCRYPTED),
        listen(PORT_V35),
    )

    private fun listen(port: Int): Flow<TuyaLanAnnouncement> = channelFlow {
        val socket = try {
            DatagramSocket(null).apply {
                // Other apps on the phone may want the same broadcasts, and a previous run's
                // socket can still be lingering. Without this, a scan fails for a reason that
                // has nothing to do with the network.
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(port))
            }
        } catch (error: SocketException) {
            // A port held by something else is not worth failing the whole scan for — the
            // other ports may still turn up devices.
            return@channelFlow
        }

        // receive() blocks and cannot observe cancellation, so the loop runs in its own
        // coroutine and awaitClose below closes the socket to break it.
        launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_BYTES)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (error: IOException) {
                    // Expected on cancellation: the socket was closed underneath us.
                    break
                }
                TuyaLanPacket
                    .parseAnnouncement(packet.data.copyOf(packet.length))
                    ?.let { trySend(it) }
            }
        }

        awaitClose { socket.close() }
    }

    private companion object {
        /** Protocol 3.1 and 3.2 broadcast clear JSON here. */
        const val PORT_LEGACY = 6666

        /** Protocol 3.3 and later, AES-encrypted with the well-known static key. */
        const val PORT_ENCRYPTED = 6667

        /** Protocol 3.5 uses a separate port for its solicit and reply exchange. */
        const val PORT_V35 = 7000

        /** Announcements are a few hundred bytes; this is generous. */
        const val BUFFER_BYTES = 2048
    }
}
