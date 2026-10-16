package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.AffyState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.VioletGlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Beautiful Floating Holographic AFFY AI Orb (JARVIS / Gemini inspired).
 *
 * Visual Characteristics:
 * - Pure circular luminous sphere with glass/holographic core (no rectangular borders).
 * - Concentric counter-rotating thin energy rings.
 * - Orbiting energy particles and sonic ticks.
 * - Soft volumetric outer aura glow.
 * - Live-driven by Gemini Live [audioAmplitude] and [AffyState].
 * - Smoothly scales up and pulses when Speaking or Listening, gently breathes in Standby.
 */
@Composable
fun AffyAiCoreOrb(
    state: AffyState,
    amplitude: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ai_orb_anim")

    // Standby breathing scale
    val standbyBreath by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_standby_breath"
    )

    // Forward orbital ring rotation
    val forwardRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    AffyState.EXECUTING -> 1200
                    AffyState.SPEAKING -> 2800
                    AffyState.LISTENING -> 3600
                    AffyState.THINKING -> 1600
                    else -> 7000
                },
                easing = LinearEasing
            )
        ),
        label = "orb_rot_fwd"
    )

    // Reverse orbital ring rotation
    val reverseRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    AffyState.EXECUTING -> 1800
                    AffyState.SPEAKING -> 3400
                    AffyState.LISTENING -> 4500
                    AffyState.THINKING -> 2200
                    else -> 9000
                },
                easing = LinearEasing
            )
        ),
        label = "orb_rot_rev"
    )

    // Dynamic state palette
    val coreColor = when (state) {
        AffyState.IDLE -> CyanCore
        AffyState.CONNECTING -> Color(0xFF00E5FF)
        AffyState.LISTENING -> CyanAccent
        AffyState.THINKING -> AmberThinking
        AffyState.SPEAKING -> NeonViolet
        AffyState.EXECUTING -> EmeraldActive
        AffyState.VERIFYING -> Color(0xFF00E5FF)
        AffyState.ERROR -> CrimsonError
    }

    val glowColor = when (state) {
        AffyState.IDLE -> CyanGlow.copy(alpha = 0.40f)
        AffyState.CONNECTING -> Color(0xFF00E5FF).copy(alpha = 0.55f)
        AffyState.LISTENING -> CyanAccent.copy(alpha = 0.65f)
        AffyState.THINKING -> AmberThinking.copy(alpha = 0.55f)
        AffyState.SPEAKING -> VioletGlow.copy(alpha = 0.75f)
        AffyState.EXECUTING -> EmeraldActive.copy(alpha = 0.60f)
        AffyState.VERIFYING -> Color(0xFF00E5FF).copy(alpha = 0.50f)
        AffyState.ERROR -> CrimsonError.copy(alpha = 0.55f)
    }

    // Dynamic live audio scaling
    val dynamicOrbScale by animateFloatAsState(
        targetValue = when (state) {
            AffyState.SPEAKING -> 1.05f + (amplitude.coerceIn(0f, 1f) * 0.28f)
            AffyState.LISTENING -> 1.02f + (amplitude.coerceIn(0f, 1f) * 0.18f)
            AffyState.EXECUTING -> 1.08f
            AffyState.CONNECTING -> 1.02f
            else -> standbyBreath
        },
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "orb_audio_scale"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(140.dp)
            .scale(dynamicOrbScale)
            .testTag("affy_ai_core_orb")
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(140.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.45f
            val audioAmp = amplitude.coerceIn(0f, 1f)

            // 1. Soft Volumetric Outer Aura Glow
            val glowRadius = baseRadius * (1.9f + (if (state == AffyState.SPEAKING) audioAmp * 0.6f else 0.2f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor,
                        glowColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            // 2. Primary Outer Energy Ring (Thin Sci-Fi Arc Ring 1)
            val ring1Radius = baseRadius * (1.38f + (audioAmp * 0.15f))
            for (i in 0 until 3) {
                val startAngle = (forwardRotation + (i * 120f)) % 360f
                drawArc(
                    color = coreColor.copy(alpha = 0.75f),
                    startAngle = startAngle,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - ring1Radius, center.y - ring1Radius),
                    size = Size(ring1Radius * 2f, ring1Radius * 2f),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 3. Counter-Rotating Inner Energy Ring (Arc Ring 2)
            val ring2Radius = baseRadius * (1.18f + (audioAmp * 0.10f))
            for (i in 0 until 4) {
                val startAngle = (reverseRotation + (i * 90f)) % 360f
                drawArc(
                    color = Color.White.copy(alpha = 0.65f),
                    startAngle = startAngle,
                    sweepAngle = 45f,
                    useCenter = false,
                    topLeft = Offset(center.x - ring2Radius, center.y - ring2Radius),
                    size = Size(ring2Radius * 2f, ring2Radius * 2f),
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 4. Orbiting Nano Energy Nodes
            val nodeCount = if (state == AffyState.SPEAKING) 6 else 4
            for (i in 0 until nodeCount) {
                val angleDeg = (forwardRotation * 1.2f) + (i * (360f / nodeCount))
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val orbitDist = baseRadius * 1.30f
                val nodePos = Offset(
                    center.x + (orbitDist * cos(angleRad)).toFloat(),
                    center.y + (orbitDist * sin(angleRad)).toFloat()
                )
                drawCircle(
                    color = Color.White,
                    radius = (2.2.dp.toPx() + (if (state == AffyState.SPEAKING) audioAmp * 1.5.dp.toPx() else 0f)),
                    center = nodePos
                )
                drawCircle(
                    color = coreColor.copy(alpha = 0.5f),
                    radius = (4.5.dp.toPx() + (if (state == AffyState.SPEAKING) audioAmp * 2.5.dp.toPx() else 0f)),
                    center = nodePos
                )
            }

            // 5. Central Holographic Core Glass / Energy Reactor
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.90f),
                        coreColor.copy(alpha = 0.85f),
                        coreColor.copy(alpha = 0.35f),
                        Color(0xFF030712)
                    ),
                    center = center,
                    radius = baseRadius
                ),
                radius = baseRadius,
                center = center
            )

            // 6. Inner High-Precision Core Rim
            drawCircle(
                color = Color.White.copy(alpha = 0.80f),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 1.8.dp.toPx())
            )

            // 7. Subtle Inner Quantum Node
            drawCircle(
                color = Color.White.copy(alpha = 0.95f),
                radius = baseRadius * (0.28f + (audioAmp * 0.15f)),
                center = center
            )
        }
    }
}
