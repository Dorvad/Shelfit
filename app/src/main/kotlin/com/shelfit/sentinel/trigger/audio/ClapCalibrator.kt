package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.transformWhile

/** How long to listen, and how many claps to ask for. */
data class CalibrationSpec(
    val ambientMillis: Long = 4_000L,
    val requiredClaps: Int = ClapCalibration.PREFERRED_SAMPLES,
    val minimumClaps: Int = ClapCalibration.MINIMUM_USABLE_SAMPLES,
    /** Give up collecting claps after this long and work with whatever arrived. */
    val clapTimeoutMillis: Long = 30_000L,
    val audio: AudioCaptureConfig = AudioCaptureConfig(),
    val baseProfile: ClapProfile = ClapProfile(),
) {
    init {
        require(ambientMillis > 0) { "ambientMillis must be positive" }
        require(minimumClaps in 1..requiredClaps) { "minimumClaps must not exceed requiredClaps" }
    }
}

/** Where a calibration run has got to. Drives the guided screen. */
sealed interface CalibrationStage {

    /** Listening to the room with nobody clapping. */
    data class MeasuringAmbient(
        val elapsedMillis: Long,
        val totalMillis: Long,
        val level: Float,
    ) : CalibrationStage {
        val fraction: Float get() = (elapsedMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
    }

    /** Waiting for the user to clap, counting what arrives. */
    data class CollectingClaps(
        val collected: Int,
        val required: Int,
        val level: Float,
        val remainingMillis: Long,
        /** Confidence of the most recent accepted clap, for immediate feedback. */
        val lastConfidence: Float?,
    ) : CalibrationStage

    /**
     * Measurement finished. Nothing has been saved — the caller reviews
     * [calibration] and tests [profile] before deciding.
     */
    data class Complete(
        val calibration: ClapCalibration,
        val profile: ClapProfile,
    ) : CalibrationStage

    data class Failed(val reason: String) : CalibrationStage

    val isTerminal: Boolean get() = this is Complete || this is Failed
}

/**
 * Measures a room and a person's claps, and turns them into thresholds.
 *
 * Two phases over one continuous capture, which matters: the ambient phase leaves the
 * feature extractor's noise floor already settled, so the claps that follow are
 * measured against a warm reference rather than a guess.
 *
 * Collection uses [forCalibrationCapture] — gates loose enough that almost any clap
 * registers, anchored to the ambient level just measured. Strict thresholds are then
 * derived from what was actually observed, by [ClapCalibration.toProfile].
 *
 * **Nothing is retained but numbers.** Frames are reduced to features and dropped,
 * exactly as during normal detection. The result is a handful of levels, ratios and
 * durations.
 *
 * The returned flow is cold and completes on its own once a terminal stage is
 * reached, releasing the microphone.
 */
class ClapCalibrator(private val audioInput: AudioInput) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(spec: CalibrationSpec, capturedAtEpochMillis: Long): Flow<CalibrationStage> {
        val session = Session(spec, capturedAtEpochMillis)

        return audioInput.frames(spec.audio)
            .transformWhile { frame ->
                val stage = session.onFrame(frame)
                if (stage != null) emit(stage)
                // Stopping here completes the flow, which tears down the capture.
                stage?.isTerminal != true
            }
            .catch { error ->
                emit(
                    CalibrationStage.Failed(
                        error.message ?: "Could not read from the microphone",
                    ),
                )
            }
    }

    /** Per-run state. Confined to one [run] call, so two runs never interfere. */
    private class Session(
        private val spec: CalibrationSpec,
        private val capturedAtEpochMillis: Long,
    ) {
        private val extractor = ClapFeatureExtractor(spec.audio, spec.baseProfile)

        private var rmsTotal = 0.0
        private val ambientPeaks = ArrayList<Float>()

        private var ambient: AmbientMeasurement? = null
        private var candidates: ClapCandidateDetector? = null
        private var clapPhaseStartMillis = 0L
        private val claps = ArrayList<ClapCandidate>()
        private var lastConfidence: Float? = null

        fun onFrame(frame: com.shelfit.sentinel.core.audio.AudioFrame): CalibrationStage? {
            val features = extractor.extract(frame)
            val measured = ambient
            return if (measured == null) {
                onAmbientFrame(features)
            } else {
                onClapFrame(features, measured)
            }
        }

        private fun onAmbientFrame(features: AudioFrameFeatures): CalibrationStage {
            rmsTotal += features.rms.toDouble()
            ambientPeaks += features.peak

            val elapsed = features.millisSinceStart + spec.audio.frameDurationMillis
            if (elapsed < spec.ambientMillis) {
                return CalibrationStage.MeasuringAmbient(
                    elapsedMillis = elapsed,
                    totalMillis = spec.ambientMillis,
                    level = features.rms,
                )
            }

            val measurement = AmbientMeasurement(
                meanRms = (rmsTotal / ambientPeaks.size).toFloat(),
                // A high percentile rather than the maximum: one cough during the
                // measurement must not define the room for good.
                peak = ambientPeaks.percentile(AMBIENT_PEAK_PERCENTILE),
                frameCount = ambientPeaks.size,
            )
            ambient = measurement
            candidates = ClapCandidateDetector(
                audio = spec.audio,
                profile = spec.baseProfile.forCalibrationCapture(measurement.peak),
            )
            clapPhaseStartMillis = features.timestampMillis

            return CalibrationStage.CollectingClaps(
                collected = 0,
                required = spec.requiredClaps,
                level = features.rms,
                remainingMillis = spec.clapTimeoutMillis,
                lastConfidence = null,
            )
        }

        private fun onClapFrame(
            features: AudioFrameFeatures,
            measured: AmbientMeasurement,
        ): CalibrationStage {
            val detection = candidates?.onFrame(features)
            if (detection is ClapDetection.Candidate) {
                claps += detection.clap
                lastConfidence = detection.clap.confidence
            }

            if (claps.size >= spec.requiredClaps) return complete(measured)

            val remaining = spec.clapTimeoutMillis -
                (features.timestampMillis - clapPhaseStartMillis)

            if (remaining <= 0L) {
                return if (claps.size >= spec.minimumClaps) {
                    complete(measured)
                } else {
                    CalibrationStage.Failed(
                        "Heard ${claps.size} of ${spec.requiredClaps} claps. " +
                            "Try again somewhere quieter, or clap closer to the phone.",
                    )
                }
            }

            return CalibrationStage.CollectingClaps(
                collected = claps.size,
                required = spec.requiredClaps,
                level = features.rms,
                remainingMillis = remaining,
                lastConfidence = lastConfidence,
            )
        }

        private fun complete(measured: AmbientMeasurement): CalibrationStage {
            val calibration = ClapCalibration.from(
                capturedAtEpochMillis = capturedAtEpochMillis,
                ambient = measured,
                claps = claps,
            )
            return CalibrationStage.Complete(
                calibration = calibration,
                profile = calibration.toProfile(spec.baseProfile),
            )
        }

        private fun List<Float>.percentile(fraction: Float): Float {
            if (isEmpty()) return 0f
            val sorted = sorted()
            val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }

        private companion object {
            const val AMBIENT_PEAK_PERCENTILE = 0.9f
        }
    }
}
