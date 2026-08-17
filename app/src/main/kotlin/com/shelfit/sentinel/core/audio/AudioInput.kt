package com.shelfit.sentinel.core.audio

import kotlinx.coroutines.flow.Flow

/**
 * PCM capture parameters.
 *
 * 16 kHz mono is deliberate: a clap's useful energy sits well below 8 kHz, and
 * halving the sample rate halves the per-frame arithmetic on a device expected to
 * run for weeks.
 *
 * @param frameSamples samples per analysis frame. Sets the time resolution of every
 *   downstream feature — 256 samples at 16 kHz is 16 ms, fine enough to separate a
 *   clap's attack from its decay while keeping frame overhead low.
 */
data class AudioCaptureConfig(
    val sampleRateHz: Int = 16_000,
    val frameSamples: Int = 256,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(frameSamples > 0) { "frameSamples must be positive" }
    }

    val frameDurationMillis: Long get() = frameSamples * MILLIS_PER_SECOND / sampleRateHz

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * One block of mono 16-bit PCM.
 *
 * The buffer is handed to the collector for immediate analysis and dropped. Nothing
 * downstream retains it, nothing writes it anywhere, and a frame is only ever
 * reduced to the scalar features in [com.shelfit.sentinel.trigger.audio.AudioFrameFeatures]
 * before the next frame arrives.
 *
 * @param samples signed 16-bit samples. Only the first [sampleCount] entries are
 *   meaningful.
 * @param startTimestampMillis position of this frame on the capture timeline,
 *   derived from the sample count rather than a wall clock, so it is exact and
 *   free of scheduling jitter.
 */
class AudioFrame(
    val samples: ShortArray,
    val sampleCount: Int,
    val startTimestampMillis: Long,
)

/**
 * A source of PCM frames.
 *
 * Exists so that clap analysis can be developed and tested without a microphone:
 * the Android implementation wraps `AudioRecord`, and unit tests feed synthetic
 * waveforms through the same interface.
 *
 * Implementations return a **cold** flow that acquires the microphone on collection
 * and releases it when collection ends, by cancellation or otherwise.
 */
interface AudioInput {
    fun frames(config: AudioCaptureConfig): Flow<AudioFrame>
}

/** The microphone could not be opened, or stopped delivering audio. */
class AudioInputUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
