package com.shelfit.sentinel

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as SentinelApplication).container

        observeKeepScreenOn(container)

        setContent {
            SentinelTheme {
                SentinelNavHost(container = container)
            }
        }
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
}
