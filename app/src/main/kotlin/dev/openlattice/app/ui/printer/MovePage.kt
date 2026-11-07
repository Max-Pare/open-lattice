package dev.openlattice.app.ui.printer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openlattice.app.ui.components.Panel
import dev.openlattice.app.ui.theme.Lattice
import dev.openlattice.core.ConnectionState
import dev.openlattice.core.PrinterState
import kotlin.math.abs
import kotlin.math.hypot

/** A tap on the jog pad: an axis move, or home. */
sealed interface JogTarget {
    data class Move(val axis: Char, val mm: Double) : JogTarget
    data object Home : JogTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovePage(
    state: PrinterState,
    camera: @Composable () -> Unit,
    onJog: (axis: Char, mm: Double) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val enabled = state.connection == ConnectionState.Connected && !state.machine.isJobActive
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Movement") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) { camera() }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!enabled) {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(
                            if (state.machine.isJobActive) "Moving is disabled while a print is running." else "The printer is not connected.",
                            color = Lattice.colors.muted,
                        )
                    }
                }
                state.position?.let {
                    Text(
                        "X %.1f   Y %.1f   Z %.1f".format(it.x, it.y, it.z),
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        color = Lattice.colors.muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Print head", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        JogPad(
                            enabled = enabled,
                            size = 252.dp,
                            onTarget = { t ->
                                when (t) {
                                    is JogTarget.Move -> onJog(t.axis, t.mm)
                                    JogTarget.Home -> onHome()
                                }
                            },
                        )
                    }
                    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Bed", color = Lattice.colors.muted, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        BedColumn(enabled, onJog)
                    }
                }
                Text(
                    "Tap the inner ring to move 1 mm, the outer ring for 10 mm. The centre homes the printer; the CC2 always homes X, Y and Z together.",
                    color = Lattice.colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Round jog pad: four quadrants (+Y up, +X right), inner ring 1 mm, outer ring 10 mm, home in
 * the middle. Screen readers get one action per move.
 */
@Composable
fun JogPad(enabled: Boolean, size: Dp, onTarget: (JogTarget) -> Unit) {
    val outer = Lattice.colors.tile
    val inner = Lattice.colors.tileHigh
    val pressedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val line = MaterialTheme.colorScheme.background
    val text = if (enabled) MaterialTheme.colorScheme.onSurface else Lattice.colors.muted
    val hint = Lattice.colors.muted
    val homeTint = if (enabled) MaterialTheme.colorScheme.primary else Lattice.colors.muted
    val measurer = rememberTextMeasurer()
    val homeIcon = rememberVectorPainter(Icons.Default.Home)
    var pressed by remember { mutableStateOf<JogTarget?>(null) }
    val currentOnTarget by rememberUpdatedState(onTarget)

    val actions = remember(enabled) {
        if (!enabled) emptyList() else buildList {
            for ((axis, name) in listOf('y' to "Y", 'x' to "X")) for (step in listOf(1.0, 10.0)) for (sign in listOf(1, -1)) {
                val label = "Move ${if (sign > 0) "+" else "-"}$name ${step.toInt()} mm"
                add(CustomAccessibilityAction(label) { currentOnTarget(JogTarget.Move(axis, sign * step)); true })
            }
            add(CustomAccessibilityAction("Home all axes") { currentOnTarget(JogTarget.Home); true })
        }
    }

    Canvas(
        Modifier.size(size)
            .semantics {
                contentDescription = "Print head jog pad"
                customActions = actions
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val width = this.size.width.toFloat()
                detectTapGestures(
                    onPress = { at ->
                        pressed = hit(at, width)
                        tryAwaitRelease()
                        pressed = null
                    },
                    onTap = { at -> hit(at, width)?.let(currentOnTarget) },
                )
            },
    ) {
        val r = this.size.minDimension / 2
        val c = center
        drawCircle(outer, r, c)
        drawCircle(inner, r * INNER, c)
        pressed?.let { highlight(it, r, c, pressedColor) }
        // Quadrant dividers along the diagonals.
        val d = r * 0.7071f
        drawLine(line, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = 4.dp.toPx())
        drawLine(line, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), strokeWidth = 4.dp.toPx())
        drawCircle(line, r * INNER, c, style = Stroke(2.dp.toPx()))
        drawCircle(line, r * HOME, c)
        drawCircle(inner, r * HOME - 3.dp.toPx(), c)
        with(homeIcon) {
            val s = r * HOME * 0.9f
            translate(c.x - s / 2, c.y - s / 2) { draw(androidx.compose.ui.geometry.Size(s, s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(homeTint)) }
        }
        val mid = (1 + INNER) / 2
        label(measurer, "Y", Offset(c.x, c.y - r * mid), text, 18f)
        label(measurer, "-Y", Offset(c.x, c.y + r * mid), text, 18f)
        label(measurer, "X", Offset(c.x + r * mid, c.y), text, 18f)
        label(measurer, "-X", Offset(c.x - r * mid, c.y), text, 18f)
        val inMid = (INNER + HOME) / 2
        label(measurer, "1", Offset(c.x, c.y - r * inMid), hint, 13f)
        label(measurer, "1", Offset(c.x, c.y + r * inMid), hint, 13f)
        label(measurer, "1", Offset(c.x + r * inMid, c.y), hint, 13f)
        label(measurer, "1", Offset(c.x - r * inMid, c.y), hint, 13f)
        label(measurer, "10 mm", Offset(c.x + r * 0.62f, c.y - r * 0.62f), hint, 12f)
    }
}

private const val INNER = 0.56f
private const val HOME = 0.2f

/** What a tap at [at] on a pad [width] across means. */
internal fun hit(at: Offset, width: Float): JogTarget? {
    val r = width / 2
    val dx = at.x - r
    val dy = at.y - r
    val dist = hypot(dx, dy)
    return when {
        dist > r -> null
        dist <= r * HOME -> JogTarget.Home
        else -> {
            val step = if (dist <= r * INNER) 1.0 else 10.0
            if (abs(dy) >= abs(dx)) JogTarget.Move('y', if (dy < 0) step else -step)
            else JogTarget.Move('x', if (dx > 0) step else -step)
        }
    }
}

private fun DrawScope.highlight(t: JogTarget, r: Float, c: Offset, color: Color) {
    when (t) {
        JogTarget.Home -> drawCircle(color, r * HOME, c)
        is JogTarget.Move -> {
            val start = when {
                t.axis == 'y' && t.mm > 0 -> 225f
                t.axis == 'x' && t.mm > 0 -> 315f
                t.axis == 'y' -> 45f
                else -> 135f
            }
            val outerRing = abs(t.mm) >= 10
            val radius = if (outerRing) r else r * INNER
            val width = if (outerRing) r * (1 - INNER) else r * (INNER - HOME)
            val mid = radius - width / 2
            drawArc(
                color, start, 90f, useCenter = false,
                topLeft = Offset(c.x - mid, c.y - mid),
                size = androidx.compose.ui.geometry.Size(mid * 2, mid * 2),
                style = Stroke(width),
            )
        }
    }
}

private fun DrawScope.label(measurer: TextMeasurer, s: String, at: Offset, color: Color, sizeSp: Float) {
    val layout = measurer.measure(s, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold))
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

/** Bed up/down in 10 and 1 mm steps. Bed up narrows the nozzle–bed gap, so Z goes down. */
@Composable
private fun BedColumn(enabled: Boolean, onJog: (Char, Double) -> Unit) {
    Surface(color = Lattice.colors.tile, shape = MaterialTheme.shapes.medium) {
        Column {
            BedButton(Icons.Default.ArrowUpward, "10", "Bed up 10 mm", enabled) { onJog('z', -10.0) }
            HorizontalDivider(color = MaterialTheme.colorScheme.background)
            BedButton(Icons.Default.ArrowUpward, "1", "Bed up 1 mm", enabled) { onJog('z', -1.0) }
            HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 6.dp)
            BedButton(Icons.Default.ArrowDownward, "1", "Bed down 1 mm", enabled) { onJog('z', 1.0) }
            HorizontalDivider(color = MaterialTheme.colorScheme.background)
            BedButton(Icons.Default.ArrowDownward, "10", "Bed down 10 mm", enabled) { onJog('z', 10.0) }
        }
    }
}

@Composable
private fun BedButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = Modifier.width(88.dp).height(56.dp).minimumInteractiveComponentSize().semantics { contentDescription = description },
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurface else Lattice.colors.muted
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Text(text, Modifier.padding(start = 4.dp), color = tint, style = MaterialTheme.typography.titleMedium)
        }
    }
}
