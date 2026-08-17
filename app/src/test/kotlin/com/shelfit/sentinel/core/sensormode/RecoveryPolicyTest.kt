package com.shelfit.sentinel.core.sensormode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {

    @Test
    fun `the first retry uses the initial delay`() {
        assertEquals(5_000L, RecoveryPolicy(initialDelayMillis = 5_000L).delayFor(1))
    }

    @Test
    fun `each retry doubles by default`() {
        val policy = RecoveryPolicy(initialDelayMillis = 1_000L)

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map(policy::delayFor))
    }

    @Test
    fun `the delay is capped so a broken microphone is not retried forever at speed`() {
        val policy = RecoveryPolicy(initialDelayMillis = 1_000L, maxDelayMillis = 10_000L)

        assertEquals(10_000L, policy.delayFor(20))
        assertTrue((1..50).all { policy.delayFor(it) <= 10_000L })
    }

    @Test
    fun `the delay never decreases`() {
        val policy = RecoveryPolicy()
        val delays = (1..30).map(policy::delayFor)

        assertEquals(delays.sorted(), delays)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempts are one-based`() {
        RecoveryPolicy().delayFor(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a ceiling below the initial delay is rejected`() {
        RecoveryPolicy(initialDelayMillis = 5_000L, maxDelayMillis = 1_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a shrinking multiplier is rejected`() {
        RecoveryPolicy(multiplier = 0.5f)
    }

    @Test
    fun `the default reaches its ceiling within a few minutes of outage`() {
        val policy = RecoveryPolicy()
        // Sum of the first six delays: long enough to sit out a phone call without
        // hammering the audio subsystem.
        val total = (1..6).sumOf(policy::delayFor)

        assertTrue("total was ${total}ms", total in 60_000L..600_000L)
    }
}
