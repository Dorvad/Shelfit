package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down *why* each everyday sound is rejected, not merely that it is.
 *
 * The end-to-end tests in `DoubleClapDetectorTest` assert that speech, music and
 * doors emit no event. That would also hold if detection were simply broken, so
 * these tests run real waveforms through the extractor and candidate detector and
 * assert which gate catches each one. If a threshold change starts rejecting doors
 * for the wrong reason — or claps for any reason — it shows up here.
 */
class ClapDiscriminationTest {

    private val audio = AudioCaptureConfig()
    private val profile = ClapProfile()

    private data class Verdict(
        val accepted: Int,
        val rejections: Set<ClapRejection>,
        val loudestPeak: Float,
        val highestFrequencyRatio: Float,
    )

    /** Runs the real analysis chain over a synthetic waveform. */
    private fun analyse(signal: SyntheticSignal): Verdict {
        val samples = signal.build()
        val extractor = ClapFeatureExtractor(audio, profile)
        val candidates = ClapCandidateDetector(audio, profile)

        var accepted = 0
        val rejections = mutableSetOf<ClapRejection>()
        var loudestPeak = 0f
        var highestFrequencyRatio = 0f
        var position = 0

        while (position + audio.frameSamples <= samples.size) {
            val features = extractor.extract(
                AudioFrame(
                    samples = samples.copyOfRange(position, position + audio.frameSamples),
                    sampleCount = audio.frameSamples,
                    startTimestampMillis = position * 1_000L / audio.sampleRateHz,
                ),
            )
            if (features.millisSinceStart > profile.warmUpMillis &&
                features.peak > profile.minPeakAmplitude
            ) {
                loudestPeak = maxOf(loudestPeak, features.peak)
                highestFrequencyRatio = maxOf(highestFrequencyRatio, features.highFrequencyRatio)
            }
            when (val detection = candidates.onFrame(features)) {
                is ClapDetection.Candidate -> accepted++
                is ClapDetection.Rejected -> rejections += detection.reason
                ClapDetection.None -> Unit
            }
            position += audio.frameSamples
        }
        return Verdict(accepted, rejections, loudestPeak, highestFrequencyRatio)
    }

    private fun quietRoom() = SyntheticSignal(audio).silence(700L)

    @Test
    fun `claps are accepted and nothing is rejected`() {
        val verdict = analyse(quietRoom().clap().silence(220L).clap().silence(500L))

        assertEquals(2, verdict.accepted)
        assertEquals(emptySet<ClapRejection>(), verdict.rejections)
        assertTrue(
            "a clap is broadband, was ${verdict.highestFrequencyRatio}",
            verdict.highestFrequencyRatio > profile.minHighFrequencyRatio * 3f,
        )
    }

    @Test
    fun `speech is rejected for never leaving the room quiet`() {
        val verdict = analyse(
            quietRoom().speech(600L).silence(200L).speech(500L).silence(400L),
        )

        assertEquals(0, verdict.accepted)
        assertTrue(
            "speech is loud enough to be considered, peak ${verdict.loudestPeak}",
            verdict.loudestPeak > profile.minPeakAmplitude,
        )
        assertTrue(
            "continuous voicing means no quiet run before a peak",
            ClapRejection.NO_QUIET_BEFORE in verdict.rejections,
        )
        assertTrue(
            "speech energy is low-pitched, was ${verdict.highestFrequencyRatio}",
            verdict.highestFrequencyRatio < profile.minHighFrequencyRatio,
        )
    }

    @Test
    fun `music is rejected on its onset frequency content and then on continuity`() {
        val verdict = analyse(quietRoom().music(4_000L).silence(400L))

        assertEquals(0, verdict.accepted)
        assertTrue(
            "the first note has quiet before it, so the spectral gate must catch it",
            ClapRejection.LOW_FREQUENCY_RUMBLE in verdict.rejections,
        )
        assertTrue(
            "sustained music then fails the quiet-before test",
            ClapRejection.NO_QUIET_BEFORE in verdict.rejections,
        )
    }

    @Test
    fun `a closing door is rejected as low frequency`() {
        val verdict = analyse(quietRoom().thud().silence(300L).thud().silence(500L))

        assertEquals(0, verdict.accepted)
        assertTrue(
            "a door thud is louder than a clap but pitched far lower, was " +
                verdict.highestFrequencyRatio,
            verdict.highestFrequencyRatio < profile.minHighFrequencyRatio / 10f,
        )
        assertTrue(ClapRejection.LOW_FREQUENCY_RUMBLE in verdict.rejections)
    }

    @Test
    fun `an object hitting a table is rejected as low frequency`() {
        val verdict = analyse(quietRoom().knock().silence(300L).knock().silence(500L))

        assertEquals(0, verdict.accepted)
        assertTrue(ClapRejection.LOW_FREQUENCY_RUMBLE in verdict.rejections)
    }

    /**
     * The honest boundary of this approach. Make a table strike dry and broadband
     * enough and it becomes a clap by every measure taken here. Recorded timbre
     * would separate them; peak, attack, crest, spectral tilt and duration will not.
     */
    @Test
    fun `a dry broadband strike is accepted, which is the known limit`() {
        val verdict = analyse(
            quietRoom().knock(noiseFraction = 0.9f, decayMillis = 6f).silence(500L),
        )

        assertEquals(1, verdict.accepted)
    }

    @Test
    fun `a clap too faint for the room is never considered`() {
        val verdict = analyse(quietRoom().clap(amplitude = 0.05f).silence(500L))

        assertEquals(0, verdict.accepted)
        assertEquals(
            "below the absolute floor it is not even tested, so nothing is rejected",
            emptySet<ClapRejection>(),
            verdict.rejections,
        )
    }
}
