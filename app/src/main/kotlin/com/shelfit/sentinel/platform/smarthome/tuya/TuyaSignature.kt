package com.shelfit.sentinel.platform.smarthome.tuya

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs Tuya Cloud API requests.
 *
 * Pure Kotlin with no Android imports, deliberately: this is the one part of the Tuya
 * integration where a bug is both fatal and invisible. A wrong signature comes back as a
 * generic authorisation error that looks like bad credentials, so it is separated out and
 * tested against Tuya's published formula rather than debugged against a live account.
 *
 * The scheme, as Tuya documents it:
 *
 * ```
 * stringToSign = method + "\n" + sha256(body) + "\n" + signatureHeaders + "\n" + pathWithQuery
 *
 * token request:    str = clientId +               t + nonce + stringToSign
 * business request: str = clientId + accessToken + t + nonce + stringToSign
 *
 * sign = uppercase(hex(hmacSha256(str, secret)))
 * ```
 *
 * The only difference between the two is the access token, which is why both go through one
 * function with a nullable token rather than two that could drift apart.
 */
internal object TuyaSignature {

    /** Tuya requires uppercase hex. Lower case is rejected as an invalid signature. */
    private const val HEX = "0123456789ABCDEF"

    /**
     * @param accessToken null for the token request itself, present for every other call.
     * @param nonce optional per Tuya's documentation; an empty string contributes nothing to
     *   the signed material, which is the common case.
     */
    fun sign(
        clientId: String,
        accessSecret: String,
        accessToken: String? = null,
        timestampMillis: Long,
        nonce: String = "",
        method: String,
        pathWithQuery: String,
        body: String = "",
    ): String {
        val stringToSign = buildString {
            append(method.uppercase()).append('\n')
            append(sha256Hex(body).lowercase()).append('\n')
            // Signature-Headers content. Empty unless custom headers are being signed, which
            // leaves a blank line — that line is part of the format, not an accident.
            append('\n')
            append(pathWithQuery)
        }

        val material = clientId + accessToken.orEmpty() + timestampMillis + nonce + stringToSign

        return hmacSha256Hex(material, accessSecret)
    }

    /**
     * Path plus query string with parameters sorted by key.
     *
     * The sort is required, not cosmetic: Tuya signs this string, so a different ordering
     * than the one sent produces a signature mismatch.
     */
    fun pathWithQuery(path: String, query: Map<String, String> = emptyMap()): String {
        if (query.isEmpty()) return path
        val sorted = query.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) -> "$key=$value" }
        return "$path?$sorted"
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

    private fun hmacSha256Hex(value: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
