package com.shelfit.sentinel.ui.shelf

import android.app.Activity
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.ui.components.BreathingDot
import com.shelfit.sentinel.ui.components.RippleRing
import com.shelfit.sentinel.ui.theme.Shelf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import java.util.Calendar
import kotlin.math.sin

/**
 * The shelf face: what the phone shows while it sits and listens.
 *
 * This screen is the product's premise made visible — an old phone on a shelf that reads
 * as a quiet decor object, not as a phone running an app. Everything here serves that:
 *
 * - **The lava field.** Four large soft blobs of palette colour drift on slow sine paths
 *   over the near-black ground — a lava-lamp at night, not a screensaver. Motion *is* the
 *   status: the field drifts while listening and freezes dim while paused, which is
 *   legible from across a room without reading anything.
 * - **Lava pace, lava cost.** Each blob takes minutes to cross the screen, so the field
 *   is advanced at ~15 fps by its own ticker rather than at display refresh — the motion
 *   cannot use more frames, and an always-on screen should not pay for them. The ticker
 *   stops entirely while paused.
 * - **The screen is held awake and moderately dimmed** (30%) while this screen is
 *   visible — bright enough to be an object worth looking at, dim enough for a bedroom
 *   shelf. Both are window-level effects, restored on exit.
 * - **Burn-in protection.** The clock face drifts a few dp each minute, and the lava
 *   field never holds a pixel at one colour — between them, nothing static ever sits on
 *   the OLED panels most spare phones have.
 *
 * When an automation fires, the face becomes the detection moment from the design —
 * the whole field surges bright toward cyan, expanding ripples, and a pill naming what
 * happened — then settles back to the drifting clock.
 *
 * Tap anywhere to leave. No other gesture, because a decor object has no UI to learn.
 */
@Composable
fun ShelfRoute(
    container: AppContainer,
    onExit: () -> Unit,
) {
    val listening by container.triggerEngine.isRunning.collectAsStateWithLifecycle()

    // Only outcomes from claps that happen while the shelf is showing. The coordinator's
    // flow replays recent history for other screens' benefit; replaying a lamp toggle
    // from an hour ago as a fresh detection would be a small lie.
    val enteredAt = remember { SystemClock.elapsedRealtime() }
    var moment by remember { mutableStateOf<DetectionMoment?>(null) }
    LaunchedEffect(Unit) {
        container.automationCoordinator.outcomes.collect { outcome ->
            if (outcome.event.elapsedRealtimeMillis < enteredAt) return@collect
            val described = describe(outcome.ruleName, outcome.result) ?: return@collect
            moment = described
        }
    }
    // The moment holds the face for a few seconds, then the clock returns.
    LaunchedEffect(moment) {
        if (moment != null) {
            delay(MOMENT_MILLIS)
            moment = null
        }
    }

    // Whether MainActivity's "keep screen on" collector wants the flag kept after this
    // screen's own keep-awake reason ends. Read from settings, not guessed from window
    // state, so the shelf never stamps on a choice made in Settings.
    val keepScreenOnSetting by remember {
        container.settingsRepository.settings.map { it.keepScreenOn }
    }.collectAsStateWithLifecycle(initialValue = false)

    ShelfWindowEffects(keepScreenOnElsewhere = keepScreenOnSetting)
    BackHandler(onBack = onExit)

    ShelfFace(
        listening = listening,
        moment = moment,
        onTap = onExit,
    )
}

/** What the detection moment shows: the outcome pill and its caption. */
private data class DetectionMoment(val headline: String, val caption: String)

/**
 * Turns an automation outcome into shelf-face words, or null for outcomes the shelf
 * should sleep through (skips: cooldowns, rules disabled mid-flight).
 */
private fun describe(ruleName: String, result: ActionResult): DetectionMoment? {
    // Rule names are derived as "Trigger → Action" by the editor; the action half is the
    // part worth announcing. A name without the arrow is shown whole.
    val parts = ruleName.split(" → ", limit = 2)
    val trigger = if (parts.size == 2) parts[0].lowercase() else "detected"
    val action = if (parts.size == 2) parts[1] else ruleName

    val headline = when (result) {
        is ActionResult.Success -> action
        is ActionResult.Partial -> "$action · ${result.succeeded} of ${result.succeeded + result.failed}"
        is ActionResult.Failure -> "$action · failed"
        is ActionResult.Skipped -> return null
    }

    val clock = Calendar.getInstance()
    val time = "%d:%02d".format(clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE))
    return DetectionMoment(headline = headline, caption = "$trigger · $time")
}

/**
 * Window-level behaviour for the shelf: keep awake, dim to shelf level, hide the
 * system bars.
 *
 * All three restored on dispose — including the brightness override, which would
 * otherwise leak a dimmed screen into the rest of the app.
 */
@Composable
private fun ShelfWindowEffects(keepScreenOnElsewhere: Boolean) {
    val context = LocalContext.current
    // Latest-value holder, so the effect keys on Unit: re-running it on a settings change
    // would re-capture the already-dimmed brightness as the value to "restore".
    val keepElsewhere = rememberUpdatedState(keepScreenOnElsewhere)
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val insets = WindowInsetsControllerCompat(window, window.decorView)

        val previousBrightness = window.attributes.screenBrightness
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = SHELF_BRIGHTNESS }
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            // Keep-screen-on is deliberately NOT cleared here: MainActivity owns that flag
            // for the "keep screen on" setting, and clearing it would stamp on a choice the
            // user made in Settings. Brightness and bars are this screen's own overrides.
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            insets.show(WindowInsetsCompat.Type.systemBars())
            if (!keepElsewhere.value) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
private fun ShelfFace(
    listening: Boolean,
    moment: DetectionMoment?,
    onTap: () -> Unit,
) {
    val palette = Shelf.palette

    // The detection surge: the lava field brightens fast when a moment lands and fades
    // slowly after it ends, so the flash reads as an event rather than a glitch.
    val flare = remember { Animatable(0f) }
    LaunchedEffect(moment != null) {
        if (moment != null) {
            flare.animateTo(1f, tween(durationMillis = 300))
        } else {
            flare.animateTo(0f, tween(durationMillis = 1_800))
        }
    }

    // Burn-in drift: a new small offset every minute, moved to slowly enough that the
    // change is never seen, only its consequences avoided.
    var minute by remember { mutableLongStateOf(0L) }
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val timeFormat = DateFormat.getTimeFormat(context)
        val dateFormat = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        while (true) {
            val now = Calendar.getInstance()
            timeText = timeFormat.format(now.time)
            dateText = dateFormat.format(now.time)
            minute = now.timeInMillis / 60_000L
            // Wake at the next minute boundary rather than polling.
            delay(60_000L - now.timeInMillis % 60_000L)
        }
    }
    val drift = remember(minute) {
        // Deterministic pseudo-random walk over a ±9dp box, seeded by the minute.
        val seed = minute * 2654435761L
        IntOffset(
            x = ((seed ushr 8) % 19 - 9).toInt(),
            y = ((seed ushr 16) % 19 - 9).toInt(),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.ambientGround)
            .drawBehind {
                // The floor glow. Centred low for the idle face, mid-screen while a
                // detection is showing — exactly the two artboards.
                val centreY = if (moment == null) 0.84f else 0.46f
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(palette.ambientGlow, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * centreY),
                        radius = size.width * 0.95f,
                    ),
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LavaField(listening = listening, flare = flare.value)

        AnimatedVisibility(
            visible = moment == null,
            enter = fadeIn(tween(900)),
            exit = fadeOut(tween(400)),
        ) {
            Column(
                modifier = Modifier.offset { drift },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = timeText,
                    fontSize = 92.sp,
                    fontWeight = FontWeight.W200,
                    letterSpacing = 0.02.em,
                    color = Color(0x85C4DAFF),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = dateText.uppercase(),
                    fontSize = 12.sp,
                    letterSpacing = 0.3.em,
                    color = Color(0x4D96AFDC),
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(
                    modifier = Modifier.padding(top = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (listening) {
                        BreathingDot(size = 7.dp)
                    } else {
                        Box(
                            Modifier
                                .padding(vertical = 7.dp)
                                .size(7.dp)
                                .background(Color(0x338CA3C9), CircleShape),
                        )
                    }
                    Text(
                        text = if (listening) "LISTENING" else "PAUSED",
                        fontSize = 10.sp,
                        letterSpacing = 0.34.em,
                        color = Color(0x528CAADC),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = moment != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(900)),
        ) {
            val shown = moment ?: return@AnimatedVisibility
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    RippleRing(Modifier.size(180.dp), delayMillis = 0, alpha = 0.40f)
                    RippleRing(Modifier.size(180.dp), delayMillis = 700, alpha = 0.30f)
                    RippleRing(Modifier.size(180.dp), delayMillis = 1400, alpha = 0.20f)
                    Box(
                        Modifier
                            .size(11.dp)
                            .drawBehind {
                                drawCircle(
                                    Brush.radialGradient(
                                        listOf(palette.cyan.copy(alpha = 0.5f), Color.Transparent),
                                    ),
                                    radius = size.minDimension * 2f,
                                )
                                drawCircle(palette.cyan)
                            },
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 26.dp)
                        .background(Color(0x1A6EBEFF), RoundedCornerShape(999.dp))
                        .border(1.dp, Color(0x4D86D2FF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = shown.headline,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.W600,
                        letterSpacing = 0.05.em,
                        color = Color(0xFFBFE9FF),
                    )
                }
                Text(
                    text = shown.caption.uppercase(),
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 10.5.sp,
                    letterSpacing = 0.24.em,
                    color = Color(0x6696B4DC),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The lava field: four large soft-edged blobs of palette colour, drifting over the
 * near-black ground on independent sine paths.
 *
 * Deliberate choices, each of which looks like an accident to remove:
 *
 * - **Its own ~15 fps ticker, not the display clock.** The blobs move so slowly that
 *   per-frame displacement at 15 fps is well under a pixel; rendering at 60–120 fps
 *   would burn power on an always-on screen for motion no eye can resolve. The ticker
 *   advances a time accumulator from `elapsedRealtime`, so a dropped frame slows
 *   nothing — the field just catches up.
 * - **Time is a Double.** The face runs for weeks; a Float accumulator loses enough
 *   precision after a few days that the sine paths start to visibly stutter.
 * - **Frozen while paused.** The ticker keys on `listening` and simply does not run
 *   when it is false, so a paused shelf costs zero redraws — and stillness *is* the
 *   paused indicator.
 * - **Colours come from the palette**, alpha-modulated here. The flare pulls every blob
 *   toward cyan — the design's detection colour — rather than brightening in place.
 */
@Composable
private fun LavaField(listening: Boolean, flare: Float) {
    val palette = Shelf.palette

    var time by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(LAVA_FRAME_MILLIS)
            val now = SystemClock.elapsedRealtime()
            time += (now - last) / 1000.0
            last = now
        }
    }

    // Each row: colour, base alpha, radius (× width), x/y drift amplitude, x/y angular
    // speed (rad/s) and phase. Periods run 70–160 s — lava pace. The speeds share no
    // common factor, so the composition never visibly repeats.
    val blobs = remember(palette) {
        listOf(
            LavaBlob(palette.accentStart, 0.20f, 1.00f, 0.36f, 0.40f, 0.052, 0.039, 0.0, 1.7),
            LavaBlob(palette.ambientGlow.copy(alpha = 1f), 0.26f, 1.15f, 0.40f, 0.42f, 0.043, 0.061, 3.1, 0.6),
            LavaBlob(palette.cyan, 0.12f, 0.72f, 0.32f, 0.36f, 0.067, 0.031, 5.0, 2.4),
            LavaBlob(palette.accent, 0.16f, 0.88f, 0.38f, 0.32f, 0.036, 0.049, 4.2, 5.5),
        )
    }

    val restAlpha = if (listening) 1f else LAVA_PAUSED_DIM
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val t = time
                blobs.forEach { blob ->
                    val cx = size.width * (0.5f + blob.driftX * sin(t * blob.speedX + blob.phaseX).toFloat())
                    val cy = size.height * (0.5f + blob.driftY * sin(t * blob.speedY + blob.phaseY).toFloat())
                    // A slow breathing of each blob's size, phase-shifted off its path.
                    val radius = size.width * blob.radius *
                        (1f + 0.10f * sin(t * 0.027 + blob.phaseX + 2.0).toFloat())
                    val colour = lerp(blob.colour, palette.cyan, flare * 0.6f)
                    val alpha = (blob.alpha * restAlpha * (1f + 1.3f * flare)).coerceAtMost(0.55f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colour.copy(alpha = alpha), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                }
            },
    )
}

private data class LavaBlob(
    val colour: Color,
    val alpha: Float,
    val radius: Float,
    val driftX: Float,
    val driftY: Float,
    val speedX: Double,
    val speedY: Double,
    val phaseX: Double,
    val phaseY: Double,
)

private const val SHELF_BRIGHTNESS = 0.30f
private const val MOMENT_MILLIS = 5_000L
private const val LAVA_FRAME_MILLIS = 66L
private const val LAVA_PAUSED_DIM = 0.40f
