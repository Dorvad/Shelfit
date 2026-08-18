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
class TuyaCloudClient(
    credentials: () -> TuyaCredentials,
) : SmartHomeClient {

    private val api = TuyaCloudApi(credentials)
    private val readCredentials = credentials

    private val _state = MutableStateFlow<SmartHomeState>(SmartHomeState.NotConnected)
    override val state: StateFlow<SmartHomeState> = _state.asStateFlow()

    override val available: Boolean = true

    /** Serialises connect/refresh so two screens cannot fight over the cached uid. */
    private val mutex = Mutex()

    private var uid: String? = null

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
        val discovered = api.linkedUid()
        if (discovered == null) {
            // Credentials that authenticate but yield no uid mean the cloud project has no
            // app account linked — a distinct problem from bad keys, and a common one.
            val failure = SmartHomeFailure(
                SmartHomeFailure.Kind.HOME_UNAVAILABLE,
                detail = "No Tuya app account is linked to this cloud project",
            )
            _state.value = SmartHomeState.Unavailable(failure)
            return@withLock SmartHomeResult.Failure(failure)
        }

        uid = discovered
        _state.value = SmartHomeState.Connected(structures = listOf(structureFor(discovered)))
        SmartHomeResult.Success(Unit)
    }

    override suspend fun disconnect() {
        mutex.withLock {
            uid = null
            api.forgetToken()
            _state.value = SmartHomeState.NotConnected
        }
    }

    override suspend fun refresh() {
        // Only re-check when we believe we are connected. Refreshing an unconfigured provider
        // on every screen resume would make pointless network calls on a phone left running.
        if (uid == null && !_state.value.isConnected) return
        connect()
    }

    override suspend fun selectStructure(structureId: StructureId) {
        // One structure, so there is nothing to choose. Left as a no-op rather than an error:
        // the caller is a generic UI that does not know this provider is single-home.
    }

    override suspend fun devices(
        structureId: StructureId,
    ): SmartHomeResult<List<SmartHomeDevice>> {
        val account = uid ?: return SmartHomeResult.Failure(
            SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED),
        )

        return when (val response = api.get("/v1.0/users/$account/devices")) {
            is TuyaResponse.Error -> {
                if (response.failure.kind == SmartHomeFailure.Kind.PERMISSION_DENIED) {
                    _state.value = SmartHomeState.PermissionRequired
                }
                SmartHomeResult.Failure(response.failure)
            }

            is TuyaResponse.Ok -> SmartHomeResult.Success(
                response.json.optJSONArray("result").objects().map(::toDevice),
            )
        }
    }

    override suspend fun execute(
        command: SmartHomeCommand,
        targets: List<Pair<DeviceId, String>>,
    ): DeviceCommandReport {
        if (uid == null) {
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
        val account = uid ?: return SmartHomeResult.Failure(
            SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED),
        )

        return when (val response = api.get("/v1.0/users/$account/devices")) {
            is TuyaResponse.Error -> SmartHomeResult.Failure(response.failure)

            is TuyaResponse.Ok -> SmartHomeResult.Success(
                response.json.optJSONArray("result").objects().map { json ->
                    TuyaLocalCredential(
                        deviceId = json.optString("id"),
                        name = json.optString("name").ifEmpty { "Unnamed device" },
                        localKey = json.optString("local_key"),
                        ip = json.optString("ip").ifEmpty { null },
                    )
                },
            )
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

    private fun structureFor(account: String) = SmartHomeStructure(
        id = StructureId("tuya:$account"),
        name = "Tuya account",
    )

    private fun SmartHomeFailure.named(deviceName: String) =
        if (this.deviceName == null) copy(deviceName = deviceName) else this

    private companion object {

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
