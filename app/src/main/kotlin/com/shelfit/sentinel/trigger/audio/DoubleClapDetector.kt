package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.trigger.Trigger
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerDetector
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Detector for [DoubleClapTrigger].
 *
 * **Stage 1 scope.** The lifecycle is real — preflight checks, state reporting,
 * and release-on-cancel all work — but no audio is captured yet, so no
 * [TriggerEvent] is ever emitted and the state settles on
 * [TriggerState.Reason.NOT_IMPLEMENTED]. The dashboard reports that honestly
 * rather than claiming to listen.
 *
 * Stage 2 replaces the body of [events] with microphone capture. The intended
 * shape, which the surrounding architecture already supports:
 *
 *  1. Open an `AudioRecord` on `MediaRecorder.AudioSource.UNPROCESSED` (falling
 *     back to `MIC`) at the lowest workable sample rate — clap onsets are
 *     broadband, so 16 kHz mono is ample and cheap.
 *  2. Compute short-window RMS energy per buffer. No FFT unless false positives
 *     demand it; energy onset detection is far kinder to the battery.
 *  3. Treat a sharp rise above the [DoubleClapConfiguration.sensitivity]-derived
 *     threshold as a peak; emit a [TriggerEvent] when two peaks fall
 *     [DoubleClapConfiguration.minGapMillis]..[DoubleClapConfiguration.maxGapMillis]
 *     apart, then stay quiet for [DoubleClapConfiguration.cooldownMillis].
 *  4. Close the `AudioRecord` in a `finally` block. Because [events] is cold and
 *     collected by [com.shelfit.sentinel.core.trigger.TriggerEngine], cancelling
 *     the engine releases the microphone.
 *
 * Buffers stay inside this class: only the conclusion leaves. Nothing is written
 * to disk.
 */
class DoubleClapDetector(
    private val sensorStatus: SensorStatusProvider,
) : TriggerDetector {

    override val trigger: Trigger = DoubleClapTrigger

    private val _state = MutableStateFlow<TriggerState>(TriggerState.Idle)
    override val state: StateFlow<TriggerState> = _state.asStateFlow()

    override fun events(configuration: TriggerConfiguration): Flow<TriggerEvent> =
        flow<TriggerEvent> {
            _state.value = TriggerState.Starting

            // Narrow to this detector's own configuration type; fall back rather
            // than crash if the engine is handed something unexpected.
            @Suppress("UNUSED_VARIABLE")
            val config = configuration as? DoubleClapConfiguration
                ?: DoubleClapTrigger.defaultConfiguration as DoubleClapConfiguration

            val microphone = sensorStatus.statusOf(SensorKind.MICROPHONE)
            when (microphone.availability) {
                SensorAvailability.UNSUPPORTED -> {
                    _state.value = TriggerState.missingSensor(SensorKind.MICROPHONE)
                    awaitCancellation()
                }

                SensorAvailability.PERMISSION_REQUIRED -> {
                    _state.value = TriggerState.missingPermission(
                        android.Manifest.permission.RECORD_AUDIO,
                    )
                    awaitCancellation()
                }

                SensorAvailability.AVAILABLE -> {
                    _state.value = TriggerState.Unavailable(
                        reason = TriggerState.Reason.NOT_IMPLEMENTED,
                        message = "Clap detection arrives in the next stage",
                    )
                    // Hold the pipeline open so start/stop behaves as it will once
                    // audio capture lands here.
                    awaitCancellation()
                }
            }
        }.onCompletion {
            _state.value = TriggerState.Idle
        }
}
