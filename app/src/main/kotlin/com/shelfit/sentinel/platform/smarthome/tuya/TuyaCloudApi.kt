package com.shelfit.sentinel.platform.smarthome.tuya

import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * Which Tuya data centre the project lives in. Shown in the console beside the Access ID.
 *
 * All seven Tuya operates, because a missing one is not a missing feature — it is a user who
 * cannot connect at all, and whose symptom is an authorisation error indistinguishable from a
 * mistyped secret. [code] is the identifier `tinytuya` and Tuya's own tooling use, so a user
 * following either can match their region to the right entry here without guessing.
 */
enum class TuyaRegion(
    val label: String,
    val code: String,
    val endpoint: String,
) {
    CENTRAL_EUROPE("Central Europe", "eu", "https://openapi.tuyaeu.com"),
    WESTERN_EUROPE("Western Europe", "eu-w", "https://openapi-weaz.tuyaeu.com"),
    WESTERN_AMERICA("Western America", "us", "https://openapi.tuyaus.com"),
    EASTERN_AMERICA("Eastern America", "us-e", "https://openapi-ueaz.tuyaus.com"),
    CHINA("China", "cn", "https://openapi.tuyacn.com"),
    INDIA("India", "in", "https://openapi.tuyain.com"),
    SINGAPORE("Singapore", "sg", "https://openapi-sg.iotbing.com"),
    ;

    companion object {
        val Default = CENTRAL_EUROPE

        fun fromName(name: String?): TuyaRegion =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/** What the user has to paste in from the Tuya console. */
data class TuyaCredentials(
    val accessId: String = "",
    val accessSecret: String = "",
    val region: TuyaRegion = TuyaRegion.Default,
) {
    val complete: Boolean get() = accessId.isNotBlank() && accessSecret.isNotBlank()
}

/** A decoded Tuya response: either a result body or a reason it failed. */
internal sealed interface TuyaResponse {
    data class Ok(val json: JSONObject) : TuyaResponse
    data class Error(val failure: SmartHomeFailure) : TuyaResponse
}

/**
 * What [TuyaCloudClient] needs from the network, as a seam.
 *
 * Exists because the first version of this integration shipped with a wrong endpoint and
 * nobody could have noticed: the client built its own HTTP layer, so there was no way to run
 * it against a known Tuya response. Behind this interface a test can hand the client the exact
 * JSON Tuya documents, which is how the endpoint is now pinned.
 */
internal interface TuyaApi {
    fun forgetToken()
    suspend fun verifyCredentials(): TuyaResponse
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): TuyaResponse
    suspend fun post(path: String, body: String): TuyaResponse
}

/**
 * The HTTP half of the Tuya integration: signed requests, JSON in and out.
 *
 * No HTTP or JSON library. `HttpsURLConnection` and `org.json` are both in the Android
 * framework, and the whole surface this app needs is three endpoints — so adding a client
 * library would buy convenience we would pay for in every build and every APK. That is the
 * same reasoning as the hand-written `RuleCodec`.
 *
 * **Every failure is a value.** Nothing here throws for a condition the network can produce:
 * a rule fires from a foreground service that has to survive a flaky Wi-Fi connection for
 * weeks, and an escaping `IOException` would take it down.
 *
 * The access token is cached until it expires and then re-requested. Tuya offers a refresh
 * endpoint, but re-requesting with the client credentials is one code path instead of two and
 * cannot get into a state where a stale refresh token needs its own recovery.
 */
internal class TuyaCloudApi(
    private val credentials: () -> TuyaCredentials,
) : TuyaApi {

    private data class Token(val value: String, val expiresAtMillis: Long)

    private var token: Token? = null

    /** Invalidates the cached token, so the next call fetches a fresh one. */
    override fun forgetToken() {
        token = null
    }

    override suspend fun get(path: String, query: Map<String, String>): TuyaResponse =
        authorised("GET", TuyaSignature.pathWithQuery(path, query), body = null)

    override suspend fun post(path: String, body: String): TuyaResponse =
        authorised("POST", path, body)

    /** Proves the credentials work, without caring about the result. */
    override suspend fun verifyCredentials(): TuyaResponse = when (val fresh = fetchToken()) {
        is TuyaResponse.Error -> fresh
        is TuyaResponse.Ok -> fresh
    }

    private suspend fun authorised(
        method: String,
        pathWithQuery: String,
        body: String?,
    ): TuyaResponse {
        val accessToken = validToken() ?: return when (val fresh = fetchToken()) {
            is TuyaResponse.Error -> fresh
            is TuyaResponse.Ok -> validToken()?.let {
                request(method, pathWithQuery, body, it)
            } ?: TuyaResponse.Error(
                SmartHomeFailure(
                    SmartHomeFailure.Kind.UNKNOWN,
                    detail = "Tuya returned no access token",
                ),
            )
        }

        val response = request(method, pathWithQuery, body, accessToken)

        // A token can be invalidated server-side before it expires — the app account being
        // unlinked, for instance. One retry with a fresh token turns that from a failed
        // automation into a slightly slower one.
        if (response is TuyaResponse.Error &&
            response.failure.kind == SmartHomeFailure.Kind.PERMISSION_DENIED
        ) {
            forgetToken()
            if (fetchToken() is TuyaResponse.Ok) {
                validToken()?.let { return request(method, pathWithQuery, body, it) }
            }
        }

        return response
    }

    private fun validToken(): String? = token
        ?.takeIf { it.expiresAtMillis > System.currentTimeMillis() + TOKEN_MARGIN_MILLIS }
        ?.value

    private suspend fun fetchToken(): TuyaResponse {
        val path = TuyaSignature.pathWithQuery(TOKEN_PATH, mapOf("grant_type" to "1"))
        val response = request("GET", path, body = null, accessToken = null)

        if (response is TuyaResponse.Ok) {
            val result = response.json.optJSONObject("result")
            val value = result?.optString("access_token").orEmpty()
            // Tuya reports the lifetime in seconds.
            val lifetimeSeconds = result?.optLong("expire_time", 0L) ?: 0L
            if (value.isNotEmpty()) {
                token = Token(
                    value = value,
                    expiresAtMillis = System.currentTimeMillis() +
                        (lifetimeSeconds.coerceAtLeast(MIN_TOKEN_SECONDS) * 1_000L),
                )
            }
        }

        return response
    }

    private suspend fun request(
        method: String,
        pathWithQuery: String,
        body: String?,
        accessToken: String?,
    ): TuyaResponse = withContext(Dispatchers.IO) {
        val current = credentials()
        if (!current.complete) {
            return@withContext TuyaResponse.Error(
                SmartHomeFailure(
                    SmartHomeFailure.Kind.NOT_CONNECTED,
                    detail = "Tuya credentials have not been entered",
                ),
            )
        }

        val timestamp = System.currentTimeMillis()
        val payload = body.orEmpty()
        val signature = TuyaSignature.sign(
            clientId = current.accessId,
            accessSecret = current.accessSecret,
            accessToken = accessToken,
            timestampMillis = timestamp,
            method = method,
            pathWithQuery = pathWithQuery,
            body = payload,
        )

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(current.region.endpoint + pathWithQuery).openConnection()
                as HttpsURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("client_id", current.accessId)
                setRequestProperty("sign", signature)
                setRequestProperty("sign_method", "HMAC-SHA256")
                setRequestProperty("t", timestamp.toString())
                setRequestProperty("Content-Type", "application/json")
                accessToken?.let { setRequestProperty("access_token", it) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
            }

            val status = connection.responseCode
            val text = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            decode(status, text)
        } catch (error: UnknownHostException) {
            // The commonest failure by a wide margin, and the one with the clearest message.
            TuyaResponse.Error(SmartHomeFailure(SmartHomeFailure.Kind.NETWORK_UNAVAILABLE))
        } catch (error: IOException) {
            TuyaResponse.Error(
                SmartHomeFailure(
                    SmartHomeFailure.Kind.NETWORK_UNAVAILABLE,
                    detail = error.message,
                ),
            )
        } catch (error: SecurityException) {
            // Missing INTERNET permission would land here rather than at build time.
            TuyaResponse.Error(
                SmartHomeFailure(SmartHomeFailure.Kind.UNKNOWN, detail = error.message),
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Turns a response into a result or a failure.
     *
     * Tuya answers HTTP 200 with `success: false` for application-level problems, so the
     * status code alone is not enough — the body has to be read either way.
     */
    private fun decode(status: Int, text: String): TuyaResponse {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return TuyaResponse.Error(
                SmartHomeFailure(
                    SmartHomeFailure.Kind.UNKNOWN,
                    detail = "Unreadable response (HTTP $status)",
                ),
            )

        if (json.optBoolean("success", false)) return TuyaResponse.Ok(json)

        val code = json.optString("code")
        val message = json.optString("msg").ifEmpty { "Tuya error $code" }
        return TuyaResponse.Error(failureFor(code, message))
    }

    /**
     * Maps a Tuya error onto our own vocabulary, **keeping Tuya's own words**.
     *
     * The detail always carries the provider's code and message verbatim. That is not
     * clutter: a paraphrase of "permission deny" reads as "reconnect your account", which
     * sends a user to fix the one thing that was never broken. Whatever we conclude, the
     * original text stays visible so the real cause is recoverable.
     *
     * Matched on the message as well as the code because Tuya's codes are sparsely
     * documented and vary by endpoint.
     */
    private fun failureFor(code: String, message: String): SmartHomeFailure {
        val lower = message.lowercase()
        val kind = when {
            // Bad keys or a bad clock. Nothing about the account is wrong.
            lower.contains("sign") -> SmartHomeFailure.Kind.PERMISSION_DENIED
            lower.contains("token") -> SmartHomeFailure.Kind.PERMISSION_DENIED

            // "No permissions"/"not subscribed" means a service is not enabled on the cloud
            // project — a console problem, not a consent problem, so it must not be reported
            // as "reconnect".
            lower.contains("not subscrib") || lower.contains("no permission") ->
                SmartHomeFailure.Kind.HOME_UNAVAILABLE

            lower.contains("expire") -> SmartHomeFailure.Kind.HOME_UNAVAILABLE
            lower.contains("permission") -> SmartHomeFailure.Kind.HOME_UNAVAILABLE

            lower.contains("offline") -> SmartHomeFailure.Kind.DEVICE_OFFLINE
            lower.contains("not exist") || lower.contains("not found") ->
                SmartHomeFailure.Kind.DEVICE_REMOVED

            else -> SmartHomeFailure.Kind.COMMAND_REJECTED
        }
        return SmartHomeFailure(
            kind = kind,
            detail = if (code.isEmpty()) message else "Tuya $code: $message",
        )
    }

    private companion object {
        const val TOKEN_PATH = "/v1.0/token"

        /** Renew a little early rather than racing the expiry on a slow connection. */
        const val TOKEN_MARGIN_MILLIS = 60_000L
        const val MIN_TOKEN_SECONDS = 60L

        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
