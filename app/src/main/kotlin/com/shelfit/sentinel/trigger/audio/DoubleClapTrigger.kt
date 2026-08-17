package com.shelfit.sentinel.trigger.audio

import android.Manifest
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.trigger.Trigger
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerId

/**
 * Metadata for [DoubleClapDetector]: two claps in quick succession, heard through
 * the microphone. Tuning lives in [DoubleClapConfiguration].
 */
object DoubleClapTrigger : Trigger {
    override val id: TriggerId = TriggerId.DoubleClap
    override val displayName: String = "Double clap"
    override val description: String = "Clap twice, quickly"
    override val requiredSensors: Set<SensorKind> = setOf(SensorKind.MICROPHONE)
    override val requiredPermissions: Set<String> = setOf(Manifest.permission.RECORD_AUDIO)
    override val defaultConfiguration: TriggerConfiguration = DoubleClapConfiguration()
}
