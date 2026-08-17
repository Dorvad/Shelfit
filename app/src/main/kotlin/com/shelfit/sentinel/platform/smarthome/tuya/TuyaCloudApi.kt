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

/** Which Tuya data centre the project lives in. Shown in the console beside the Access ID. */
enum class TuyaRegion(val label: String, val endpoint: String) {
    CENTRAL_EUROPE("Central Europe", "https://openapi.tuyaeu.com"),
    WESTERN_AMERICA("Western America", "https://openapi.tuyaus.com"),
    CHINA("China", "https://openapi.tuyacn.com"),
    INDIA("India", "https://openapi.tuyain.com"),
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
) {

    private data class Token(val value: String, val expiresAtMillis: Long)

    private var token: Token? = null

    /** Invalidates the cached token, so the next call fetches a fresh one. */
    fun forgetToken() {
        token = null
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): TuyaResponse =
        authorised("GET", TuyaSignature.pathWithQuery(path, query), body = null)

    suspend fun post(path: String, body: String): TuyaResponse =
        authorised("POST", path, body)

    /** Proves the credentials work, without caring about the result. */
    suspend fun verifyCredentials(): TuyaResponse = when (val fresh = fetchToken()) {
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

    /** The uid of the linked app account, needed to list devices. Read from the token call. */
    suspend fun linkedUid(): String? {
        val response = request(
            method = "GET",
            pathWithQuery = TuyaSignature.pathWithQuery(TOKEN_PATH, mapOf("grant_type" to "1")),
            body = null,
            accessToken = null,
        )
        return (response as? TuyaResponse.Ok)
            ?.json?.optJSONObject("result")
            ?.optString("uid")
            ?.takeIf { it.isNotEmpty() }
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
     * Maps a Tuya error onto our own vocabulary.
     *
     * Matched on the message as well as the numeric code: Tuya's codes are stable but sparsely
     * documented, and a token problem reported under an unfamiliar code should still lead the
     * user to reconnect rather than to a shrug.
     */
    private fun failureFor(code: String, message: String): SmartHomeFailure {
        val lower = message.lowercase()
        val kind = when {
            lower.contains("token") -> SmartHomeFailure.Kind.PERMISSION_DENIED
            lower.contains("sign") -> SmartHomeFailure.Kind.PERMISSION_DENIED
            lower.contains("permission") -> SmartHomeFailure.Kind.PERMISSION_DENIED
            lower.contains("authoriz") || lower.contains("authoris") ->
                SmartHomeFailure.Kind.PERMISSION_DENIED

            // A lapsed IoT Core subscription is the single most likely cause of an
            // automation that worked last month and does not today.
            lower.contains("expire") -> SmartHomeFailure.Kind.PERMISSION_DENIED
            lower.contains("offline") -> SmartHomeFailure.Kind.DEVICE_OFFLINE
            lower.contains("not exist") || lower.contains("not found") ->
                SmartHomeFailure.Kind.DEVICE_REMOVED

            else -> SmartHomeFailure.Kind.COMMAND_REJECTED
        }
        return SmartHomeFailure(kind = kind, detail = message)
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
