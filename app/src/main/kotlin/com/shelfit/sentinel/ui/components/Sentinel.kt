package com.shelfit.sentinel.ui.components

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.shelfit.sentinel.R
import com.shelfit.sentinel.ui.theme.Shelf

/**
 * The design system's building blocks, straight from the hi-fi artboards.
 *
 * Everything here reads its colours from [Shelf.palette] and nothing else, so the three
 * gradient directions the design ships (Sapphire, Ocean, Midnight) stay a one-line swap
 * in the theme. Sizes and alphas are the artboards' numbers, not approximations — where
 * a value looks oddly specific (a 46% gradient stop, a 0.14em tracking), it is because
 * the design said so.
 */

/* ---------------------------------------------------------------- background */

/**
 * The screen ground: the deep blue fall-off plus a faint cool glow at the top left.
 *
 * Drawn, not composed from layered boxes, so it costs one draw pass. The gradient runs
 * 8° off vertical exactly as the CSS `172deg` does — barely perceptible, but its absence
 * reads as "flatter than the mock" without anyone being able to say why.
 */
fun Modifier.shelfBackground(
    top: Color,
    mid: Color,
    bottom: Color,
): Modifier = drawBehind {
    val tilt = size.width * 0.07f
    drawRect(
        Brush.linearGradient(
            0.00f to top,
            0.46f to mid,
            1.00f to bottom,
            start = Offset(size.width / 2 + tilt, 0f),
            end = Offset(size.width / 2 - tilt, size.height),
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0x2978AAFF), Color.Transparent),
            center = Offset(size.width * 0.18f, -size.height * 0.06f),
            radius = size.width * 1.1f,
        ),
    )
}

/**
 * Screen frame: gradient ground, back-arrow top bar, scrolling column of content.
 *
 * One scaffold for every screen keeps the padding rhythm (18dp gutters, 13dp gaps)
 * identical across the app — the artboards share it, and drift here is what makes an
 * app feel assembled from parts.
 */
@Composable
fun SentinelScreen(
    title: String,
    onNavigateBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = Shelf.palette
    Box(
        modifier
            .fillMaxSize()
            .shelfBackground(palette.gradientTop, palette.gradientMid, palette.gradientBottom),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = Color(0xFFBDD2F6),
                        )
                    }
                } else {
                    Box(Modifier.width(12.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                topBarActions()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
                content = content,
            )
        }
    }
}

/* ---------------------------------------------------------------- surfaces */

/** Spaced-caps section label, the design's way of naming a group without a box. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Shelf.palette.accent,
        modifier = modifier,
    )
}

/** The standard card: faint blue fill, hairline border, 18dp radius. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = Shelf.palette
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardFill)
            .border(1.dp, palette.cardLine, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        content = content,
    )
}

/**
 * The emphasized card — the screen's one bright object.
 *
 * Each artboard allows itself exactly one (the sensor-mode state, the connected
 * provider, the calibration verdict). Using two on a screen is how emphasis dies;
 * keep the discipline the design keeps.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = Shelf.palette
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0x2E5A96FF), Color(0x0A5A96FF)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .border(1.dp, palette.heroLine, shape)
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/* ---------------------------------------------------------------- buttons */

/** Primary action: the accent gradient with dark ink. One per screen, like the hero. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = Shelf.palette
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (enabled) 12.dp else 0.dp,
                shape = shape,
                ambientColor = Color(0x484682F0),
                spotColor = Color(0x484682F0),
            )
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd))
                } else {
                    Brush.linearGradient(listOf(Color(0x338CAADC), Color(0x338CAADC)))
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) palette.onAccent else Color(0x66EAF1FF),
        )
    }
}

/** Secondary action: hairline outline, quiet label. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = Shelf.palette
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) palette.outlineText else palette.textFaint,
        )
    }
}

/** Tertiary action: bare accent text. */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Shelf.palette.accent,
            textAlign = TextAlign.Center,
        )
    }
}

/* ---------------------------------------------------------------- selection controls */

/**
 * The design's 44×26 toggle: accent gradient when on, slate when off.
 *
 * Custom-drawn because the gradient fill is the design's signature and Material's
 * Switch only takes flat colours. The touch target is padded to 48dp behind the
 * visible pill, which is why this composable is wider than it looks.
 */
@Composable
fun ShelfSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = Shelf.palette
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    if (checked) {
                        Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd))
                    } else {
                        Brush.linearGradient(listOf(Color(0x338CAADC), Color(0x338CAADC)))
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) Color(0xFFF4F8FF) else Color(0xFF8CA3C9)),
            )
        }
    }
}

/** 20dp radio: accent ring and dot when chosen, faint ring otherwise. */
@Composable
fun ShelfRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val palette = Shelf.palette
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(
                2.dp,
                if (selected) palette.accentStart else Color(0x597EA6FF),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(palette.accentStart),
            )
        }
    }
}

/** 20dp checkbox: gradient fill with a dark tick when checked. */
@Composable
fun ShelfCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val palette = Shelf.palette
    val shape = RoundedCornerShape(6.dp)
    if (checked) {
        Box(
            modifier = modifier
                .size(20.dp)
                .clip(shape)
                .background(
                    Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd)),
                )
                .drawBehind {
                    val path = Path().apply {
                        moveTo(size.width * 0.22f, size.height * 0.52f)
                        lineTo(size.width * 0.43f, size.height * 0.72f)
                        lineTo(size.width * 0.79f, size.height * 0.30f)
                    }
                    drawPath(
                        path,
                        color = Color(0xFF06122B),
                        style = Stroke(
                            width = size.width * 0.16f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                },
        )
    } else {
        Box(
            modifier = modifier
                .size(20.dp)
                .clip(shape)
                .border(2.dp, Color(0x597EA6FF), shape),
        )
    }
}

/** The design's segmented control: a well with one lit segment. */
@Composable
fun ShelfSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
) {
    val palette = Shelf.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x147EA6FF))
            .border(1.dp, Color(0x247EA6FF), RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val canSelect = enabled(index)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Color(0x387EB4FF) else Color.Transparent)
                    .clickable(enabled = canSelect, role = Role.RadioButton) { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        selected -> Color(0xFFE2EEFF)
                        !canSelect -> palette.textFaint.copy(alpha = 0.5f)
                        else -> Color(0xFF6E85AE)
                    },
                )
            }
        }
    }
}

/* ---------------------------------------------------------------- status */

enum class PillTone { Good, Warn, Neutral }

/** Rounded status chip: Connected / Blocked / Available. */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val palette = Shelf.palette
    val (fill, borderColor, textColor) = when (tone) {
        PillTone.Good -> Triple(Color(0x1F7CE0DC), Color(0x5A7CE0DC), palette.cyan)
        PillTone.Warn -> Triple(Color.Transparent, Color(0x66FFC38B), palette.warn)
        PillTone.Neutral -> Triple(Color.Transparent, palette.outline, palette.textDim)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/**
 * The listening indicator: a small cyan dot that breathes.
 *
 * 5.5 s period from the design — slow enough to read as calm, the difference between
 * a decor object and a notification LED. The glow is drawn, not shadowed, so it costs
 * nothing extra.
 */
@Composable
fun BreathingDot(modifier: Modifier = Modifier, size: Dp = 9.dp) {
    val palette = Shelf.palette
    val transition = rememberInfiniteTransition(label = "breathe")
    val phase by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathePhase",
    )
    Box(
        modifier = modifier.size(size * 3),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    val scale = 1f + 0.22f * ((phase - 0.35f) / 0.6f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(palette.cyan.copy(alpha = phase * 0.55f), Color.Transparent),
                        ),
                        radius = this.size.minDimension * 1.6f * scale,
                    )
                    drawCircle(
                        color = palette.cyan.copy(alpha = phase),
                        radius = this.size.minDimension / 2f * scale,
                    )
                },
        )
    }
}

/** One cell of the design's stat strip: a big thin number over a spaced-caps label. */
@Composable
fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Shelf.palette.text,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = valueColor,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.12.em),
            color = Shelf.palette.textFaint,
        )
    }
}

/** Hairline divider in the design's line colour. */
@Composable
fun ShelfDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Shelf.palette.line),
    )
}

/** Ripple ring used by the detection moment: expands from 35% to 190% and fades. */
@Composable
fun RippleRing(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    alpha: Float = 0.4f,
) {
    val palette = Shelf.palette
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, delayMillis = delayMillis, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rippleProgress",
    )
    Box(
        modifier.drawBehind {
            val scale = 0.35f + progress * (1.9f - 0.35f)
            drawCircle(
                color = palette.cyan.copy(alpha = alpha * (0.75f * (1f - progress))),
                radius = size.minDimension / 2f * scale,
                style = Stroke(width = 1.dp.toPx()),
            )
        },
    )
}
