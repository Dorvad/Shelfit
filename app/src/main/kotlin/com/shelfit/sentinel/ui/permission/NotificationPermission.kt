package com.shelfit.sentinel.ui.permission

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Notification permission as the UI needs to see it.
 *
 * @param needsRequest true only where a runtime request is possible and would help. On
 *   Android 12 and below, or when the user has switched notifications off in system
 *   settings, the only route is the settings page.
 */
class NotificationPermissionState(
    val enabled: Boolean,
    val needsRequest: Boolean,
    val request: () -> Unit,
)

/**
 * Tracks `POST_NOTIFICATIONS` and whether notifications are enabled at all.
 *
 * Both matter: without a visible notification the foreground service still runs, but its
 * Pause and Resume controls are unreachable and the user has no indication the phone is
 * listening.
 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(context.notificationsEnabled()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { enabled = context.notificationsEnabled() }

    LifecycleResumeEffect(Unit) {
        enabled = context.notificationsEnabled()
        onPauseOrDispose { }
    }

    return remember(enabled, context) {
        NotificationPermissionState(
            enabled = enabled,
            needsRequest = !enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            request = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}

private fun Context.notificationsEnabled(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()
