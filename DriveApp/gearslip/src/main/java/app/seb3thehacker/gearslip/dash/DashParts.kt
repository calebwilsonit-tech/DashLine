package app.seb3thehacker.gearslip.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A touch target that acts the instant a finger lands, not when it lifts.
 *
 * Standard Compose clickables wait for the finger to come up (and, inside anything scrollable,
 * hold the pressed look back ~150 ms). On the truck every extra wait stacks on top of the radio's
 * own touch and video delay, so Dashline buttons fire on touch-DOWN and show the pressed colour
 * in the same frame. The pressed colour is read only in the draw phase: pressing a button
 * redraws it without recomposing anything.
 *
 * Don't use this inside scrolling lists (a scroll would trigger it); use [onTapUp] there.
 */
@Composable
fun Tap(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    color: Color? = null,
    pressedColor: Color? = null,
    outline: Boolean = true,
    onTapUp: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalDashTheme.current
    val s = shape ?: RoundedCornerShape(t.radius)
    val base = color ?: t.panel
    val hi = pressedColor ?: t.panelHi
    val pressed = remember { mutableStateOf(false) }
    val action by rememberUpdatedState(onTap)
    val withOutline = if (outline && t.outline != Color.Transparent) Modifier.border(1.dp, t.outline, s) else Modifier
    Box(
        modifier
            .clip(s)
            .then(withOutline)
            .drawBehind { drawRect(if (pressed.value) hi else base) }
            .pointerInput(onTapUp) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed.value = true
                    if (!onTapUp) action()
                    val up = waitForUpOrCancellation()
                    pressed.value = false
                    if (onTapUp && up != null) action()
                }
            },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** A card that isn't a button. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = LocalDashTheme.current
    val s = RoundedCornerShape(t.radius)
    val withOutline = if (t.outline != Color.Transparent) Modifier.border(1.dp, t.outline, s) else Modifier
    Box(
        modifier.clip(s).then(withOutline).drawBehind { drawRect(t.panel) },
        content = content,
    )
}

@Composable
fun Label(
    text: String,
    size: TextUnit = 16.sp,
    color: Color? = null,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    val t = LocalDashTheme.current
    Text(
        text,
        modifier = modifier,
        color = color ?: t.text,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = size * 1.15f,
    )
}

/**
 * Icons drawn as plain shapes: no image loading, tinted by the theme, sharp at any size, and
 * free of the large extended-icons library.
 */
enum class Glyph { Play, Pause, Next, Prev, Home, Map, Music, Phone, Message, Garage, Settings, Back }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.Play -> drawPath(Path().apply {
                moveTo(w * 0.28f, h * 0.18f); lineTo(w * 0.82f, h * 0.5f); lineTo(w * 0.28f, h * 0.82f); close()
            }, color)
            Glyph.Pause -> {
                drawRect(color, Offset(w * 0.24f, h * 0.18f), Size(w * 0.17f, h * 0.64f))
                drawRect(color, Offset(w * 0.59f, h * 0.18f), Size(w * 0.17f, h * 0.64f))
            }
            Glyph.Next -> {
                drawPath(Path().apply {
                    moveTo(w * 0.2f, h * 0.2f); lineTo(w * 0.66f, h * 0.5f); lineTo(w * 0.2f, h * 0.8f); close()
                }, color)
                drawRect(color, Offset(w * 0.68f, h * 0.2f), Size(w * 0.12f, h * 0.6f))
            }
            Glyph.Prev -> {
                drawPath(Path().apply {
                    moveTo(w * 0.8f, h * 0.2f); lineTo(w * 0.34f, h * 0.5f); lineTo(w * 0.8f, h * 0.8f); close()
                }, color)
                drawRect(color, Offset(w * 0.2f, h * 0.2f), Size(w * 0.12f, h * 0.6f))
            }
            Glyph.Home -> drawPath(Path().apply {
                moveTo(w * 0.5f, h * 0.14f); lineTo(w * 0.88f, h * 0.48f); lineTo(w * 0.76f, h * 0.48f)
                lineTo(w * 0.76f, h * 0.86f); lineTo(w * 0.24f, h * 0.86f); lineTo(w * 0.24f, h * 0.48f)
                lineTo(w * 0.12f, h * 0.48f); close()
            }, color)
            Glyph.Map -> {
                // A location pin.
                drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.5f, h * 0.38f))
                drawPath(Path().apply {
                    moveTo(w * 0.27f, h * 0.48f); lineTo(w * 0.5f, h * 0.92f); lineTo(w * 0.73f, h * 0.48f); close()
                }, color)
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.38f))
            }
            Glyph.Music -> {
                drawLine(color, Offset(w * 0.62f, h * 0.18f), Offset(w * 0.62f, h * 0.68f), stroke.width)
                drawLine(color, Offset(w * 0.62f, h * 0.18f), Offset(w * 0.84f, h * 0.28f), stroke.width)
                drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.46f, h * 0.7f))
            }
            Glyph.Phone -> drawPath(Path().apply {
                moveTo(w * 0.22f, h * 0.14f); lineTo(w * 0.4f, h * 0.14f); lineTo(w * 0.46f, h * 0.34f)
                lineTo(w * 0.36f, h * 0.44f)
                quadraticTo(w * 0.46f, h * 0.6f, w * 0.58f, h * 0.66f)
                lineTo(w * 0.68f, h * 0.56f); lineTo(w * 0.86f, h * 0.62f); lineTo(w * 0.86f, h * 0.82f)
                quadraticTo(w * 0.4f, h * 0.84f, w * 0.18f, h * 0.36f); close()
            }, color)
            Glyph.Message -> {
                drawRoundRect(color, Offset(w * 0.12f, h * 0.18f), Size(w * 0.76f, h * 0.52f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
                drawPath(Path().apply {
                    moveTo(w * 0.3f, h * 0.66f); lineTo(w * 0.3f, h * 0.88f); lineTo(w * 0.52f, h * 0.66f); close()
                }, color)
            }
            Glyph.Garage -> {
                drawPath(Path().apply {
                    moveTo(w * 0.08f, h * 0.42f); lineTo(w * 0.5f, h * 0.12f); lineTo(w * 0.92f, h * 0.42f)
                }, color, style = stroke)
                for (i in 0 until 4) {
                    val y = h * (0.46f + i * 0.12f)
                    drawRect(color, Offset(w * 0.22f, y), Size(w * 0.56f, h * 0.08f))
                }
            }
            Glyph.Settings -> {
                drawCircle(color, radius = w * 0.3f, style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Offset(w / 2, h / 2)
                    val inner = Offset(c.x + (w * 0.3f * Math.cos(a)).toFloat(), c.y + (h * 0.3f * Math.sin(a)).toFloat())
                    val outer = Offset(c.x + (w * 0.44f * Math.cos(a)).toFloat(), c.y + (h * 0.44f * Math.sin(a)).toFloat())
                    drawLine(color, inner, outer, stroke.width * 1.4f, StrokeCap.Round)
                }
                drawCircle(color, radius = w * 0.1f)
            }
            Glyph.Back -> drawPath(Path().apply {
                moveTo(w * 0.62f, h * 0.18f); lineTo(w * 0.3f, h * 0.5f); lineTo(w * 0.62f, h * 0.82f)
            }, color, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round))
        }
    }
}
