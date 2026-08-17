package com.shelfit.sentinel.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Microphone permission as the UI needs to see it.
 *
 * @param granted whether capture may proceed.
 * @param deniedAfterRequest true once the user has refused. The system stops showing
 *   the dialog after a repeat refusal, so at that point the only route left is the
 *   app's settings page.
 */
class MicrophonePermissionState(
    val granted: Boolean,
    val deniedAfterRequest: Boolean,
    val request: () -> Unit,
    val openAppSettings: () -> Unit,
)

/**
 * Tracks and requests `RECORD_AUDIO`.
 *
 * Re-reads on resume: the permission can be revoked from system settings while the
 * app sits in the background, which on an always-on device is routine rather than
 * exceptional.
 */
@Composable
fun rememberMicrophonePermissionState(): MicrophonePermissionState {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(context.hasMicrophonePermission()) }
    var deniedAfterRequest by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        deniedAfterRequest = !isGranted
    }

    LifecycleResumeEffect(Unit) {
        granted = context.hasMicrophonePermission()
        if (granted) deniedAfterRequest = false
        onPauseOrDispose { }
    }

    return remember(granted, deniedAfterRequest, context) {
        MicrophonePermissionState(
            granted = granted,
            deniedAfterRequest = deniedAfterRequest,
            request = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            openAppSettings = { context.openAppSettings() },
        )
    }
}

private fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
