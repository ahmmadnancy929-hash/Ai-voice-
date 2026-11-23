package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalStudioPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ProcessingOrbVisualizer(
    progressPercent: Int,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioPalette.current
    val animatedSweep by animateFloatAsState(
        targetValue = (progressPercent.coerceIn(0, 100) / 100f) * 360f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "progress_sweep"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spectrum_phase"
    )

    Box(
        modifier = modifier.size(224.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 12.dp.toPx()
            val inset = strokePx / 2f + 10.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            // Outer subtle halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        studio.instrumentalAccent.copy(alpha = 0.14f),
                        studio.vocalAccent.copy(alpha = 0.06f),
                        studio.borderSubtle.copy(alpha = 0f)
                    )
                ),
                radius = size.minDimension * 0.48f
            )

            // Background track ring
            drawArc(
                color = studio.borderSubtle.copy(alpha = 0.55f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Active dual-tone gradient progress arc
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        studio.instrumentalAccent,
                        studio.vocalAccent,
                        studio.successAccent,
                        studio.instrumentalAccent
                    )
                ),
                startAngle = -90f,
                sweepAngle = animatedSweep.coerceAtLeast(6f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Animated inner spectrum equalizer ring
            val barCount = 24
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val baseRadius = size.minDimension * 0.27f

            for (i in 0 until barCount) {
                val angle = (i.toFloat() / barCount) * (2f * PI.toFloat())
                val wave = 0.5f + 0.5f * sin(phase + i * 0.55f)
                val barLen = 6.dp.toPx() + wave * 14.dp.toPx()
                val startR = baseRadius
                val endR = baseRadius + barLen
                val start = Offset(centerX + cos(angle) * startR, centerY + sin(angle) * startR)
                val end = Offset(centerX + cos(angle) * endR, centerY + sin(angle) * endR)
                val barColor = if (i % 2 == 0) {
                    studio.instrumentalAccent.copy(alpha = 0.72f)
                } else {
                    studio.vocalAccent.copy(alpha = 0.72f)
                }
                drawLine(
                    color = barColor,
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$progressPercent%",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
