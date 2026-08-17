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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.log10

/** Shared card used by the dashboard, the test screen and calibration. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
fun LabelledRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasise) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(METER_HEIGHT)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        val trackWidth = maxWidth

        Box(
            modifier = Modifier
                .fillMaxWidth(meterFraction(level))
                .height(METER_HEIGHT)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp),
                ),
        )

        background?.let { Tick(trackWidth, it, MaterialTheme.colorScheme.error) }
        threshold?.let { Tick(trackWidth, it, MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun Tick(trackWidth: androidx.compose.ui.unit.Dp, amplitude: Float, color: Color) {
    Box(
        modifier = Modifier
            .offset(x = trackWidth * meterFraction(amplitude))
            .width(2.dp)
            .height(METER_HEIGHT)
            .background(color),
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

private val METER_HEIGHT = 20.dp
private const val METER_FLOOR_DB = -70f
