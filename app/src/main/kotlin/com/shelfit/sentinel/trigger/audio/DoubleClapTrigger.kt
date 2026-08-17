package com.shelfit.sentinel.trigger.audio

import android.Manifest
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.trigger.Trigger
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerId

/**
 * Two claps in quick succession, heard through the microphone.
 *
 * @param sensitivity 0f..1f. Higher values react to quieter claps and produce more
 *   false positives. Mapped to an amplitude threshold by the detector.
 * @param minGapMillis a second peak sooner than this is treated as an echo of the
 *   first, not a second clap.
 * @param maxGapMillis a second peak later than this starts a new pair instead of
 *   completing the current one.
 * @param cooldownMillis quiet period after a detection, so one pair of claps is
 *   never reported twice.
 */
data class DoubleClapConfiguration(
    override val enabled: Boolean = true,
    val sensitivity: Float = 0.5f,
    val minGapMillis: Long = 120L,
    val maxGapMillis: Long = 800L,
    val cooldownMillis: Long = 1_500L,
) : TriggerConfiguration

/** Metadata for [DoubleClapDetector]. */
object DoubleClapTrigger : Trigger {
    override val id: TriggerId = TriggerId.DoubleClap
    override val displayName: String = "Double clap"
    override val description: String = "Clap twice, quickly"
    override val requiredSensors: Set<SensorKind> = setOf(SensorKind.MICROPHONE)
    override val requiredPermissions: Set<String> = setOf(Manifest.permission.RECORD_AUDIO)
    override val defaultConfiguration: TriggerConfiguration = DoubleClapConfiguration()
}
