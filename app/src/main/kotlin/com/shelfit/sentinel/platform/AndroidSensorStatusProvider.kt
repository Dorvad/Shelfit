package com.shelfit.sentinel.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.sensor.SensorStatus
import com.shelfit.sentinel.core.sensor.SensorStatusProvider

/**
 * Answers "can this device do it, and are we allowed to?" against the real
 * framework.
 *
 * Deliberately reads live each time it is asked: permissions can be revoked from
 * Settings while the app is running, which for an always-on device is the normal
 * case rather than an edge case.
 */
class AndroidSensorStatusProvider(private val context: Context) : SensorStatusProvider {

    private val packageManager: PackageManager get() = context.packageManager

    private val sensorManager: SensorManager?
        get() = ContextCompat.getSystemService(context, SensorManager::class.java)

    override fun statusOf(kind: SensorKind): SensorStatus {
        val availability = when (kind) {
            SensorKind.MICROPHONE -> resolve(
                hasHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                permission = Manifest.permission.RECORD_AUDIO,
            )

            SensorKind.CAMERA -> resolve(
                hasHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                permission = Manifest.permission.CAMERA,
            )

            SensorKind.AMBIENT_LIGHT -> resolve(hasSensor(Sensor.TYPE_LIGHT))
            SensorKind.ACCELEROMETER -> resolve(hasSensor(Sensor.TYPE_ACCELEROMETER))
            SensorKind.PROXIMITY -> resolve(hasSensor(Sensor.TYPE_PROXIMITY))
        }
        return SensorStatus(kind, availability)
    }

    private fun hasSensor(type: Int): Boolean =
        sensorManager?.getDefaultSensor(type) != null

    /** Hardware-only sensors need no runtime permission. */
    private fun resolve(hasHardware: Boolean): SensorAvailability =
        if (hasHardware) SensorAvailability.AVAILABLE else SensorAvailability.UNSUPPORTED

    private fun resolve(hasHardware: Boolean, permission: String): SensorAvailability = when {
        !hasHardware -> SensorAvailability.UNSUPPORTED
        !isGranted(permission) -> SensorAvailability.PERMISSION_REQUIRED
        else -> SensorAvailability.AVAILABLE
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
