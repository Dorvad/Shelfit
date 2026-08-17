package com.shelfit.sentinel.core.smarthome

import kotlinx.coroutines.flow.StateFlow

/** Either a value or a reason it could not be produced. */
sealed interface SmartHomeResult<out T> {
    data class Success<T>(val value: T) : SmartHomeResult<T>
    data class Failure(val failure: SmartHomeFailure) : SmartHomeResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
    fun failureOrNull(): SmartHomeFailure? = (this as? Failure)?.failure
}

/**
 * **The seam.** Everything the app needs from a smart home, in the app's own terms.
 *
 * This interface is the entire contract a provider has to satisfy. A vendor SDK appears in
 * exactly one implementation of it and nowhere else, which is what keeps the audio
 * pipeline, the rule engine and the UI free of vendor types. `SmartHomeActionExecutor`
 * talks to this, never to a provider.
 *
 * Implementations must not throw for expected conditions — no network, revoked permission,
 * a lamp that has been unplugged. Those are values, because the automation layer has to
 * keep running and the UI has to explain them. An exception escaping here is a bug.
 */
interface SmartHomeClient {

    /**
     * Where the connection stands. Observed by the UI so a permission withdrawn from
     * Google's app shows up without the user prodding anything.
     */
    val state: StateFlow<SmartHomeState>

    /** True when this build can actually reach a provider. False for the unconfigured stub. */
    val available: Boolean

    /**
     * Runs the account-linking and permission flow.
     *
     * Must be called while the app is visible: a consent screen needs an activity to show
     * itself on. Idempotent — calling it when already connected re-reads the state.
     */
    suspend fun connect(): SmartHomeResult<Unit>

    /** Drops local authorisation. Does not revoke anything on Google's side. */
    suspend fun disconnect()

    /** Re-reads [state] from the provider. Cheap enough to call when a screen resumes. */
    suspend fun refresh()

    /** Remembers which home the user picked, for [devices] and for the editor. */
    suspend fun selectStructure(structureId: StructureId)

    /**
     * Devices in a home that this app understands enough to list.
     *
     * Includes unsupported and unreachable devices — see [SmartHomeDevice] for why they are
     * surfaced rather than filtered.
     */
    suspend fun devices(structureId: StructureId): SmartHomeResult<List<SmartHomeDevice>>

    /**
     * Switches devices.
     *
     * Never throws and never partially reports: the returned [DeviceCommandReport] has one
     * entry per requested device, whatever happened. For [SmartHomeCommand.TOGGLE] the
     * implementation is responsible for reading each device's current state and sending the
     * inverse, failing that device with [SmartHomeFailure.Kind.DEVICE_STATE_UNKNOWN] if it
     * cannot be determined — a toggle that guesses is worse than one that declines.
     *
     * @param targets device id paired with the name last known to the app, so failures can
     *   be reported against something a person recognises even when the device has since
     *   been removed.
     */
    suspend fun execute(
        command: SmartHomeCommand,
        targets: List<Pair<DeviceId, String>>,
    ): DeviceCommandReport
}
