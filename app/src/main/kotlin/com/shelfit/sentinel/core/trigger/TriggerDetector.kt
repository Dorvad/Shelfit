package com.shelfit.sentinel.core.trigger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns raw sensor input into [TriggerEvent]s. This is the only layer allowed to
 * touch a sensor.
 *
 * Implementation contract:
 *  - [events] returns a **cold** flow. Nothing is acquired until it is collected,
 *    and cancelling the collection must release the sensor. That is what keeps an
 *    always-on device from holding hardware it is not using.
 *  - [state] is owned by the detector. It should report [TriggerState.Starting]
 *    while acquiring, [TriggerState.Active] once running, and return to
 *    [TriggerState.Idle] when collection ends.
 *  - Raw sensor data must not leave the detector. Emit conclusions, not buffers.
 */
interface TriggerDetector {
    val trigger: Trigger

    /** Observable lifecycle for the dashboard. */
    val state: StateFlow<TriggerState>

    /**
     * Cold stream of detections.
     *
     * @param configuration the user's settings for [trigger]. Implementations
     *   narrow this to their own type and fall back to
     *   [Trigger.defaultConfiguration] if given something unexpected.
     */
    fun events(configuration: TriggerConfiguration): Flow<TriggerEvent>
}
