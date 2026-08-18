package com.shelfit.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shelfit.sentinel.ui.theme.Shelf
import kotlin.math.log10

/**
 * Shared card used by the dashboard, the test screen and calibration.
 *
 * Now the design's glass card: the hi-fi artboards draw every content group as a faint
 * blue panel with a hairline border and a spaced-caps accent label. Keeping the old
 * name means every existing call site picked the design up without being edited.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassCard(modifier = modifier) {
        SectionLabel(title)
        content()
    }
}

@Composable
fun LabelledRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.textDim,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasise) Shelf.palette.warn else Shelf.palette.text,
        )
    }
}

/**
 * Level on a decibel scale, with optional ticks for the tracked background and the
 * threshold in force. A clap should read as a wide gap between the bar and the ticks.
 */
@Composable
fun LevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    background: Float? = null,
    threshold: Float? = null,
) {
    // The design's meter: a 10dp well, a gradient fill, and ticks that overhang the
    // track by 4dp so they stay visible when the level bar passes them.
    val palette = Shelf.palette
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(METER_HEIGHT + TICK_OVERHANG * 2),
    ) {
        val trackWidth = maxWidth

        Box(
            Modifier
                .padding(vertical = TICK_OVERHANG)
                .fillMaxWidth()
                .height(METER_HEIGHT)
                .background(Color(0x1F7EA6FF), RoundedCornerShape(5.dp)),
        )
        Box(
            Modifier
                .padding(vertical = TICK_OVERHANG)
                .fillMaxWidth(meterFraction(level))
                .height(METER_HEIGHT)
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.accentStart, palette.accentEnd),
                    ),
                    RoundedCornerShape(5.dp),
                ),
        )

        background?.let { Tick(trackWidth, it, Color(0x8096B4E1)) }
        threshold?.let { Tick(trackWidth, it, palette.warn) }
    }
}

@Composable
private fun Tick(trackWidth: androidx.compose.ui.unit.Dp, amplitude: Float, color: Color) {
    Box(
        modifier = Modifier
            .offset(x = trackWidth * meterFraction(amplitude))
            .width(2.dp)
            .height(METER_HEIGHT + TICK_OVERHANG * 2)
            .background(color, RoundedCornerShape(1.dp)),
    )
}

/** Maps a 0f..1f amplitude onto the meter via decibels, which matches how loudness reads. */
private fun meterFraction(amplitude: Float): Float {
    if (amplitude <= 0f) return 0f
    val decibels = 20f * log10(amplitude)
    return ((decibels - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
}

fun formatDecibels(amplitude: Float): String {
    if (amplitude <= 0f) return "—"
    return "%.0f dB".format(20f * log10(amplitude))
}

fun formatMultiple(ratio: Float): String = "%.1fx".format(ratio)

fun formatConfidence(confidence: Float): String = "%.2f".format(confidence)

private val METER_HEIGHT = 10.dp
private val TICK_OVERHANG = 4.dp
private const val METER_FLOOR_DB = -70f
