package com.shelfit.sentinel

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shelfit.sentinel.ui.navigation.SentinelNavHost
import com.shelfit.sentinel.ui.theme.SentinelTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The only activity. Detection is owned by [AppContainer], not by this class, so
 * the UI can come and go without disturbing the trigger engine.
 */
class MainActivity : ComponentActivity() {

    /**
     * Set when the resume notification launched us, acted on in [onResume].
     *
     * Waiting for RESUMED is not ceremony: starting a microphone foreground service
     * requires the app to hold while-in-use microphone access, and a visible, resumed
     * activity is what grants it. Firing during onCreate risks Android refusing.
     */
    private var resumeRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is one committed dark world, so the system bars are told so outright
        // rather than left to follow a system theme the UI ignores.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val container = (application as SentinelApplication).container

        readIntent(intent)
        observeKeepScreenOn(container)

        setContent {
            SentinelTheme {
                SentinelNavHost(container = container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!resumeRequested) return
        resumeRequested = false

        val container = (application as SentinelApplication).container
        lifecycleScope.launch { container.sensorModeController.enable() }
    }

    private fun readIntent(intent: Intent?) {
        if (intent?.action == ACTION_RESUME_SENSOR_MODE) resumeRequested = true
    }

    /**
     * Applies the "keep screen on" preference. Scoped to STARTED so the flag is
     * dropped whenever the UI is not in front — an always-on device should not hold
     * the screen awake from the background.
     */
    private fun observeKeepScreenOn(container: AppContainer) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settingsRepository.settings
                    .map { it.keepScreenOn }
                    .distinctUntilChanged()
                    .collect { keepOn ->
                        if (keepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
            }
        }
    }

    companion object {
        /**
         * Sent by the "tap to resume listening" notification. One tap is the fewest
         * Android allows: the microphone service cannot be started from the
         * notification itself.
         */
        const val ACTION_RESUME_SENSOR_MODE = "com.shelfit.sentinel.action.RESUME_SENSOR_MODE"
    }
}
