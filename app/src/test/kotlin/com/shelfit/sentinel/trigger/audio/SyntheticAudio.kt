package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import com.shelfit.sentinel.core.audio.AudioInput
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Builds PCM for the sounds the detector has to tell apart, so the whole audio
 * pipeline can be tested without a microphone.
 *
 * Every generator is seeded, so a run is reproducible: a threshold change that
 * breaks discrimination fails the build rather than showing up as flake.
 *
 * These waveforms are caricatures of the real thing — they validate that the
 * *logic* separates an impulse from a tone from sustained noise. They cannot
 * validate real-world accuracy, which only a physical device can.
 */
class SyntheticSignal(
    private val config: AudioCaptureConfig = AudioCaptureConfig(),
    private val backgroundAmplitude: Float = 0.0005f,
    seed: Long = 20260817L,
) {

    private val random = Random(seed)
    private val samples = ArrayList<Float>()

    /** Room tone only. */
    fun silence(millis: Long): SyntheticSignal = apply {
        repeat(sampleCount(millis)) { samples += background() }
    }

    /**
     * A clap: near-instant onset, broadband, gone in a few tens of milliseconds.
     * Broadband because a real clap is a pressure impulse, not a pitched sound.
     */
    fun clap(
        amplitude: Float = 0.5f,
        decayMillis: Float = 6f,
        durationMillis: Long = 80L,
    ): SyntheticSignal = apply {
        val decaySamples = decayMillis * config.sampleRateHz / MILLIS_PER_SECOND
        repeat(sampleCount(durationMillis)) { index ->
            val envelope = exp(-index / decaySamples)
            samples += background() + noise() * amplitude * envelope
        }
    }

    /**
     * A door closing or a heavy object landing: loud and impulsive, but the energy
     * is low-pitched and it rings on far longer than a clap.
     */
    fun thud(
        amplitude: Float = 0.6f,
        frequencyHz: Float = 110f,
        decayMillis: Float = 70f,
        durationMillis: Long = 320L,
    ): SyntheticSignal = apply {
        val decaySamples = decayMillis * config.sampleRateHz / MILLIS_PER_SECOND
        repeat(sampleCount(durationMillis)) { index ->
            val envelope = exp(-index / decaySamples)
            samples += background() + oscillator(index, frequencyHz) * amplitude * envelope
        }
    }

    /**
     * Something hitting a table: impulsive like a clap, but with most of its energy
     * in a mid-frequency ring rather than spread across the spectrum.
     *
     * @param noiseFraction share of the amplitude that is broadband. Raising it
     *   makes the knock progressively more clap-like — which is the honest limit of
     *   feature-based discrimination.
     */
    fun knock(
        amplitude: Float = 0.5f,
        frequencyHz: Float = 700f,
        noiseFraction: Float = 0.15f,
        decayMillis: Float = 12f,
        durationMillis: Long = 70L,
    ): SyntheticSignal = apply {
        val decaySamples = decayMillis * config.sampleRateHz / MILLIS_PER_SECOND
        repeat(sampleCount(durationMillis)) { index ->
            val envelope = exp(-index / decaySamples)
            val ring = oscillator(index, frequencyHz) * (1f - noiseFraction)
            val broadband = noise() * noiseFraction
            samples += background() + (ring + broadband) * amplitude * envelope
        }
    }

    /**
     * Speech: a pitched, harmonically rich sound that ramps up over tens of
     * milliseconds and then sustains. Energy sits in the low harmonics.
     */
    fun speech(
        millis: Long,
        amplitude: Float = 0.28f,
        fundamentalHz: Float = 150f,
        attackMillis: Float = 45f,
    ): SyntheticSignal = apply {
        val attackSamples = attackMillis * config.sampleRateHz / MILLIS_PER_SECOND
        repeat(sampleCount(millis)) { index ->
            // Gradual onset, then a slow syllabic wobble.
            val attack = (index / attackSamples).coerceAtMost(1f)
            val syllable = 0.7f + 0.3f * oscillator(index, SYLLABLE_RATE_HZ)
            var value = 0f
            for (harmonic in 1..SPEECH_HARMONICS) {
                value += oscillator(index, fundamentalHz * harmonic) / harmonic
            }
            samples += background() + value * amplitude * attack * syllable
        }
    }

    /** Music: several sustained pitches at once, never returning to background. */
    fun music(millis: Long, amplitude: Float = 0.3f): SyntheticSignal = apply {
        repeat(sampleCount(millis)) { index ->
            val chord = oscillator(index, 220f) +
                oscillator(index, 277f) +
                oscillator(index, 330f)
            samples += background() + chord / 3f * amplitude
        }
    }

    fun build(): ShortArray = ShortArray(samples.size) { index ->
        (samples[index].coerceIn(-1f, 1f) * FULL_SCALE).toInt().toShort()
    }

    fun toAudioInput(holdOpenAtEnd: Boolean = false): AudioInput =
        SyntheticAudioInput(build(), holdOpenAtEnd)

    private fun sampleCount(millis: Long): Int =
        (millis * config.sampleRateHz / MILLIS_PER_SECOND).toInt()

    private fun background(): Float = noise() * backgroundAmplitude

    private fun noise(): Float = random.nextFloat() * 2f - 1f

    private fun oscillator(index: Int, frequencyHz: Float): Float =
        sin(TWO_PI * frequencyHz * index / config.sampleRateHz).toFloat()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
        const val FULL_SCALE = 32_767f
        const val TWO_PI = 2.0 * PI
        const val SPEECH_HARMONICS = 14
        const val SYLLABLE_RATE_HZ = 4f
    }
}

/**
 * Replays a fixed buffer through the [AudioInput] contract, timestamping frames from
 * the sample position exactly as the `AudioRecord` implementation does.
 *
 * @param holdOpenAtEnd when true the flow suspends instead of completing, mimicking a
 *   microphone that keeps delivering. Use it to exercise cancellation; leave it false
 *   when a test wants to collect every event and finish.
 */
private class SyntheticAudioInput(
    private val samples: ShortArray,
    private val holdOpenAtEnd: Boolean,
) : AudioInput {

    override fun frames(config: AudioCaptureConfig): Flow<AudioFrame> = flow {
        var position = 0
        while (position + config.frameSamples <= samples.size) {
            emit(
                AudioFrame(
                    samples = samples.copyOfRange(position, position + config.frameSamples),
                    sampleCount = config.frameSamples,
                    startTimestampMillis = position * 1_000L / config.sampleRateHz,
                ),
            )
            position += config.frameSamples
        }
        if (holdOpenAtEnd) awaitCancellation()
    }
}
