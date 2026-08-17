package com.shelfit.sentinel.platform.smarthome.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-centre list.
 *
 * Worth asserting because an omission here is invisible in the worst way: a user in a region
 * the app does not offer picks the nearest wrong one, gets an authorisation error, and spends
 * the evening re-checking a secret that was correct all along.
 */
class TuyaRegionTest {

    @Test
    fun `all seven Tuya data centres are offered`() {
        assertEquals(
            setOf("eu", "eu-w", "us", "us-e", "cn", "in", "sg"),
            TuyaRegion.entries.map { it.code }.toSet(),
        )
    }

    @Test
    fun `every endpoint is a distinct https host`() {
        val endpoints = TuyaRegion.entries.map { it.endpoint }

        assertTrue(endpoints.all { it.startsWith("https://") })
        assertEquals("no two regions may share an endpoint", endpoints.size, endpoints.distinct().size)
    }

    @Test
    fun `an unknown stored region falls back rather than failing`() {
        // A downgrade, or a preference written by a later version that added a region.
        assertEquals(TuyaRegion.Default, TuyaRegion.fromName("ATLANTIS"))
        assertEquals(TuyaRegion.Default, TuyaRegion.fromName(null))
    }

    @Test
    fun `a stored region round trips`() {
        TuyaRegion.entries.forEach { region ->
            assertEquals(region, TuyaRegion.fromName(region.name))
        }
    }

    @Test
    fun `every region has a label a person can match to the console`() {
        assertTrue(TuyaRegion.entries.all { it.label.isNotBlank() })
    }
}
