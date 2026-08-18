package com.shelfit.sentinel.platform.smarthome.tuya

import com.shelfit.sentinel.core.smarthome.DeviceCommandReport
import com.shelfit.sentinel.core.smarthome.DeviceCommandResult
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.DeviceKind
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeDevice
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tuya as a smart-home provider, over the Cloud API.
 *
 * One of exactly three files that may know Tuya exists — this one, [TuyaCloudApi] and
 * [TuyaSignature]. Above the [SmartHomeClient] interface the app has no idea which provider is
 * in use, which is why adding Tuya changed no audio code, no rule code and no UI.
 *
 * Chosen over Tuya's Smart Life App SDK deliberately. That SDK would work, but it brings
 * fastjson, okhttp, native libraries for two ABIs and an embedded V8 JavaScript engine into an
 * app whose entire premise is running quietly on an old phone for weeks. Three endpoints over
 * `HttpsURLConnection` cost nothing and are fully inspectable.
 *
 * ### Deliberate simplifications
 *
 * **One structure.** Tuya's model is an account with devices; this app's is homes containing
 * devices. Rather than guess at a homes API, the linked account is presented as a single
 * structure. If per-home separation is wanted later, it belongs here and nowhere else.
 *
 * **Every command reads state first.** Tuya does not expose a uniform on/off: a bulb answers
 * to `switch_led`, a socket to `switch_1`, a plug to `switch`. The code has to be discovered
 * from the device's own status, and reading it also gives the current value that
 * [SmartHomeCommand.TOGGLE] needs. That is one extra round trip on ON and OFF, and it buys
 * correctness across devices this code has never seen.
 */
class TuyaCloudClient internal constructor(
    credentials: () -> TuyaCredentials,
    private val api: TuyaApi,
) : SmartHomeClient {

    constructor(credentials: () -> TuyaCredentials) : this(credentials, TuyaCloudApi(credentials))

    private val readCredentials = credentials

    private val _state = MutableStateFlow<SmartHomeState>(SmartHomeState.NotConnected)
    override val state: StateFlow<SmartHomeState> = _state.asStateFlow()

    override val available: Boolean = true

    /** Serialises connect/refresh so two screens cannot fight over the cached uid. */
    private val mutex = Mutex()

    private var connected = false

    override suspend fun connect(): SmartHomeResult<Unit> = mutex.withLock {
        if (!readCredentials().complete) {
            _state.value = SmartHomeState.NotConnected
            return@withLock SmartHomeResult.Failure(
                SmartHomeFailure(
                    SmartHomeFailure.Kind.NOT_CONNECTED,
                    detail = "Enter your Tuya Access ID and Access Secret first",
                ),
            )
        }

        api.forgetToken()

        // Two separate questions, reported separately, because they have different fixes.
        // "Are the keys and data centre right" is answered by the token call; "can this
        // project see your devices" is answered by the device list. Collapsing them is how a
        // console problem ends up reported as a credentials problem.
        when (val token = api.verifyCredentials()) {
            is TuyaResponse.Error -> {
                _state.value = SmartHomeState.Unavailable(token.failure)
                return@withLock SmartHomeResult.Failure(token.failure)
            }

            is TuyaResponse.Ok -> Unit
        }

        when (val listed = fetchDevices()) {
            is SmartHomeResult.Failure -> {
                _state.value = SmartHomeState.Unavailable(listed.failure)
                return@withLock SmartHomeResult.Failure(listed.failure)
            }

            is SmartHomeResult.Success -> {
                if (listed.value.isEmpty()) {
                    // The keys work and the call is permitted, but the project is not
                    // associated with any app account — the commonest setup mistake, and
                    // precisely distinguishable from every other failure.
                    val failure = SmartHomeFailure(
                        SmartHomeFailure.Kind.HOME_UNAVAILABLE,
                        detail = "Your keys work, but no devices are shared with this cloud " +
                            "project. In the Tuya console, open your project's Devices tab " +
                            "and use Link Tuya App Account — not the Users tab.",
                    )
                    _state.value = SmartHomeState.Unavailable(failure)
                    return@withLock SmartHomeResult.Failure(failure)
                }
                connected = true
                _state.value = SmartHomeState.Connected(structures = listOf(ACCOUNT_STRUCTURE))
                SmartHomeResult.Success(Unit)
            }
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            connected = false
            api.forgetToken()
            _state.value = SmartHomeState.NotConnected
        }
    }

    override suspend fun refresh() {
        // Only re-check when we believe we are connected. Refreshing an unconfigured provider
        // on every screen resume would make pointless network calls on a phone left running.
        if (!connected && !_state.value.isConnected) return
        connect()
    }

    override suspend fun selectStructure(structureId: StructureId) {
        // One structure, so there is nothing to choose. Left as a no-op rather than an error:
        // the caller is a generic UI that does not know this provider is single-home.
    }

    override suspend fun devices(
        structureId: StructureId,
    ): SmartHomeResult<List<SmartHomeDevice>> {
        if (!connected) {
            return SmartHomeResult.Failure(
                SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED),
            )
        }
        return when (val listed = fetchDevices()) {
            is SmartHomeResult.Failure -> {
                _state.value = SmartHomeState.Unavailable(listed.failure)
                SmartHomeResult.Failure(listed.failure)
            }

            is SmartHomeResult.Success ->
                SmartHomeResult.Success(listed.value.map(::toDevice))
        }
    }

    /**
     * Every device shared with this cloud project, as raw JSON.
     *
     * Uses `/v1.0/iot-01/associated-users/devices`, which needs **no user id**. The obvious
     * alternative, `/v1.0/users/{uid}/devices`, needs the linked app account's id — and the
     * `uid` in a client-credentials token response is the *project's* identifier, not that
     * account's. Using it produces a permission error that reads exactly like bad keys, which
     * is a genuinely misleading dead end. This endpoint sidesteps the whole question.
     *
     * Paged, because a large home exceeds one response. The page cap is a guard against a
     * malformed `has_more` looping for ever rather than a real limit.
     */
    private suspend fun fetchDevices(): SmartHomeResult<List<JSONObject>> {
        val collected = mutableListOf<JSONObject>()
        var lastRowKey: String? = null

        repeat(MAX_PAGES) {
            val query = buildMap {
                put("size", PAGE_SIZE.toString())
                lastRowKey?.let { put("last_row_key", it) }
            }

            when (val response = api.get(DEVICE_LIST_PATH, query)) {
                is TuyaResponse.Error -> return SmartHomeResult.Failure(response.failure)

                is TuyaResponse.Ok -> {
                    val result = response.json.optJSONObject("result")
                        ?: return SmartHomeResult.Success(collected)

                    // The all-devices form returns "devices"; the by-user form returns "list".
                    // Accepting both costs one line and survives Tuya changing which it uses.
                    val page = (result.optJSONArray("devices") ?: result.optJSONArray("list"))
                        .objects()
                    collected += page

                    val hasMore = result.optBoolean("has_more", false)
                    lastRowKey = result.optString("last_row_key").ifEmpty { null }
                    if (!hasMore || lastRowKey == null || page.isEmpty()) {
                        return SmartHomeResult.Success(collected)
                    }
                }
            }
        }

        return SmartHomeResult.Success(collected)
    }

    override suspend fun execute(
        command: SmartHomeCommand,
        targets: List<Pair<DeviceId, String>>,
    ): DeviceCommandReport {
        if (!connected) {
            return DeviceCommandReport.allFailing(
                targets,
                SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED),
            )
        }

        // Sequential rather than concurrent. A rule points at a handful of devices, and a
        // burst of parallel requests against a rate-limited API is a worse failure than a
        // few hundred extra milliseconds.
        return DeviceCommandReport(
            targets.map { (id, rememberedName) -> switch(command, id, rememberedName) },
        )
    }

    private suspend fun switch(
        command: SmartHomeCommand,
        id: DeviceId,
        rememberedName: String,
    ): DeviceCommandResult {
        val status = when (val response = api.get("/v1.0/devices/${id.value}/status")) {
            is TuyaResponse.Error ->
                return DeviceCommandResult(id, rememberedName, response.failure)

            is TuyaResponse.Ok -> response.json.optJSONArray("result").objects()
        }

        val switchCode = status.firstNotNullOfOrNull { entry ->
            entry.optString("code").takeIf { it.isSwitchCode() && entry.has("value") }
        } ?: return DeviceCommandResult(
            id,
            rememberedName,
            SmartHomeFailure(
                SmartHomeFailure.Kind.DEVICE_UNSUPPORTED,
                deviceName = rememberedName,
                detail = "No on/off control found on this device",
            ),
        )

        val currentlyOn = status
            .firstOrNull { it.optString("code") == switchCode }
            ?.let { if (it.get("value") is Boolean) it.getBoolean("value") else null }

        val target = when (command) {
            SmartHomeCommand.ON -> true
            SmartHomeCommand.OFF -> false
            // Declining beats guessing. A toggle that picks a direction can switch a lamp on
            // at 3am as easily as off.
            SmartHomeCommand.TOGGLE -> currentlyOn?.not() ?: return DeviceCommandResult(
                id,
                rememberedName,
                SmartHomeFailure(
                    SmartHomeFailure.Kind.DEVICE_STATE_UNKNOWN,
                    deviceName = rememberedName,
                ),
            )
        }

        val body = JSONObject()
            .put("commands", JSONArray().put(JSONObject().put("code", switchCode).put("value", target)))
            .toString()

        return when (val response = api.post("/v1.0/devices/${id.value}/commands", body)) {
            is TuyaResponse.Error ->
                DeviceCommandResult(id, rememberedName, response.failure.named(rememberedName))

            is TuyaResponse.Ok -> DeviceCommandResult(id, rememberedName, failure = null)
        }
    }

    /**
     * The local keys for every device, for setting up LAN control later.
     *
     * A Tuya-only method rather than part of [SmartHomeClient]: a local key is a vendor concept,
     * and widening the shared interface to carry one would push it through the whole app to
     * serve a single settings screen.
     *
     * Read from the same device list [devices] uses, so it costs one extra call rather than a
     * second integration. Nothing is cached and nothing is logged — the keys are handed to the
     * caller and forgotten.
     */
    suspend fun localCredentials(): SmartHomeResult<List<TuyaLocalCredential>> {
        if (!connected) {
            return SmartHomeResult.Failure(
                SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED),
            )
        }

        return when (val listed = fetchDevices()) {
            is SmartHomeResult.Failure -> SmartHomeResult.Failure(listed.failure)

            is SmartHomeResult.Success ->
                SmartHomeResult.Success(TuyaLocalCredential.fromDeviceList(listed.value))
        }
    }

    private fun toDevice(json: JSONObject): SmartHomeDevice {
        val status = json.optJSONArray("status").objects()
        val switchEntry = status.firstOrNull { it.optString("code").isSwitchCode() }
        val switchCode = switchEntry?.optString("code")

        return SmartHomeDevice(
            id = DeviceId(json.optString("id")),
            name = json.optString("name").ifEmpty { "Unnamed device" },
            // Tuya does not always report a room, and an absent one is modelled rather than
            // filled in with a placeholder.
            roomName = json.optString("room_name").ifEmpty { null },
            kind = kindOf(json.optString("category"), switchCode),
            // Tuya calls it "online". A device it cannot reach is listed, not hidden.
            reachable = json.optBoolean("online", false),
            isOn = switchEntry
                ?.takeIf { it.has("value") && it.get("value") is Boolean }
                ?.getBoolean("value"),
        )
    }

    /**
     * Light, outlet, or neither.
     *
     * Classified by capability first and category second. The category list below covers the
     * common cases, but Tuya has hundreds and new ones appear — so an unrecognised category
     * with a working on/off control is still usable, which is the behaviour a user wants.
     * `switch_led` is the strong signal for a light.
     */
    private fun kindOf(category: String, switchCode: String?): DeviceKind = when {
        switchCode == null -> DeviceKind.UNSUPPORTED
        category in LIGHT_CATEGORIES -> DeviceKind.LIGHT
        category in OUTLET_CATEGORIES -> DeviceKind.OUTLET
        switchCode == "switch_led" -> DeviceKind.LIGHT
        else -> DeviceKind.OUTLET
    }

    private fun SmartHomeFailure.named(deviceName: String) =
        if (this.deviceName == null) copy(deviceName = deviceName) else this

    private companion object {

        /** No uid required — see [fetchDevices]. */
        const val DEVICE_LIST_PATH = "/v1.0/iot-01/associated-users/devices"
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 20

        /** One provider, one structure. Tuya's model is an account, not a set of homes. */
        val ACCOUNT_STRUCTURE = SmartHomeStructure(
            id = StructureId("tuya:account"),
            name = "Tuya account",
        )

        /** Tuya's own naming. A device may expose several; the first switchable one wins. */
        val SWITCH_CODES = listOf(
            "switch_led",
            "switch",
            "switch_1",
            "switch_2",
            "switch_3",
            "switch_4",
            "switch_5",
            "switch_6",
        )

        /** Common Tuya category codes. Not exhaustive — see [kindOf]. */
        val LIGHT_CATEGORIES = setOf("dj", "dd", "xdd", "fwd", "dc", "tyndj")
        val OUTLET_CATEGORIES = setOf("cz", "pc", "kg", "tdq")

        fun String.isSwitchCode(): Boolean = this in SWITCH_CODES

        /** `org.json` has no iterator, and this is tidier than repeating the loop. */
        fun JSONArray?.objects(): List<JSONObject> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it) }
        }
    }
}
