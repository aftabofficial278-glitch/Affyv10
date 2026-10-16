package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AffyState
import com.example.ui.theme.CosmicGoldDeep
import com.example.ui.theme.CosmicGoldLight
import com.example.ui.theme.CosmicGoldPrimary
import com.example.ui.theme.CosmicGoldWarm
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.NeonViolet
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private data class StarParticle(
    val xNorm: Float,
    val yNorm: Float,
    val sizeDp: Float,
    val baseAlpha: Float,
    val phaseOffset: Float
)

/**
 * Authentic AFFY Holographic Chair UI:
 * Displays Affy as a futuristic holographic AI presence seated on a cyber throne.
 * Features:
 * - Dynamic holographic aura & atmospheric volumetric glow responding to [AffyState] and [amplitude].
 * - Holographic pedestal projector with spinning cyber rings and upward light beams.
 * - Gentle floating bobbing & breathing animation.
 * - Horizontal holographic laser scanlines effect.
 * - Audio-reactive holographic sonic resonance ripples when listening or speaking.
 * - One-tap interactive activation via [onClick].
 */
@Composable
fun AffyHologramPresence(
    state: AffyState,
    amplitude: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hologram_chair_presence")
    val liveAmp = amplitude.coerceIn(0f, 1f)
    val interactionSource = remember { MutableInteractionSource() }

    // 1. Hologram floating bobbing motion (gentle ±8dp vertical translation)
    val floatTranslationY by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hologram_float"
    )

    // 2. Holographic breathing scale (subtle 1.0f to 1.025f expansion)
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hologram_breathing"
    )

    // 3. Scanline phase moving vertically across the hologram
    val scanlinePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanline_phase"
    )

    // 4. Pedestal cyber ring rotation
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation"
    )

    // 5. Hologram shimmer & flicker (authentic laser holography micro-variations)
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hologram_shimmer"
    )

    // State-driven holographic colors
    val primaryAuraColor by animateColorAsState(
        targetValue = when (state) {
            AffyState.IDLE -> CosmicGoldPrimary
            AffyState.CONNECTING -> CyanCore
            AffyState.LISTENING -> CyanAccent
            AffyState.THINKING -> NeonViolet
            AffyState.SPEAKING -> CosmicGoldLight
            AffyState.EXECUTING -> EmeraldActive
            AffyState.VERIFYING -> CyanCore
            AffyState.ERROR -> CrimsonError
        },
        animationSpec = tween(500),
        label = "primary_aura_color"
    )

    val secondaryAuraColor by animateColorAsState(
        targetValue = when (state) {
            AffyState.IDLE -> CyanCore
            AffyState.CONNECTING -> CosmicGoldPrimary
            AffyState.LISTENING -> Color(0xFF00E5FF)
            AffyState.THINKING -> Color(0xFFC084FC)
            AffyState.SPEAKING -> CosmicGoldWarm
            AffyState.EXECUTING -> Color(0xFF69F0AE)
            AffyState.VERIFYING -> CyanAccent
            AffyState.ERROR -> Color(0xFFFF8A80)
        },
        animationSpec = tween(500),
        label = "secondary_aura_color"
    )

    // Ambient distant star particles
    val stars = remember {
        List(28) { idx ->
            StarParticle(
                xNorm = ((idx * 0.618034f + 0.05f) % 0.94f) + 0.03f,
                yNorm = ((idx * 0.381966f + (idx / 28f)) % 0.92f) + 0.04f,
                sizeDp = 0.75f + (idx % 3) * 0.45f,
                baseAlpha = 0.08f + (idx % 4) * 0.05f,
                phaseOffset = idx * 0.65f
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("affy_hologram_presence")
            .testTag("affy_ai_core_orb")
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // =========================================================================
        // LAYER 1: DEEP COSMIC SPACE BACKDROP WITH AMBIENT PARTICLES
        // =========================================================================
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Deep obsidian-space base
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF020306),
                        Color(0xFF04060C),
                        Color(0xFF070A14),
                        Color(0xFF04060B),
                        Color(0xFF010204)
                    )
                ),
                size = size
            )

            // Distant space stars
            stars.forEach { star ->
                val sx = w * star.xNorm
                val sy = h * star.yNorm
                val twinkle = 0.65f + 0.35f * sin(ringRotation * 0.05f + star.phaseOffset)
                val alpha = (star.baseAlpha * twinkle).coerceIn(0.04f, 0.35f)
                drawCircle(
                    color = CosmicGoldLight.copy(alpha = alpha),
                    radius = star.sizeDp.dp.toPx(),
                    center = Offset(sx, sy)
                )
            }
        }

        // =========================================================================
        // LAYER 2: VOLUMETRIC AURA & AUDIO RESONANCE WAVES BEHIND CHAIR
        // =========================================================================
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = min(w, h)
            val center = Offset(w * 0.5f, h * 0.44f)

            // Large atmospheric holographic aura centered behind the chair
            val auraRadius = minDim * (0.58f + liveAmp * 0.16f)
            val auraIntensity = (0.22f + liveAmp * 0.24f).coerceAtMost(0.60f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryAuraColor.copy(alpha = auraIntensity),
                        secondaryAuraColor.copy(alpha = auraIntensity * 0.50f),
                        CosmicGoldDeep.copy(alpha = auraIntensity * 0.18f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = auraRadius
                ),
                radius = auraRadius,
                center = center
            )

            // Audio-reactive expanding sonic resonance rings when listening or speaking
            if (state == AffyState.LISTENING || state == AffyState.SPEAKING || liveAmp > 0.04f) {
                val numRings = 3
                for (i in 0 until numRings) {
                    val ringProgress = ((ringRotation * 0.02f + i.toFloat() / numRings) % 1f)
                    val r = minDim * (0.35f + ringProgress * 0.32f + liveAmp * 0.12f)
                    val ringAlpha = ((1f - ringProgress) * (0.25f + liveAmp * 0.35f)).coerceIn(0f, 0.65f)
                    drawCircle(
                        color = primaryAuraColor.copy(alpha = ringAlpha),
                        radius = r,
                        center = center,
                        style = Stroke(width = (1.5f + (1f - ringProgress) * 2f).dp.toPx())
                    )
                }
            }

            // =====================================================================
            // HOLOGRAPHIC PEDESTAL PROJECTOR BASE (Floor beneath chair)
            // =====================================================================
            val pedestalCenterY = h * 0.74f
            val pedestalRadiusX = minDim * 0.44f
            val pedestalRadiusY = minDim * 0.12f

            // Base platform outer glowing ellipse
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryAuraColor.copy(alpha = 0.35f + liveAmp * 0.20f),
                        secondaryAuraColor.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, pedestalCenterY),
                    radius = pedestalRadiusX
                ),
                topLeft = Offset(w * 0.5f - pedestalRadiusX, pedestalCenterY - pedestalRadiusY),
                size = Size(pedestalRadiusX * 2f, pedestalRadiusY * 2f)
            )

            // Cybernetic emitter rings with dash segments
            drawOval(
                color = primaryAuraColor.copy(alpha = 0.55f),
                topLeft = Offset(w * 0.5f - pedestalRadiusX * 0.85f, pedestalCenterY - pedestalRadiusY * 0.85f),
                size = Size(pedestalRadiusX * 1.7f, pedestalRadiusY * 1.7f),
                style = Stroke(width = 1.5.dp.toPx())
            )

            drawOval(
                color = secondaryAuraColor.copy(alpha = 0.40f),
                topLeft = Offset(w * 0.5f - pedestalRadiusX * 0.60f, pedestalCenterY - pedestalRadiusY * 0.60f),
                size = Size(pedestalRadiusX * 1.2f, pedestalRadiusY * 1.2f),
                style = Stroke(width = 1.2.dp.toPx())
            )

            // Upward projector light rays from the base
            for (ray in 0 until 5) {
                val rayAngle = (ray * 72f + ringRotation * 0.5f) * (Math.PI / 180f)
                val rx = (w * 0.5f + cos(rayAngle) * pedestalRadiusX * 0.75f).toFloat()
                val ry = (pedestalCenterY + sin(rayAngle) * pedestalRadiusY * 0.75f).toFloat()
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryAuraColor.copy(alpha = 0.30f + liveAmp * 0.25f),
                            secondaryAuraColor.copy(alpha = 0.55f)
                        ),
                        startY = h * 0.45f,
                        endY = ry
                    ),
                    start = Offset(rx, h * 0.45f),
                    end = Offset(rx, ry),
                    strokeWidth = 1.2.dp.toPx()
                )
            }
        }

        // =========================================================================
        // LAYER 3: THE ICONIC HOLOGRAM AVATAR SITTING ON THE CHAIR
        // =========================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.78f)
                .offset { IntOffset(0, floatTranslationY.roundToInt()) }
                .graphicsLayer {
                    scaleX = breathingScale + (liveAmp * 0.04f)
                    scaleY = breathingScale + (liveAmp * 0.04f)
                    alpha = shimmerAlpha
                },
            contentAlignment = Alignment.Center
        ) {
            // The Hologram Image
            Image(
                painter = painterResource(id = R.drawable.affy_holographic_chair),
                contentDescription = "AFFY Hologram Chair Avatar",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Seamless Vignette Fade Overlays to blend image edges into the deep space canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Top fade
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF04060C),
                            Color(0xCC04060C),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h * 0.18f
                    ),
                    size = Size(w, h * 0.18f)
                )

                // Bottom fade into pedestal
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xAA04060C),
                            Color(0xFF04060C)
                        ),
                        startY = h * 0.80f,
                        endY = h
                    ),
                    topLeft = Offset(0f, h * 0.80f),
                    size = Size(w, h * 0.20f)
                )

                // Horizontal Hologram Scanlines Effect
                val scanlineSpacing = 6.dp.toPx()
                val scanlineOffset = (scanlinePhase * scanlineSpacing * 8f) % scanlineSpacing
                var y = scanlineOffset
                while (y < h) {
                    drawLine(
                        color = primaryAuraColor.copy(alpha = 0.07f + liveAmp * 0.05f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.0.dp.toPx()
                    )
                    y += scanlineSpacing
                }

                // Moving Holographic Laser Beam Scanline
                val beamY = h * scanlinePhase
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryAuraColor.copy(alpha = 0.28f + liveAmp * 0.20f),
                            secondaryAuraColor.copy(alpha = 0.40f + liveAmp * 0.20f),
                            primaryAuraColor.copy(alpha = 0.28f + liveAmp * 0.20f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, beamY),
                    end = Offset(w, beamY),
                    strokeWidth = 2.5.dp.toPx()
                )
            }
        }
    }
}

