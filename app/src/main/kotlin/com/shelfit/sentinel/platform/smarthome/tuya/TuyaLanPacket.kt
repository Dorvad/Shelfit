package com.shelfit.sentinel.platform.smarthome.tuya

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A device announcing itself on the local network. */
data class TuyaLanAnnouncement(
    val deviceId: String,
    val ip: String,
    /** "3.3", "3.4", "3.5" — decides which control protocol the device speaks. */
    val protocolVersion: String?,
    val productKey: String?,
) {
    /**
     * Whether this app could drive the device locally today.
     *
     * Only reports what is *implemented*, not what is possible. Announcing a device we
     * cannot yet talk to is still useful — it tells the user which protocol they need.
     */
    val controlSupported: Boolean get() = protocolVersion in SupportedVersions

    companion object {
        /** Empty until the local control protocol lands; see docs/tuya-lan.md. */
        val SupportedVersions: Set<String> = emptySet()
    }
}

/**
 * Decodes Tuya's local-network discovery broadcasts.
 *
 * Tuya devices announce themselves on the LAN every few seconds. Listening costs nothing and
 * yields the three things local control needs — the device id, its current IP, and which
 * protocol version it speaks. The *local key* is the one thing broadcasts never carry, and
 * the one thing only the cloud can give you.
 *
 * ### On this being reverse-engineered
 *
 * Tuya does not document the LAN protocol. The frame layouts below come from the community
 * reference implementations, and the discovery key really is a fixed constant compiled into
 * every device — it is obfuscation, not security, and decoding a broadcast that a device
 * shouted at the whole subnet reveals nothing private.
 *
 * Because it is undocumented, this decoder is **deliberately forgiving**: it tries the
 * plausible interpretations of a datagram and keeps whichever yields valid JSON, rather than
 * asserting one layout and dropping everything else. A device that announces itself in a
 * shape we did not predict should still be found.
 */
internal object TuyaLanPacket {

    /**
     * The static discovery key: `MD5("yGAdlopoPVldABfn")`.
     *
     * Computed rather than pasted as hex, so the derivation is visible and a typo is
     * impossible. Every Tuya device on earth uses it for broadcasts.
     */
    val DiscoveryKey: ByteArray = MessageDigest.getInstance("MD5")
        .digest("yGAdlopoPVldABfn".toByteArray(Charsets.UTF_8))

    private val PREFIX_55AA = byteArrayOf(0x00, 0x00, 0x55, 0xAA.toByte())
    private val PREFIX_6699 = byteArrayOf(0x00, 0x00, 0x66, 0x99.toByte())

    private const val HEADER_55AA = 16
    private const val TRAILER_55AA = 8

    private const val HEADER_6699 = 18
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16
    private const val SUFFIX_LENGTH = 4
    private const val AAD_OFFSET = 4
    private const val RETCODE_LENGTH = 4

    /**
     * @return the announcement, or null if this datagram is not one we recognise.
     */
    fun parseAnnouncement(datagram: ByteArray): TuyaLanAnnouncement? =
        candidatePayloads(datagram)
            .firstNotNullOfOrNull { payload -> toAnnouncement(payload) }

    /**
     * Every reading of the datagram worth attempting, cheapest first.
     *
     * The retcode field is present on device-to-client frames and absent otherwise, and a
     * broadcast is arguably either. Trying both costs one AES block and removes a guess.
     */
    private fun candidatePayloads(datagram: ByteArray): List<ByteArray> = when {
        datagram.startsWith(PREFIX_55AA) -> body55AA(datagram)
        datagram.startsWith(PREFIX_6699) -> body6699(datagram)
        // Protocol 3.1 and 3.2 broadcast plain JSON on UDP 6666.
        else -> listOf(datagram)
    }

    private fun body55AA(datagram: ByteArray): List<ByteArray> {
        if (datagram.size <= HEADER_55AA + TRAILER_55AA) return emptyList()
        val body = datagram.copyOfRange(HEADER_55AA, datagram.size - TRAILER_55AA)
        val withoutRetcode = body.dropFirst(RETCODE_LENGTH)

        return listOfNotNull(
            body,
            withoutRetcode,
            decryptEcb(body),
            withoutRetcode?.let { decryptEcb(it) },
        )
    }

    private fun body6699(datagram: ByteArray): List<ByteArray> {
        val minimum = HEADER_6699 + IV_LENGTH + TAG_LENGTH + SUFFIX_LENGTH
        if (datagram.size <= minimum) return emptyList()

        val iv = datagram.copyOfRange(HEADER_6699, HEADER_6699 + IV_LENGTH)
        // Ciphertext and tag are contiguous, which is what the JDK's GCM expects.
        val cipherEnd = datagram.size - SUFFIX_LENGTH
        val cipherText = datagram.copyOfRange(HEADER_6699 + IV_LENGTH, cipherEnd)
        val aad = datagram.copyOfRange(AAD_OFFSET, HEADER_6699)

        val plain = decryptGcm(cipherText, iv, aad) ?: return emptyList()

        // The 4-byte return code sits inside the encrypted region in a 6699 frame.
        return listOfNotNull(plain, plain.dropFirst(RETCODE_LENGTH))
    }

    private fun toAnnouncement(payload: ByteArray): TuyaLanAnnouncement? {
        val text = payload.asJsonText() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

        // Tuya uses gwId on most devices and devId on some. Accepting both is one line.
        val deviceId = json.optNonEmpty("gwId")
            ?: json.optNonEmpty("devId")
            ?: return null
        val ip = json.optNonEmpty("ip") ?: return null

        return TuyaLanAnnouncement(
            deviceId = deviceId,
            ip = ip,
            protocolVersion = json.optNonEmpty("version"),
            productKey = json.optNonEmpty("productKey"),
        )
    }

    private fun decryptEcb(input: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(DiscoveryKey, "AES"), null as IvParameterSpec?)
            doFinal(input)
        }
    }.getOrNull()

    private fun decryptGcm(input: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray? =
        runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(DiscoveryKey, "AES"),
                    GCMParameterSpec(TAG_LENGTH * 8, iv),
                )
                updateAAD(aad)
                doFinal(input)
            }
        }.getOrNull()

    /**
     * The payload as text, or null if it is clearly not JSON.
     *
     * Trims from the first `{`: some frames carry a clear version header ahead of the
     * payload, and skipping to the brace is more robust than counting its bytes.
     */
    private fun ByteArray.asJsonText(): String? {
        val start = indexOf('{'.code.toByte())
        if (start < 0) return null
        val text = String(this, start, size - start, Charsets.UTF_8)
        val end = text.lastIndexOf('}')
        return if (end < 0) null else text.substring(0, end + 1)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private fun ByteArray.dropFirst(count: Int): ByteArray? =
        if (size > count) copyOfRange(count, size) else null

    private fun ByteArray.indexOf(byte: Byte): Int = indexOfFirst { it == byte }

    private fun JSONObject.optNonEmpty(key: String): String? =
        optString(key).takeIf { it.isNotEmpty() }
}
