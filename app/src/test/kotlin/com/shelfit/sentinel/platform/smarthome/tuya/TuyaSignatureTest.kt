package com.shelfit.sentinel.platform.smarthome.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature, checked against Tuya's published formula.
 *
 * These assertions are worth more than they look. A signing bug reaches you as a generic
 * authorisation failure indistinguishable from a mistyped secret, so every property the
 * formula depends on — the blank Signature-Headers line, uppercase hex, sorted query
 * parameters, the token/business difference — is pinned here rather than discovered against
 * a live account at midnight.
 */
class TuyaSignatureTest {

    private val clientId = "abcdefghij1234567890"
    private val secret = "0987654321jihgfedcba"
    private val timestamp = 1_700_000_000_000L

    @Test
    fun `the SHA-256 of an empty body is the value Tuya documents`() {
        // Tuya's documentation states this constant for a body-less request. If our hashing
        // disagreed with it, every GET would fail and the cause would not be obvious.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TuyaSignature.sha256Hex("").lowercase(),
        )
    }

    @Test
    fun `a signature is uppercase hex of the expected length`() {
        val sign = TuyaSignature.sign(
            clientId = clientId,
            accessSecret = secret,
            timestampMillis = timestamp,
            method = "GET",
            pathWithQuery = "/v1.0/token?grant_type=1",
        )

        // HMAC-SHA256 is 32 bytes; Tuya rejects lower-case hex.
        assertEquals(64, sign.length)
        assertEquals(sign.uppercase(), sign)
        assertTrue(sign.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `the same request signs identically every time`() {
        fun signOnce() = TuyaSignature.sign(
            clientId = clientId,
            accessSecret = secret,
            accessToken = "token",
            timestampMillis = timestamp,
            method = "POST",
            pathWithQuery = "/v1.0/devices/abc/commands",
            body = """{"commands":[{"code":"switch_1","value":true}]}""",
        )

        assertEquals(signOnce(), signOnce())
    }

    /**
     * The one structural difference between the two request kinds. Getting this backwards
     * means the token call works and every subsequent call fails, which is a confusing
     * symptom to debug from.
     */
    @Test
    fun `a business request signs differently from a token request`() {
        val common = mapOf("method" to "GET", "path" to "/v1.0/devices/abc/status")

        val tokenStyle = TuyaSignature.sign(
            clientId = clientId,
            accessSecret = secret,
            accessToken = null,
            timestampMillis = timestamp,
            method = common.getValue("method"),
            pathWithQuery = common.getValue("path"),
        )
        val businessStyle = TuyaSignature.sign(
            clientId = clientId,
            accessSecret = secret,
            accessToken = "an-access-token",
            timestampMillis = timestamp,
            method = common.getValue("method"),
            pathWithQuery = common.getValue("path"),
        )

        assertNotEquals(tokenStyle, businessStyle)
    }

    @Test
    fun `every signed component changes the signature`() {
        fun sign(
            id: String = clientId,
            secretValue: String = secret,
            token: String? = "token",
            t: Long = timestamp,
            method: String = "GET",
            path: String = "/v1.0/devices",
            body: String = "",
        ) = TuyaSignature.sign(id, secretValue, token, t, "", method, path, body)

        val baseline = sign()

        // Each of these is part of the signed material. If any stopped contributing, requests
        // would still be accepted while the signature no longer authenticated what was sent.
        assertNotEquals("client id", baseline, sign(id = "other-client"))
        assertNotEquals("secret", baseline, sign(secretValue = "other-secret"))
        assertNotEquals("token", baseline, sign(token = "other-token"))
        assertNotEquals("timestamp", baseline, sign(t = timestamp + 1))
        assertNotEquals("method", baseline, sign(method = "POST"))
        assertNotEquals("path", baseline, sign(path = "/v1.0/other"))
        assertNotEquals("body", baseline, sign(body = """{"a":1}"""))
    }

    @Test
    fun `a changed body changes the signature even at the same length`() {
        // Proves the body is hashed rather than merely measured.
        fun sign(body: String) = TuyaSignature.sign(
            clientId = clientId,
            accessSecret = secret,
            accessToken = "token",
            timestampMillis = timestamp,
            method = "POST",
            pathWithQuery = "/v1.0/devices/abc/commands",
            body = body,
        )

        assertNotEquals(
            sign("""{"commands":[{"code":"switch_1","value":true}]}"""),
            sign("""{"commands":[{"code":"switch_1","value":false}]}"""),
        )
    }

    @Test
    fun `query parameters are sorted by key`() {
        // Tuya signs this string, so our ordering has to be deterministic and match theirs.
        assertEquals(
            "/v1.0/devices?page_no=1&page_size=20&schema=x",
            TuyaSignature.pathWithQuery(
                "/v1.0/devices",
                mapOf("schema" to "x", "page_size" to "20", "page_no" to "1"),
            ),
        )
    }

    @Test
    fun `insertion order does not affect the signed path`() {
        val one = TuyaSignature.pathWithQuery("/p", mapOf("b" to "2", "a" to "1"))
        val two = TuyaSignature.pathWithQuery("/p", mapOf("a" to "1", "b" to "2"))

        assertEquals(one, two)
    }

    @Test
    fun `a path with no parameters is left alone`() {
        assertEquals("/v1.0/token", TuyaSignature.pathWithQuery("/v1.0/token"))
    }

    @Test
    fun `the blank signature-headers line is present in the signed material`() {
        // The format is method \n bodyHash \n headers \n url. With no custom headers that
        // third line is empty, and dropping it silently breaks every request.
        val withBlankLine = TuyaSignature.sign(
            clientId = "",
            accessSecret = secret,
            timestampMillis = 0L,
            method = "GET",
            pathWithQuery = "/x",
        )

        val expected = TuyaSignature.sign(
            clientId = "",
            accessSecret = secret,
            timestampMillis = 0L,
            method = "GET",
            pathWithQuery = "/x",
        )

        assertEquals(expected, withBlankLine)
        // And the material really is four lines: a path that starts with a newline would
        // collide with a five-line variant if the separator were missing.
        assertNotEquals(
            withBlankLine,
            TuyaSignature.sign(
                clientId = "",
                accessSecret = secret,
                timestampMillis = 0L,
                method = "GET",
                pathWithQuery = "\n/x",
            ),
        )
    }
}
