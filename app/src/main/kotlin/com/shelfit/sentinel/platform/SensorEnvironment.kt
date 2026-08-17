package com.shelfit.sentinel.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shelfit.sentinel.core.sensormode.BatteryOptimisationStatus
import com.shelfit.sentinel.core.sensormode.PermissionStatus

/** A snapshot of everything about the device that decides whether Sensor Mode can work. */
data class SensorEnvironmentSnapshot(
    val microphone: PermissionStatus,
    val notifications: PermissionStatus,
    val batteryOptimisation: BatteryOptimisationStatus,
)

/**
 * Reads the parts of the system that govern unattended operation.
 *
 * Read live each time rather than cached: permissions can be revoked and battery
 * exemptions withdrawn from system settings while the app is in the background, which
 * for a device left running for weeks is routine rather than exceptional.
 */
class SensorEnvironment(context: Context) {

    private val appContext = context.applicationContext

    fun snapshot(): SensorEnvironmentSnapshot = SensorEnvironmentSnapshot(
        microphone = microphonePermission(),
        notifications = notificationPermission(),
        batteryOptimisation = batteryOptimisation(),
    )

    fun microphonePermission(): PermissionStatus =
        if (isGranted(Manifest.permission.RECORD_AUDIO)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }

    /**
     * Whether the foreground-service notification will actually be visible.
     *
     * `POST_NOTIFICATIONS` only exists from Android 13, but a user can switch an app's
     * notifications off on any version, and on Android 13+ they can also switch off the
     * single channel while leaving the permission granted.
     * `areNotificationsEnabled` covers all of that in one question, which is the
     * question that matters: without a visible notification the service still runs, but
     * Pause and Resume become unreachable and the user has no sign it is on.
     */
    fun notificationPermission(): PermissionStatus = when {
        !NotificationManagerCompat.from(appContext).areNotificationsEnabled() ->
            PermissionStatus.DENIED

        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> PermissionStatus.NOT_REQUIRED
        isGranted(Manifest.permission.POST_NOTIFICATIONS) -> PermissionStatus.GRANTED
        else -> PermissionStatus.DENIED
    }

    /**
     * Whether the system has been asked to leave this app alone.
     *
     * `isIgnoringBatteryOptimizations` is a documented, permission-free read. It does
     * not capture OEM-specific "app killers", which are not exposed by any public API —
     * so an EXEMPT result means "AOSP will not interfere", not "nothing will".
     */
    fun batteryOptimisation(): BatteryOptimisationStatus {
        val power = ContextCompat.getSystemService(appContext, PowerManager::class.java)
            ?: return BatteryOptimisationStatus.UNKNOWN
        return if (power.isIgnoringBatteryOptimizations(appContext.packageName)) {
            BatteryOptimisationStatus.EXEMPT
        } else {
            BatteryOptimisationStatus.OPTIMISED
        }
    }

    /**
     * Opens the system list where an app can be exempted from battery optimisation.
     *
     * This is the deliberate choice over `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
     * which shows a one-tap system dialog but requires holding
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Google Play restricts that permission to a
     * short list of app categories and rejects apps that request it outside them. This
     * route costs the user two extra taps, needs no permission, and cannot put a listing
     * at risk. If this app is never distributed through Play, switching to the direct
     * request is a small, contained change — and still a documented API either way.
     */
    fun batteryOptimisationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** This app's own settings page, where notifications and permissions can be changed. */
    fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
}
