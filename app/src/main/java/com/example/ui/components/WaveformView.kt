package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun InteractiveWaveform(
    peaks: List<Float>,
    progressFraction: Float,
    activeColor: Color,
    inactiveColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val safePeaks = if (peaks.isEmpty()) List(80) { 0.25f } else peaks

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(safePeaks) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek(frac)
                }
            }
            .pointerInput(safePeaks) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeek(frac)
                }
            }
    ) {
        val barCount = safePeaks.size
        val totalWidth = size.width
        val totalHeight = size.height
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.62f).coerceIn(2f, 10f)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val clampedProgress = progressFraction.coerceIn(0f, 1f)
        val activeCutoffX = totalWidth * clampedProgress

        for (i in 0 until barCount) {
            val amp = safePeaks[i].coerceIn(0.08f, 1f)
            val barHeight = (totalHeight * 0.86f * amp).coerceAtLeast(6f)
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            val y = (totalHeight - barHeight) / 2f
            val color = if (x + barWidth / 2f <= activeCutoffX) activeColor else inactiveColor

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }

        // Playhead indicator line
        if (clampedProgress > 0f && clampedProgress < 1f) {
            drawLine(
                color = cursorColor.copy(alpha = 0.85f),
                start = Offset(activeCutoffX, 2f),
                end = Offset(activeCutoffX, totalHeight - 2f),
                strokeWidth = 3f
            )
        }
    }
}
