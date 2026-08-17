package com.shelfit.sentinel.core.sensormode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivations that decide what the health screen says, and in particular the one
 * that matters most: whether listening needs a person.
 */
class SensorHealthTest {

    private fun health(
        desiredMode: ListeningMode = ListeningMode.LISTENING,
        serviceRunning: Boolean = true,
        listening: Boolean = true,
        microphone: PermissionStatus = PermissionStatus.GRANTED,
        notifications: PermissionStatus = PermissionStatus.GRANTED,
        battery: BatteryOptimisationStatus = BatteryOptimisationStatus.EXEMPT,
        lastError: SensorModeError? = null,
    ) = SensorHealth(
        desiredMode = desiredMode,
        serviceRunning = serviceRunning,
        listening = listening,
        microphone = microphone,
        notifications = notifications,
        batteryOptimisation = battery,
        lastError = lastError,
    )

    // ---- resumeRequired -----------------------------------------------------

    @Test
    fun `resume is required when the user wants listening but nothing runs`() {
        assertTrue(health(serviceRunning = false, listening = false).resumeRequired)
    }

    @Test
    fun `resume is not required while the service is running`() {
        assertFalse(health().resumeRequired)
    }

    @Test
    fun `resume is not required when the user turned Sensor Mode off`() {
        assertFalse(
            health(
                desiredMode = ListeningMode.OFF,
                serviceRunning = false,
                listening = false,
            ).resumeRequired,
        )
    }

    @Test
    fun `a deliberate pause is not a resume-required state`() {
        // Paused keeps the service alive, so Resume comes from the notification rather
        // than from the health screen.
        assertFalse(
            health(desiredMode = ListeningMode.PAUSED, listening = false).resumeRequired,
        )
    }

    // ---- readyForUnattendedUse ----------------------------------------------

    @Test
    fun `a healthy setup is ready for unattended use`() {
        assertTrue(health().readyForUnattendedUse)
    }

    @Test
    fun `a missing microphone permission is never ready`() {
        assertFalse(health(microphone = PermissionStatus.DENIED).readyForUnattendedUse)
    }

    @Test
    fun `denied notifications block unattended use because the controls vanish with them`() {
        assertFalse(health(notifications = PermissionStatus.DENIED).readyForUnattendedUse)
    }

    @Test
    fun `notifications that do not exist on this version are fine`() {
        assertTrue(health(notifications = PermissionStatus.NOT_REQUIRED).readyForUnattendedUse)
    }

    @Test
    fun `battery optimisation alone does not block unattended use`() {
        // Stock Android will not stop a foreground service for this, so reporting it as
        // fatal would be crying wolf.
        assertTrue(
            health(battery = BatteryOptimisationStatus.OPTIMISED).readyForUnattendedUse,
        )
    }

    @Test
    fun `an error the app will retry does not block unattended use`() {
        val transient = SensorModeError(
            kind = SensorModeError.Kind.MICROPHONE_UNAVAILABLE,
            atEpochMillis = 0L,
        )

        assertFalse(transient.needsUserAction)
        assertTrue(health(lastError = transient).readyForUnattendedUse)
    }

    @Test
    fun `an error needing a person does block unattended use`() {
        val fatal = SensorModeError(
            kind = SensorModeError.Kind.MICROPHONE_PERMISSION_REVOKED,
            atEpochMillis = 0L,
        )

        assertTrue(fatal.needsUserAction)
        assertFalse(health(lastError = fatal).readyForUnattendedUse)
    }

    // ---- attention ordering -------------------------------------------------

    @Test
    fun `attention leads with the microphone permission`() {
        val attention = health(
            serviceRunning = false,
            listening = false,
            microphone = PermissionStatus.DENIED,
            notifications = PermissionStatus.DENIED,
            battery = BatteryOptimisationStatus.OPTIMISED,
        ).attention

        assertEquals(SensorHealth.Attention.MICROPHONE_PERMISSION, attention.first())
        assertEquals(
            "battery optimisation is the least urgent, so it comes last",
            SensorHealth.Attention.BATTERY_OPTIMISATION,
            attention.last(),
        )
    }

    @Test
    fun `a healthy setup needs no attention`() {
        assertTrue(health().attention.isEmpty())
    }

    // ---- persistence helpers ------------------------------------------------

    @Test
    fun `an unknown stored mode falls back to off rather than starting the microphone`() {
        assertEquals(ListeningMode.OFF, ListeningMode.fromName("SOMETHING_ELSE"))
        assertEquals(ListeningMode.OFF, ListeningMode.fromName(null))
    }

    @Test
    fun `round trips through the stored name`() {
        ListeningMode.entries.forEach { mode ->
            assertEquals(mode, ListeningMode.fromName(mode.name))
        }
    }
}
