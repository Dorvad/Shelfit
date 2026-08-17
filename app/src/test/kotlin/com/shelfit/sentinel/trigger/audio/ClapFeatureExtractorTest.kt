package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * The measurements, checked against waveforms whose properties are known by
 * construction.
 *
 * These pin down the two features that carry most of the discrimination:
 * `highFrequencyRatio` (which separates a broadband clap from a low thud) and the
 * asymmetric noise floor (which separates a brief transient from sustained noise).
 */
class ClapFeatureExtractorTest {

    private val audio = AudioCaptureConfig()
    private val profile = ClapProfile()

    private fun extractor() = ClapFeatureExtractor(audio, profile)

    private fun frame(index: Int, generator: (Int) -> Float): AudioFrame {
        val samples = ShortArray(audio.frameSamples) { position ->
            val absolute = index * audio.frameSamples + position
            (generator(absolute).coerceIn(-1f, 1f) * 32_767f).toInt().toShort()
        }
        return AudioFrame(
            samples = samples,
            sampleCount = samples.size,
            startTimestampMillis = index * audio.frameDurationMillis,
        )
    }

    private fun sine(frequencyHz: Float, amplitude: Float): (Int) -> Float = { index ->
        (sin(2.0 * PI * frequencyHz * index / audio.sampleRateHz) * amplitude).toFloat()
    }

    private fun whiteNoise(amplitude: Float, seed: Long = 7L): (Int) -> Float {
        val random = Random(seed)
        return { (random.nextFloat() * 2f - 1f) * amplitude }
    }

    @Test
    fun `a low frequency tone scores a very low high-frequency ratio`() {
        val features = extractor().extract(frame(0, sine(110f, 0.5f)))

        assertTrue(
            "110 Hz should sit far below the clap threshold, was " +
                features.highFrequencyRatio,
            features.highFrequencyRatio < profile.minHighFrequencyRatio / 10f,
        )
    }

    @Test
    fun `broadband noise scores a high high-frequency ratio`() {
        val features = extractor().extract(frame(0, whiteNoise(0.5f)))

        assertTrue(
            "white noise should clear the clap threshold, was " +
                features.highFrequencyRatio,
            features.highFrequencyRatio > profile.minHighFrequencyRatio,
        )
        assertTrue("ratio is normalised", features.highFrequencyRatio <= 1f)
    }

    @Test
    fun `the high-frequency ratio rises with frequency`() {
        val ratios = listOf(110f, 500f, 2_000f, 6_000f).map { frequency ->
            extractor().extract(frame(0, sine(frequency, 0.5f))).highFrequencyRatio
        }

        assertEquals(ratios.sortedBy { it }, ratios)
    }

    @Test
    fun `a steady tone has a low crest factor and an impulse has a high one`() {
        val tone = extractor().extract(frame(0, sine(1_000f, 0.5f))).crestFactor

        // One sharp spike in an otherwise quiet frame.
        val impulse = extractor().extract(
            frame(0) { index -> if (index % audio.frameSamples == 0) 0.9f else 0.001f },
        ).crestFactor

        assertTrue("a sine sits near sqrt(2), was $tone", tone < 1.6f)
        assertTrue("an impulse should be far higher, was $impulse", impulse > 10f)
    }

    @Test
    fun `attack ratio spikes when a loud frame follows a quiet one`() {
        val extractor = extractor()
        val quiet = whiteNoise(0.0005f)

        repeat(4) { index -> extractor.extract(frame(index, quiet)) }
        val onset = extractor.extract(frame(4, whiteNoise(0.5f)))

        assertTrue(
            "attack should be large, was " + onset.attackRatio,
            onset.attackRatio > profile.minAttackRatio,
        )
    }

    @Test
    fun `the noise floor barely moves during a brief loud burst`() {
        val extractor = extractor()
        val quiet = whiteNoise(0.001f)

        repeat(20) { index -> extractor.extract(frame(index, quiet)) }
        val settled = extractor.extract(frame(20, quiet)).noiseFloor

        // Roughly one clap's worth of loud audio: 5 frames, about 80 ms.
        var last = settled
        repeat(5) { offset ->
            last = extractor.extract(frame(21 + offset, whiteNoise(0.5f))).noiseFloor
        }

        assertTrue(
            "floor should stay close to the quiet level, went from $settled to $last",
            last < settled * 20f,
        )
    }

    @Test
    fun `the noise floor climbs when noise is sustained`() {
        val extractor = extractor()
        val quiet = whiteNoise(0.001f)

        repeat(20) { index -> extractor.extract(frame(index, quiet)) }
        val settled = extractor.extract(frame(20, quiet)).noiseFloor

        // Several seconds of continuous noise, as music or a running tap would be.
        var ambientRatio = 0f
        var floor = settled
        val sustainedFrames = (4_000L / audio.frameDurationMillis).toInt()
        repeat(sustainedFrames) { offset ->
            val features = extractor.extract(frame(21 + offset, whiteNoise(0.2f)))
            floor = features.noiseFloor
            ambientRatio = features.ambientRatio
        }

        assertTrue("floor should have risen, $settled -> $floor", floor > settled * 20f)
        assertTrue(
            "sustained noise should stop looking like a transient, ratio was $ambientRatio",
            ambientRatio < profile.minAmbientRatio,
        )
    }

    @Test
    fun `the noise floor falls back quickly once a room goes quiet`() {
        val extractor = extractor()

        val loudFrames = (2_000L / audio.frameDurationMillis).toInt()
        repeat(loudFrames) { index -> extractor.extract(frame(index, whiteNoise(0.2f))) }
        val elevated = extractor.extract(frame(loudFrames, whiteNoise(0.2f))).noiseFloor

        val quiet = whiteNoise(0.001f)
        val recoveryFrames = (1_000L / audio.frameDurationMillis).toInt()
        var floor = elevated
        repeat(recoveryFrames) { offset ->
            floor = extractor.extract(frame(loudFrames + 1 + offset, quiet)).noiseFloor
        }

        assertTrue("floor should have dropped, $elevated -> $floor", floor < elevated / 10f)
    }

    @Test
    fun `silence produces no NaN or infinite features`() {
        val features = extractor().extract(
            AudioFrame(ShortArray(audio.frameSamples), audio.frameSamples, 0L),
        )

        assertEquals(0f, features.rms, 0f)
        assertEquals(0f, features.peak, 0f)
        assertEquals(0f, features.crestFactor, 0f)
        assertEquals(0f, features.highFrequencyRatio, 0f)
        assertTrue(features.ambientRatio.isFinite())
        assertTrue(features.attackRatio.isFinite())
    }

    @Test
    fun `reset returns the extractor to a clean state`() {
        val extractor = extractor()
        repeat(20) { index -> extractor.extract(frame(index, whiteNoise(0.3f))) }

        extractor.reset()
        val features = extractor.extract(frame(0, whiteNoise(0.001f)))

        // A fresh floor seeds from the frame itself, so nothing looks like a transient.
        assertEquals(1f, features.ambientRatio, 0.01f)
        assertEquals(0L, features.millisSinceStart)
    }
}
