package app.parda.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parda.ui.theme.Frost

/** The frosted ground with two soft colour fields behind the glass. */
@Composable
fun FrostBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Frost.Ground)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Frost.Accent.copy(alpha = 0.75f), Color.Transparent),
                    center = Offset(size.width * 0.1f, size.height * 0.05f), radius = size.width * 0.7f,
                ),
                radius = size.width * 0.7f, center = Offset(size.width * 0.1f, size.height * 0.05f),
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(Frost.Peach.copy(alpha = 0.85f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.62f), radius = size.width * 0.6f,
                ),
                radius = size.width * 0.6f, center = Offset(size.width * 0.95f, size.height * 0.62f),
            )
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    padding: Dp = 20.dp,
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Frost.Glass)
            .border(BorderStroke(1.dp, Frost.GlassEdge), shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun NightCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Frost.Night).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Frost.Night, contentColor = Color.White),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth().height(48.dp)) {
        Text(text, color = Frost.Ink, style = MaterialTheme.typography.titleMedium)
    }
}

/** A rounded status or action pill. Dark when [strong]. */
@Composable
fun Pill(text: String, strong: Boolean = false, onClick: (() -> Unit)? = null, bg: Color? = null, fg: Color? = null) {
    val base = Modifier
        .clip(CircleShape)
        .background(bg ?: if (strong) Frost.Night else Frost.GlassStrong)
    Box(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg ?: if (strong) Color.White else Frost.Ink)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun SectionLabel(text: String, color: Color = Frost.Ink2) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Frost.Ink2)
    }
}

/** Stroke icons drawn from the same 24-unit paths as the design canvas. */
object Icons {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    val Home = icon("home") { moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); verticalLineTo(20f); horizontalLineTo(15f); verticalLineTo(14f); horizontalLineTo(9f); verticalLineTo(20f); horizontalLineTo(4f); close() }
    val Shield = icon("shield") { moveTo(12f, 3f); lineTo(19f, 6f); verticalLineTo(12f); curveTo(19f, 16.5f, 16f, 19.5f, 12f, 21f); curveTo(8f, 19.5f, 5f, 16.5f, 5f, 12f); verticalLineTo(6f); close(); moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f) }
    val Lock = icon("lock") { moveTo(7f, 11f); horizontalLineTo(17f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineTo(18f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(7f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); verticalLineTo(13f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close(); moveTo(8f, 11f); verticalLineTo(8f); arcToRelative(4f, 4f, 0f, false, true, 8f, 0f); verticalLineTo(11f) }
    val Ledger = icon("ledger") { moveTo(4f, 7f); horizontalLineTo(20f); moveTo(4f, 12f); horizontalLineTo(20f); moveTo(4f, 17f); horizontalLineTo(14f) }
    val Chevron = icon("chevron") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val Check = icon("check") { moveTo(5f, 12f); lineTo(9f, 16f); lineTo(19f, 6f) }
}

@Composable
fun StrokeIcon(icon: ImageVector, tint: Color = Frost.Ink, size: Dp = 22.dp, contentDescription: String? = null) {
    androidx.compose.material3.Icon(icon, contentDescription, Modifier.size(size), tint = tint)
}
