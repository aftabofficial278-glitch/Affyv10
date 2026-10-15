package com.example.ui.components

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AffyState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.NeonViolet
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen Living Holographic AFFY Presence.
 *
 * Renders the existing character seated in the command chair seamlessly integrated into the HUD:
 * - Completely borderless, frameless, and cardless.
 * - Deep gradient edge blends (top, bottom, sides) fading naturally into the dark HUD canvas.
 * - Subtle vertical breathing float (micro-parallax).
 * - Living holographic scanlines, ambient cyan/violet state lighting, and rising cyber dust particles.
 */
@Composable
fun AffyHologramPresence(
    state: AffyState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hologram_vfx")

    // Slow, subtle breathing float (±4dp)
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "holo_float"
    )

    // Subtle scale breathing (0.99x to 1.015x)
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "holo_scale"
    )

    // Holographic scanline sweep
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_sweep"
    )

    // Particle drift progression (0 to 1)
    val particleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particle_flow"
    )

    // Subtle holographic flicker alpha (0.92 to 1.0)
    val flickerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.93f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flicker_alpha"
    )

    // Dynamic state tint and glow
    val auraColor = when (state) {
        AffyState.IDLE -> CyanCore
        AffyState.CONNECTING -> Color(0xFF00E5FF)
        AffyState.LISTENING -> CyanAccent
        AffyState.THINKING -> AmberThinking
        AffyState.SPEAKING -> NeonViolet
        AffyState.EXECUTING -> EmeraldActive
        AffyState.VERIFYING -> Color(0xFF00E5FF)
        AffyState.ERROR -> CrimsonError
    }

    // Static seeded particle data
    val particles = remember {
        List(22) {
            ParticleData(
                xFraction = (it * 0.045f + 0.02f) % 1.0f,
                speedMultiplier = 0.7f + (it % 5) * 0.15f,
                sizeDp = 1.2f + (it % 3) * 0.8f,
                baseAlpha = 0.25f + (it % 4) * 0.15f,
                horizontalWobblePhase = it * 0.6f
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 1. Central Holographic Character Asset with Living Micro-Movement
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = floatOffset.dp)
                .scale(breatheScale)
                .alpha(flickerAlpha),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.affy_holographic_chair),
                contentDescription = "Holographic AFFY Presence",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 2. Seamless HUD Blending Gradients (Eliminates all box/card edges completely)
            // Top Edge Gradient Fade
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF050811),
                                Color(0xEE050811),
                                Color(0x99050811),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(top = 110.dp)
            )

            // Bottom Edge Gradient Fade
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x88050811),
                                Color(0xEE050811),
                                Color(0xFF050811),
                                Color(0xFF000000)
                            )
                        )
                    )
                    .padding(bottom = 200.dp)
            )

            // Left & Right Radial Vignette
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xEE050811),
                                Color(0x55050811),
                                Color.Transparent,
                                Color.Transparent,
                                Color(0x55050811),
                                Color(0xEE050811)
                            )
                        )
                    )
            )

            // 3. Holographic Sci-Fi Scanlines, Energy Shimmer, and Rising Particles
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Moving horizontal holographic scanline
                val scanY = h * scanProgress
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            auraColor.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.35f),
                            auraColor.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, scanY),
                    end = Offset(w, scanY),
                    strokeWidth = 2.dp.toPx()
                )

                // Secondary subtle high-frequency scanlines
                val scanlineSpacing = 6.dp.toPx()
                var y = 0f
                while (y < h) {
                    drawLine(
                        color = auraColor.copy(alpha = 0.025f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    y += scanlineSpacing
                }

                // Ambient Radial Aura behind the character
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            auraColor.copy(alpha = if (state == AffyState.SPEAKING) 0.30f + (amplitude * 0.25f) else 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(w / 2f, h * 0.45f),
                        radius = w * 0.65f
                    ),
                    radius = w * 0.65f,
                    center = Offset(w / 2f, h * 0.45f)
                )

                // Rising Cyber Dust Particles
                particles.forEach { p ->
                    val rawY = (1f - ((particleProgress * p.speedMultiplier) % 1.0f))
                    val particleY = h * (0.15f + rawY * 0.75f)
                    val wobbleX = sin(rawY * 10f + p.horizontalWobblePhase) * 12.dp.toPx()
                    val particleX = (w * p.xFraction) + wobbleX

                    val pAlpha = (p.baseAlpha * (1f - (rawY - 0.5f).let { if (it < 0) -it else it } * 1.5f)).coerceIn(0f, 0.7f)

                    drawCircle(
                        color = if (state == AffyState.SPEAKING) Color(0xFFE1BEE7).copy(alpha = pAlpha) else CyanCore.copy(alpha = pAlpha),
                        radius = p.sizeDp.dp.toPx(),
                        center = Offset(particleX, particleY)
                    )
                }
            }
        }
    }
}

private data class ParticleData(
    val xFraction: Float,
    val speedMultiplier: Float,
    val sizeDp: Float,
    val baseAlpha: Float,
    val horizontalWobblePhase: Float
)
