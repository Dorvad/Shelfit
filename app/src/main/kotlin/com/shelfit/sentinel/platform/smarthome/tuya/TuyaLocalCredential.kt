package com.shelfit.sentinel.platform.smarthome.tuya

import org.json.JSONObject

/**
 * What local (LAN) control needs for one device, as the cloud reports it.
 *
 * Deliberately **not** part of [com.shelfit.sentinel.core.smarthome.SmartHomeDevice]: a local key
 * is a Tuya concept, and putting it on the shared device model would push a vendor detail
 * through the whole app to serve one screen. This is the same kind of side channel as
 * `ClapDiagnostics` — provider-specific, and reachable only from the provider's own settings.
 *
 * @param localKey the per-device secret that encrypts LAN traffic. Only obtainable from the
 *   cloud, never broadcast, and it changes if the device is reset or re-paired.
 * @param gatewayId the hub this device hangs off, when it is not on Wi-Fi itself.
 * @param nodeId the sub-device's address behind its gateway. Tuya calls this `cid`, and a
 *   command for a sub-device has to carry it.
 */
data class TuyaLocalCredential(
    val deviceId: String,
    val name: String,
    val localKey: String,
    /** Sometimes reported by the cloud; the LAN scan is the reliable source. */
    val ip: String?,
    /**
     * True when this device is reached through a hub rather than over Wi-Fi.
     *
     * Decides whether local control can address it at all: a Wi-Fi device answers on its own
     * IP, whereas a Zigbee device has no IP and every command must be routed through its
     * gateway. Surfacing it is the difference between "local control will work for this" and
     * an afternoon wondering why a device never answers.
     */
    val isSubDevice: Boolean = false,
    val gatewayId: String? = null,
    val nodeId: String? = null,
) {
    val usable: Boolean get() = localKey.isNotBlank()

    /** Reachable on its own IP, so the plain LAN protocol applies. */
    val directlyReachable: Boolean get() = !isSubDevice

    companion object {

        /**
         * Reads the list, working out which devices sit behind a gateway.
         *
         * The cloud reports `sub` reliably but `gateway_id` only sometimes. When the parent is
         * missing, it is inferred the way `tinytuya` does it — and its comment is worth
         * repeating, because the heuristic looks arbitrary otherwise: *"The only link between
         * parent and child appears to be the local key."* Tuya hands a sub-device the same
         * local key as its hub, so a sub-device whose key matches a non-sub device has found
         * its parent.
         */
        fun fromDeviceList(devices: List<JSONObject>): List<TuyaLocalCredential> {
            fun isSub(json: JSONObject) = json.optBoolean("sub", false)

            // Key to id, for the non-sub devices only — a hub, never another sub-device.
            val parentByKey: Map<String, String> = devices
                .filterNot(::isSub)
                .mapNotNull { json ->
                    val key = json.optString("local_key")
                    val id = json.optString("id")
                    if (key.isEmpty() || id.isEmpty()) null else key to id
                }
                .toMap()

            return devices.map { json ->
                val sub = isSub(json)
                val key = json.optString("local_key")
                TuyaLocalCredential(
                    deviceId = json.optString("id"),
                    name = json.optString("name").ifEmpty { "Unnamed device" },
                    localKey = key,
                    ip = json.optString("ip").ifEmpty { null },
                    isSubDevice = sub,
                    gatewayId = json.optString("gateway_id").ifEmpty { null }
                        ?: parentByKey[key]?.takeIf { sub && it != json.optString("id") },
                    nodeId = json.optString("node_id").ifEmpty { null },
                )
            }
        }
    }
}
