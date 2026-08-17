package com.shelfit.sentinel.platform.smarthome.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Discovery decoding, driven by packets this test builds itself.
 *
 * The LAN protocol is undocumented, so the decoder is written to be forgiving rather than
 * exact. That only earns its keep if the forgiveness is verified: each test below constructs a
 * datagram in one of the shapes a device might send — encrypted or clear, with or without a
 * return code, 55AA or 6699 — and asserts the device is still found.
 *
 * Building the packets here rather than pasting captured bytes keeps the test readable and
 * means the encryption is exercised in both directions.
 */
class TuyaLanPacketTest {

    private val announcement = """
        {"ip":"192.168.1.42","gwId":"bf1234567890abcdef","active":2,"ablilty":0,
        "encrypt":true,"productKey":"keyAbc123","version":"3.3"}
    """.trimIndent().replace("\n", "")

    // ---- packet builders -------------------------------------------------------------

    private fun encryptEcb(plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(TuyaLanPacket.DiscoveryKey, "AES"),
                null as IvParameterSpec?,
            )
            doFinal(plain)
        }

    /** A 55AA frame: prefix, sequence, command, length, payload, CRC32, suffix. */
    private fun frame55AA(payload: ByteArray, retcode: Boolean): ByteArray {
        val body = if (retcode) ByteArray(4) + payload else payload
        return ByteBuffer.allocate(16 + body.size + 8).apply {
            putInt(0x000055AA)
            putInt(1)                       // sequence
            putInt(0x0A)                    // DP_QUERY-ish; discovery command is not asserted
            putInt(body.size + 8)           // body + CRC + suffix
            put(body)
            putInt(0)                       // CRC32 — not verified by the decoder
            putInt(0x0000AA55)
        }.array()
    }

    /** A 6699 frame: prefix, reserved, sequence, command, length, IV, ciphertext+tag, suffix. */
    private fun frame6699(payload: ByteArray): ByteArray {
        val iv = ByteArray(12) { it.toByte() }
        val header = ByteBuffer.allocate(18).apply {
            putInt(0x00006699)
            putShort(0)                     // reserved
            putInt(7)                       // sequence
            putInt(0x25)                    // REQ_DEVINFO
            putInt(0)                       // length, filled in below
        }.array()

        val aad = header.copyOfRange(4, 18)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(TuyaLanPacket.DiscoveryKey, "AES"),
                GCMParameterSpec(128, iv),
            )
            updateAAD(aad)
            doFinal(payload)
        }

        return ByteBuffer.allocate(18 + iv.size + sealed.size + 4).apply {
            put(header)
            put(iv)
            put(sealed)
            putInt(0x00009966)
        }.array()
    }

    // ---- the constant ----------------------------------------------------------------

    @Test
    fun `the discovery key is the documented static constant`() {
        // MD5("yGAdlopoPVldABfn"), compiled into every Tuya device. Asserted so a refactor
        // of the derivation cannot silently change it.
        assertEquals(
            "6c1ec8e2bb9bb59ab50b0daf649b410a",
            TuyaLanPacket.DiscoveryKey.joinToString("") { "%02x".format(it) },
        )
        assertEquals("AES-128 needs 16 bytes", 16, TuyaLanPacket.DiscoveryKey.size)
    }

    // ---- the shapes a device might send ----------------------------------------------

    @Test
    fun `an encrypted 55AA broadcast is decoded`() {
        val packet = frame55AA(encryptEcb(announcement.toByteArray()), retcode = false)

        val found = TuyaLanPacket.parseAnnouncement(packet)

        assertNotNull(found)
        assertEquals("bf1234567890abcdef", found!!.deviceId)
        assertEquals("192.168.1.42", found.ip)
        assertEquals("3.3", found.protocolVersion)
        assertEquals("keyAbc123", found.productKey)
    }

    @Test
    fun `a return code ahead of the payload does not hide the device`() {
        // Present on device-to-client frames and absent otherwise, and a broadcast is
        // arguably either. Both readings must work.
        val packet = frame55AA(encryptEcb(announcement.toByteArray()), retcode = true)

        assertEquals(
            "bf1234567890abcdef",
            TuyaLanPacket.parseAnnouncement(packet)?.deviceId,
        )
    }

    @Test
    fun `a clear-text 55AA broadcast is decoded`() {
        val packet = frame55AA(announcement.toByteArray(), retcode = false)

        assertEquals("192.168.1.42", TuyaLanPacket.parseAnnouncement(packet)?.ip)
    }

    @Test
    fun `plain JSON on the legacy port is decoded`() {
        // Protocol 3.1 and 3.2 broadcast unwrapped JSON on UDP 6666.
        assertEquals(
            "bf1234567890abcdef",
            TuyaLanPacket.parseAnnouncement(announcement.toByteArray())?.deviceId,
        )
    }

    @Test
    fun `a 6699 GCM broadcast is decoded`() {
        val packet = frame6699(announcement.toByteArray())

        val found = TuyaLanPacket.parseAnnouncement(packet)

        assertNotNull("protocol 3.5 devices must be discoverable too", found)
        assertEquals("bf1234567890abcdef", found!!.deviceId)
    }

    @Test
    fun `a 6699 payload with an encrypted return code is decoded`() {
        val packet = frame6699(ByteArray(4) + announcement.toByteArray())

        assertEquals(
            "bf1234567890abcdef",
            TuyaLanPacket.parseAnnouncement(packet)?.deviceId,
        )
    }

    @Test
    fun `a clear version header ahead of the JSON is skipped`() {
        // 3.3 frames can carry a 15-byte source header before the payload. Scanning to the
        // first brace is more robust than counting bytes that vary by version.
        val header = "3.3".toByteArray() + ByteArray(12)
        val packet = frame55AA(header + announcement.toByteArray(), retcode = false)

        assertEquals("192.168.1.42", TuyaLanPacket.parseAnnouncement(packet)?.ip)
    }

    @Test
    fun `devId is accepted in place of gwId`() {
        val alternative = """{"ip":"10.0.0.5","devId":"bfabc","version":"3.4"}"""

        val found = TuyaLanPacket.parseAnnouncement(alternative.toByteArray())

        assertEquals("bfabc", found?.deviceId)
        assertEquals("3.4", found?.protocolVersion)
    }

    // ---- what must be rejected -------------------------------------------------------

    @Test
    fun `an announcement with no device id is ignored`() {
        // Without an id there is nothing a rule could point at.
        assertNull(TuyaLanPacket.parseAnnouncement("""{"ip":"10.0.0.5"}""".toByteArray()))
    }

    @Test
    fun `an announcement with no address is ignored`() {
        assertNull(TuyaLanPacket.parseAnnouncement("""{"gwId":"bfabc"}""".toByteArray()))
    }

    @Test
    fun `unrelated network noise is ignored rather than throwing`() {
        // The socket sees whatever else broadcasts on the subnet — mDNS, SSDP, DHCP. None of
        // it may crash a scan.
        listOf(
            ByteArray(0),
            byteArrayOf(1, 2, 3),
            "not json at all".toByteArray(),
            ByteArray(64) { 0xFF.toByte() },
            "{".toByteArray(),
            """{"broken":""".toByteArray(),
        ).forEach { noise ->
            assertNull("${noise.size} bytes", TuyaLanPacket.parseAnnouncement(noise))
        }
    }

    @Test
    fun `a truncated 55AA frame is ignored`() {
        assertNull(TuyaLanPacket.parseAnnouncement(ByteArray(20).also {
            it[2] = 0x55; it[3] = 0xAA.toByte()
        }))
    }

    @Test
    fun `a 6699 frame that fails authentication is ignored`() {
        val packet = frame6699(announcement.toByteArray())
        // Flip a ciphertext byte. GCM must reject it rather than yield garbage.
        packet[packet.size - 8] = (packet[packet.size - 8] + 1).toByte()

        assertNull(TuyaLanPacket.parseAnnouncement(packet))
    }

    // ---- the honest state of local control -------------------------------------------

    @Test
    fun `no protocol version claims local control yet`() {
        // Discovery is implemented; control is not. This must report what is *implemented*,
        // so the UI cannot promise a switch it has no code to perform.
        val found = TuyaLanPacket.parseAnnouncement(announcement.toByteArray())

        assertNotNull(found)
        assertTrue(
            "listing a device is not the same as being able to switch it",
            !found!!.controlSupported,
        )
    }
}
