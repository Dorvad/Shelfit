package com.shelfit.sentinel.platform.smarthome.tuya

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
 */
data class TuyaLocalCredential(
    val deviceId: String,
    val name: String,
    val localKey: String,
    /** Sometimes reported by the cloud; the LAN scan is the reliable source. */
    val ip: String?,
) {
    val usable: Boolean get() = localKey.isNotBlank()
}
