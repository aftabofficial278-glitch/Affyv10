package com.example.agent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.agent.ui.theme.ElectricBlue
import com.example.agent.ui.theme.NeonCyan
import com.example.agent.ui.theme.NeonPurple

@Composable
fun VoiceOrb(
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    isActive: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (isActive) 1.25f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 800 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbScale"
    )

    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingRotation"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
            val baseRadius = (size.toPx() / 2f) * 0.7f

            // Outer subtle glow ring
            drawCircle(
                color = NeonCyan.copy(alpha = 0.15f),
                radius = baseRadius * pulseScale * 1.25f,
                center = center
            )

            // Mid pulsating ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonCyan.copy(alpha = 0.4f), Color.Transparent),
                    center = center,
                    radius = baseRadius * pulseScale * 1.1f
                ),
                radius = baseRadius * pulseScale * 1.1f,
                center = center
            )

            // Core Gradient Orb
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(NeonCyan, ElectricBlue, NeonPurple),
                    start = Offset(center.x - baseRadius, center.y - baseRadius),
                    end = Offset(center.x + baseRadius, center.y + baseRadius)
                ),
                radius = baseRadius * pulseScale,
                center = center
            )

            // Outer rotating stroke
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(NeonCyan, NeonPurple, Color.Transparent, NeonCyan)
                ),
                radius = baseRadius * pulseScale * 1.08f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
