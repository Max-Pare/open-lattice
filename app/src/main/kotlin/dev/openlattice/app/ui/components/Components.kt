package dev.openlattice.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openlattice.app.ui.label
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.MachineState
import dev.openlattice.core.PrinterState

/** A flat card on the ground colour: the basic container of every screen. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color = Lattice.colors.panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val inner: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, contentColor = MaterialTheme.colorScheme.onSurface, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, contentColor = MaterialTheme.colorScheme.onSurface, content = inner)
    }
}

/** "Device control            More ›" */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold))
        if (action != null && onAction != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, color = Lattice.colors.muted, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Lattice.colors.muted)
            }
        }
    }
}

/** Small grey caption above a settings group. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp), color = Lattice.colors.muted, style = MaterialTheme.typography.bodyMedium)
}

/** A panel of [SettingsRow]s; put [GroupDivider] between rows. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier.fillMaxWidth(), padding = PaddingValues(0.dp), content = content)
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Lattice.colors.divider.copy(alpha = 0.5f))
}

/** Title on the left, optional value and chevron on the right, like a phone's settings list. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    chevron: Boolean = onClick != null && !destructive,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Lattice.colors.muted) }
        }
        value?.let {
            Text(
                it,
                Modifier.padding(start = 12.dp).widthIn(max = 200.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Lattice.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        trailing?.let { Box(Modifier.padding(start = 12.dp)) { it() } }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 4.dp), tint = Lattice.colors.muted)
    }
}

/** What a printer is doing, as a word and a colour. */
data class StatusLook(val label: String, val color: Color)

@Composable
fun PrinterState.look(): StatusLook {
    val c = MaterialTheme.colorScheme
    val l = Lattice.colors
    return when (val conn = connection) {
        is ConnectionState.Failed -> StatusLook(if (conn.authRejected) "Access code rejected" else "Offline", c.error)
        ConnectionState.Connecting -> StatusLook("Connecting…", l.muted)
        ConnectionState.Disconnected -> StatusLook("Offline", l.muted)
        ConnectionState.Connected -> when {
            machine == MachineState.ERROR || exceptions.isNotEmpty() -> StatusLook("Error", c.error)
            machine == MachineState.PAUSED || machine == MachineState.PAUSING -> StatusLook(machine.label(), l.warn)
            machine.isJobActive -> StatusLook(machine.label(), c.primary)
            machine == MachineState.COMPLETED -> StatusLook("Done", l.ok)
            machine == MachineState.STOPPED -> StatusLook("Stopped", l.muted)
            else -> StatusLook(machine.label(), l.ok)
        }
    }
}

/** "● Printing" */
@Composable
fun StatusWord(state: PrinterState, modifier: Modifier = Modifier) {
    val look = state.look()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(look.color, CircleShape))
        Text(look.label, style = MaterialTheme.typography.bodyMedium, color = look.color)
    }
}

/** Progress bar thick enough to read at a glance. */
@Composable
fun ThickProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = Lattice.colors.tileHigh,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Half-width tile: grey title, bold value, and a chevron or a control on the right. */
@Composable
fun ControlTile(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Panel(modifier, onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Lattice.colors.muted, maxLines = 1)
            if (onClick != null && trailing == null && enabled) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Lattice.colors.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Lattice.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}

/** Square thumbnail with a neutral placeholder when there is no image. */
@Composable
fun Thumb(image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.small).background(Lattice.colors.tile),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Outlined.ViewInAr, null, Modifier.size(size * 0.4f), tint = Lattice.colors.muted.copy(alpha = 0.6f))
        }
    }
}

/** A filament spool seen from the side: back flange, the filament in its colour, front flange with the hub. */
@Composable
fun Spool(
    color: Color?,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    description: String = label,
    onClick: (() -> Unit)? = null,
) {
    val flange = if (Lattice.colors.isDark) Color(0xFF4A4F55) else Color(0xFFB9BEC4)
    val back = if (Lattice.colors.isDark) Color(0xFF2E3237) else Color(0xFFD3D7DB)
    val hole = if (Lattice.colors.isDark) Color(0xFF26292D) else Color(0xFF8E949A)
    val fill = color ?: Lattice.colors.tileHigh
    val ring = MaterialTheme.colorScheme.primary
    val labelColor = if (fill.luminance() > 0.45f) Color(0xFF111111) else Color.White
    Box(
        modifier.size(width = 64.dp, height = 88.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val w = size.width
            val h = size.height
            val fw = w * 0.36f
            drawOval(back, topLeft = Offset(0f, 0f), size = Size(fw, h))
            drawRect(fill, topLeft = Offset(fw * 0.5f, h * 0.08f), size = Size(w - fw, h * 0.84f))
            drawOval(fill, topLeft = Offset(fw * 0.5f - fw * 0.28f, h * 0.08f), size = Size(fw * 0.56f, h * 0.84f))
            drawOval(flange, topLeft = Offset(w - fw, 0f), size = Size(fw, h))
            drawOval(hole, topLeft = Offset(w - fw * 0.5f - fw * 0.15f, h * 0.5f - h * 0.08f), size = Size(fw * 0.3f, h * 0.16f))
            if (active) {
                drawRoundRect(ring, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(width = 2.5.dp.toPx()), topLeft = Offset(-2f, -2f), size = Size(w + 4f, h + 4f))
            }
        }
        Text(
            label,
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp),
            color = labelColor,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** A filled circle with a hairline so white and black both show. */
@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color).border(1.dp, Lattice.colors.divider, CircleShape))
}
