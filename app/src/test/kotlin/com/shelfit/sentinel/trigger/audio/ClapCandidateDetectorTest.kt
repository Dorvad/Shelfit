package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accept/reject rules for a single clap, driven by hand-built feature frames.
 *
 * Feeding [AudioFrameFeatures] directly rather than PCM isolates the decision logic:
 * each test changes exactly one property of an otherwise clap-shaped sound and
 * asserts which gate catches it.
 */
class ClapCandidateDetectorTest {

    private val audio = AudioCaptureConfig()
    private val profile = ClapProfile()
    private val frameMillis = audio.frameDurationMillis

    private fun detector() = ClapCandidateDetector(audio, profile)

    private var timeline = 0L

    /** Background frame: at the noise floor, nothing happening. */
    private fun quiet() = features(peak = 0.001f, rms = 0.0005f, ambientRatio = 1f)

    /** A textbook clap onset — every gate comfortably cleared. */
    private fun clapOnset() = features(
        peak = 0.5f,
        rms = 0.12f,
        ambientRatio = 200f,
        crestFactor = 4.2f,
        highFrequencyRatio = 0.45f,
        attackRatio = 200f,
    )

    private fun features(
        peak: Float,
        rms: Float,
        ambientRatio: Float,
        crestFactor: Float = if (rms > 0f) peak / rms else 0f,
        highFrequencyRatio: Float = 0.45f,
        attackRatio: Float = 1f,
        noiseFloor: Float = 0.0005f,
    ): AudioFrameFeatures {
        val at = timeline
        timeline += frameMillis
        return AudioFrameFeatures(
            timestampMillis = at,
            // Past the warm-up unless a test deliberately overrides it.
            millisSinceStart = at + profile.warmUpMillis,
            peak = peak,
            rms = rms,
            crestFactor = crestFactor,
            zeroCrossingRate = 0.3f,
            highFrequencyRatio = highFrequencyRatio,
            noiseFloor = noiseFloor,
            ambientRatio = ambientRatio,
            attackRatio = attackRatio,
        )
    }

    /** Enough background to satisfy [ClapProfile.quietBeforeMillis]. */
    private fun ClapCandidateDetector.settle() {
        repeat(QUIET_FRAMES) { onFrame(quiet()) }
    }

    /** Runs frames until a candidate or rejection appears, or the frames run out. */
    private fun ClapCandidateDetector.runQuietFrames(count: Int): ClapDetection {
        var last: ClapDetection = ClapDetection.None
        repeat(count) {
            val detection = onFrame(quiet())
            if (detection != ClapDetection.None) last = detection
        }
        return last
    }

    @Test
    fun `a clap that decays back to background is accepted`() {
        val detector = detector()
        detector.settle()

        assertEquals(ClapDetection.None, detector.onFrame(clapOnset()))

        val detection = detector.runQuietFrames(QUIET_FRAMES)

        assertTrue("expected a candidate, got $detection", detection is ClapDetection.Candidate)
        val clap = (detection as ClapDetection.Candidate).clap
        assertTrue("confidence should exceed the baseline", clap.confidence > 0.5f)
    }

    @Test
    fun `the candidate is timestamped at the onset, not at confirmation`() {
        val detector = detector()
        detector.settle()

        val onset = clapOnset()
        detector.onFrame(onset)
        val detection = detector.runQuietFrames(QUIET_FRAMES)

        val clap = (detection as ClapDetection.Candidate).clap
        assertEquals(onset.timestampMillis, clap.onsetMillis)
    }

    @Test
    fun `a loud sound with a gradual onset is rejected as slow attack`() {
        val detector = detector()
        detector.settle()

        val detection = detector.onFrame(
            clapOnset().copy(attackRatio = profile.minAttackRatio - 0.5f),
        )

        assertEquals(
            ClapDetection.Rejected(ClapRejection.SLOW_ATTACK, 0L).reason,
            (detection as ClapDetection.Rejected).reason,
        )
    }

    @Test
    fun `a low pitched bang is rejected on its frequency content`() {
        val detector = detector()
        detector.settle()

        // A door or a heavy object: impulsive, but the energy is all low.
        val detection = detector.onFrame(clapOnset().copy(highFrequencyRatio = 0.002f))

        assertEquals(
            ClapRejection.LOW_FREQUENCY_RUMBLE,
            (detection as ClapDetection.Rejected).reason,
        )
    }

    @Test
    fun `a sustained sound is rejected as not impulsive`() {
        val detector = detector()
        detector.settle()

        val detection = detector.onFrame(
            clapOnset().copy(crestFactor = profile.minCrestFactor - 0.5f),
        )

        assertEquals(
            ClapRejection.NOT_IMPULSIVE,
            (detection as ClapDetection.Rejected).reason,
        )
    }

    @Test
    fun `a peak on top of continuous noise is rejected for having no quiet before`() {
        val detector = detector()
        // No settling: the room was already busy.
        val detection = detector.onFrame(clapOnset())

        assertEquals(
            ClapRejection.NO_QUIET_BEFORE,
            (detection as ClapDetection.Rejected).reason,
        )
    }

    @Test
    fun `a sound that does not decay in time is rejected`() {
        val detector = detector()
        detector.settle()
        detector.onFrame(clapOnset())

        // Stays loud well past maxTransientMillis — a shout, or a ringing slam.
        // Take the first verdict: once rejected, later loud frames rightly report
        // NO_QUIET_BEFORE instead.
        var detection: ClapDetection = ClapDetection.None
        repeat(LOUD_FRAMES) {
            if (detection != ClapDetection.None) return@repeat
            detection = detector.onFrame(
                features(peak = 0.4f, rms = 0.15f, ambientRatio = 180f),
            )
        }

        assertEquals(
            ClapRejection.TRANSIENT_TOO_LONG,
            (detection as ClapDetection.Rejected).reason,
        )
    }

    @Test
    fun `a sound interrupted before it settles is rejected`() {
        val detector = detector()
        detector.settle()
        detector.onFrame(clapOnset())

        // One quiet frame, then loud again before quietAfterMillis has elapsed.
        assertEquals(ClapDetection.None, detector.onFrame(quiet()))
        val detection = detector.onFrame(
            features(peak = 0.4f, rms = 0.15f, ambientRatio = 180f),
        )

        assertEquals(
            ClapRejection.NO_QUIET_AFTER,
            (detection as ClapDetection.Rejected).reason,
        )
    }

    @Test
    fun `nothing is detected during the warm up period`() {
        val detector = detector()
        val early = clapOnset().copy(millisSinceStart = profile.warmUpMillis - frameMillis)

        assertEquals(ClapDetection.None, detector.onFrame(early))
    }

    @Test
    fun `a quiet click below the absolute floor is not even considered`() {
        val detector = detector()
        detector.settle()

        // Sharp and broadband, but far too faint to be a clap in the room.
        val detection = detector.onFrame(
            clapOnset().copy(
                peak = profile.minPeakAmplitude / 2f,
                rms = 0.004f,
                ambientRatio = 8f,
            ),
        )

        assertEquals(ClapDetection.None, detection)
    }

    @Test
    fun `two claps in sequence both produce candidates`() {
        val detector = detector()
        detector.settle()

        detector.onFrame(clapOnset())
        val first = detector.runQuietFrames(QUIET_FRAMES)
        detector.onFrame(clapOnset())
        val second = detector.runQuietFrames(QUIET_FRAMES)

        assertTrue(first is ClapDetection.Candidate)
        assertTrue(second is ClapDetection.Candidate)
        val gap = (second as ClapDetection.Candidate).clap.onsetMillis -
            (first as ClapDetection.Candidate).clap.onsetMillis
        assertTrue("claps should be measurably apart", gap > 0L)
    }

    @Test
    fun `confidence rises with how decisively the thresholds are cleared`() {
        val marginal = detector().let { detector ->
            detector.settle()
            detector.onFrame(
                features(
                    peak = profile.minPeakAmplitude,
                    rms = profile.minPeakAmplitude / profile.minCrestFactor,
                    ambientRatio = profile.minAmbientRatio,
                    crestFactor = profile.minCrestFactor,
                    highFrequencyRatio = profile.minHighFrequencyRatio,
                    attackRatio = profile.minAttackRatio,
                ),
            )
            (detector.runQuietFrames(QUIET_FRAMES) as ClapDetection.Candidate).clap.confidence
        }

        val decisive = detector().let { detector ->
            detector.settle()
            detector.onFrame(clapOnset())
            (detector.runQuietFrames(QUIET_FRAMES) as ClapDetection.Candidate).clap.confidence
        }

        assertEquals("a bare-threshold clap scores the baseline", 0.5f, marginal, 0.01f)
        assertTrue("a decisive clap scores higher", decisive > marginal)
        assertTrue("confidence stays in range", decisive <= 1f)
    }

    @Test
    fun `reset clears a transient in progress`() {
        val detector = detector()
        detector.settle()
        detector.onFrame(clapOnset())
        assertTrue(detector.transientInProgress)

        detector.reset()

        assertTrue(!detector.transientInProgress)
        assertNull((detector.onFrame(quiet()) as? ClapDetection.Candidate)?.clap)
    }

    private companion object {
        const val QUIET_FRAMES = 12
        const val LOUD_FRAMES = 16
    }
}
