package com.flow.app

/*
 * Neo-brutalism design system for Flow.
 *
 * Recipe shared by every block: a flat background fill, a 3dp solid ink border,
 * a 4dp corner radius, and a HARD non-blurred drop shadow drawn as a second
 * identical-shape solid ink rectangle offset by (6dp, 6dp) behind it. We never
 * use Modifier.shadow / Material elevation (those blur). The shadow is reserved
 * by padding the block end+bottom by the offset, then drawBehind paints a solid
 * rounded-rect copy shifted into that reserved space.
 *
 * NeoTheme  = token holder used by component defaults (colors + dims).
 * NeoColors = color-only mirror exposed for screens to recolor blocks.
 */

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------

/** Central token holder. NOT a composable. Single source of truth. */
object NeoTheme {
    val bgCream: Color = Color(0xFF000000)
    val surfaceWhite: Color = Color(0xFF0C1018)
    val surfaceMint: Color = Color(0xFF10203F)
    val primaryGreen: Color = Color(0xFF1F5CFF)
    val accentGreenDeep: Color = Color(0xFF5B8CFF)
    val ink: Color = Color(0xFFFFFFFF)
    val inkMuted: Color = Color(0xFF9DB2D9)
    val dangerRed: Color = Color(0xFFFF5A6A)

    val borderWidth: Dp = 3.dp
    val shadowOffset: Dp = 6.dp
    val corner: Dp = 4.dp
    val shape: Shape = RoundedCornerShape(4.dp)
}

/** Color-only mirror of [NeoTheme], exposed for screens to recolor blocks. */
object NeoColors {
    val bgCream: Color = NeoTheme.bgCream
    val surfaceWhite: Color = NeoTheme.surfaceWhite
    val surfaceMint: Color = NeoTheme.surfaceMint
    val primaryGreen: Color = NeoTheme.primaryGreen
    val accentGreenDeep: Color = NeoTheme.accentGreenDeep
    val ink: Color = NeoTheme.ink
    val inkMuted: Color = NeoTheme.inkMuted
    val dangerRed: Color = NeoTheme.dangerRed
    val onPrimary: Color = Color(0xFFFFFFFF)
    val border: Color = Color(0xFF5B8CFF)
    val shadow: Color = Color(0xFF1F5CFF)
}

/** Typography presets — never below Medium weight; all ink by default. */
object NeoType {
    val screenTitle = TextStyle(fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 32.sp, color = NeoTheme.ink)
    val sectionTitle = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = NeoTheme.ink)
    val cardHeading = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NeoTheme.ink)
    val body = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink)
    val bodySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NeoTheme.ink)
    val badge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp, color = NeoTheme.ink)
    val button = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 0.3.sp, color = NeoTheme.ink)
}

// ---------------------------------------------------------------------------
// Core building block
// ---------------------------------------------------------------------------

/**
 * Renders the core neo-brutalist recipe: a hard non-blurred offset shadow rect
 * + bordered filled block. All other visual components build on this.
 */
@Composable
fun NeoSurface(
    modifier: Modifier = Modifier,
    backgroundColor: Color = NeoTheme.surfaceWhite,
    borderColor: Color = NeoTheme.ink,
    shadowColor: Color = NeoTheme.ink,
    shadowOffset: Dp = NeoTheme.shadowOffset,
    cornerRadius: Dp = NeoTheme.corner,
    borderWidth: Dp = NeoTheme.borderWidth,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            // Reserve room on the end/bottom so the offset shadow has space to
            // live without overlapping siblings or overflowing a fillMaxWidth parent.
            .padding(end = shadowOffset, bottom = shadowOffset)
            // Draw the HARD shadow first (no blur): a solid rounded rect copy of
            // the block, shifted into the reserved space. drawBehind paints into the
            // post-padding bounds and is allowed to overflow them.
            .drawBehind {
                val off = shadowOffset.toPx()
                drawRoundRect(
                    color = shadowColor,
                    topLeft = Offset(off, off),
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                )
            }
            .background(backgroundColor, shape)
            .border(borderWidth, borderColor, shape),
        content = content
    )
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/** Top bar: title (ScreenTitle) on the left, status as a mint NeoBadge on the right. */
@Composable
fun NeoTopBar(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = NeoTheme.surfaceWhite
) {
    NeoSurface(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = NeoType.screenTitle)
            if (status.isNotBlank()) {
                NeoBadge(text = status, backgroundColor = NeoTheme.surfaceMint)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------

/** Plain data holder for one bottom-nav entry. Not a composable. */
data class NeoNavItem(val id: String, val label: String, val icon: ImageVector)

/** Bottom navigation row of equal-weight bordered blocks; selected fills green. */
@Composable
fun NeoBottomNav(
    items: List<NeoNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { item ->
            val selected = item.id == selectedId
            NeoSurface(
                modifier = Modifier.weight(1f),
                backgroundColor = if (selected) NeoTheme.primaryGreen else NeoTheme.surfaceWhite,
                shadowOffset = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(item.id) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = NeoTheme.ink,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = item.label,
                        style = NeoType.bodySmall.copy(
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                        )
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

/** General container block. `content` runs in a ColumnScope inset by contentPadding. */
@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = NeoTheme.surfaceWhite,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    shadowOffset: Dp = NeoTheme.shadowOffset,
    content: @Composable ColumnScope.() -> Unit
) {
    NeoSurface(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shadowOffset = shadowOffset
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/** Primary filled action button: vivid green fill, ink border, ExtraBold ink label. */
@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = NeoTheme.primaryGreen,
    contentColor: Color = NeoTheme.ink,
    leadingIcon: ImageVector? = null
) {
    val fill = if (enabled) backgroundColor else desaturate(backgroundColor)
    val ink = if (enabled) contentColor else NeoTheme.inkMuted
    NeoButtonShell(
        modifier = modifier,
        backgroundColor = fill,
        enabled = enabled,
        onClick = onClick,
        text = text,
        contentColor = ink,
        leadingIcon = leadingIcon
    )
}

/** Secondary button: surfaceWhite fill, ink border, ExtraBold ink label. */
@Composable
fun NeoOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = NeoTheme.ink,
    leadingIcon: ImageVector? = null
) {
    val ink = if (enabled) contentColor else NeoTheme.inkMuted
    NeoButtonShell(
        modifier = modifier,
        backgroundColor = NeoTheme.surfaceWhite,
        enabled = enabled,
        onClick = onClick,
        text = text,
        contentColor = ink,
        leadingIcon = leadingIcon
    )
}

@Composable
private fun NeoButtonShell(
    modifier: Modifier,
    backgroundColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    text: String,
    contentColor: Color,
    leadingIcon: ImageVector?
) {
    NeoSurface(
        modifier = modifier,
        backgroundColor = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .heightIn(min = 48.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = NeoType.button.copy(color = contentColor))
        }
    }
}

// ---------------------------------------------------------------------------
// Text field
// ---------------------------------------------------------------------------

/** Bordered input block: surfaceWhite fill, ink border, hard shadow, no Material underline. */
@Composable
fun NeoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = NeoType.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        NeoSurface(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = NeoTheme.surfaceWhite
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        textStyle = NeoType.body,
                        cursorBrush = SolidColor(NeoTheme.ink),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = NeoType.body.copy(color = NeoTheme.inkMuted)
                        )
                    }
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingIcon()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Badge
// ---------------------------------------------------------------------------

/** Small chunky pill: filled bg, ink border, Bold 12sp ALL-CAPS text, tiny 2dp shadow. */
@Composable
fun NeoBadge(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = NeoTheme.surfaceMint,
    contentColor: Color = NeoTheme.ink
) {
    NeoSurface(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shadowOffset = 2.dp,
        cornerRadius = 4.dp
    ) {
        Text(
            text = text.uppercase(),
            style = NeoType.badge.copy(color = contentColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Switch row
// ---------------------------------------------------------------------------

/** Full-width row inside a bordered block: label on the left, squared neo toggle on the right. */
@Composable
fun NeoSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    NeoSurface(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = NeoTheme.surfaceWhite
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = NeoType.body,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            NeoToggle(checked = checked)
        }
    }
}

@Composable
private fun NeoToggle(checked: Boolean) {
    val trackWidth = 52.dp
    val trackHeight = 30.dp
    val thumbSize = 22.dp
    val shape = RoundedCornerShape(4.dp)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - 4.dp - 3.dp else 4.dp,
        label = "neoThumb"
    )
    Box(
        modifier = Modifier
            .size(width = trackWidth, height = trackHeight)
            .background(if (checked) NeoTheme.primaryGreen else NeoTheme.surfaceWhite, shape)
            .border(NeoTheme.borderWidth, NeoTheme.ink, shape),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .background(NeoTheme.ink, RoundedCornerShape(3.dp))
                .border(2.dp, NeoTheme.ink, RoundedCornerShape(3.dp))
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Cheap desaturation toward dark grey for disabled fills (no blur, keeps the flat look). */
private fun desaturate(c: Color): Color {
    val gray = 0.78f
    return Color(
        red = c.red * (1f - gray) + 0.165f * gray,
        green = c.green * (1f - gray) + 0.184f * gray,
        blue = c.blue * (1f - gray) + 0.227f * gray,
        alpha = c.alpha
    )
}
