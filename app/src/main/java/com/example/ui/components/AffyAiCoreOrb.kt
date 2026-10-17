package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val SPHERE_GRID_SIZE = 220
private const val NOISE_DIM = 32
private const val NOISE_MASK = 31

/**
 * Precomputed 3D Ray-Sphere geometry & continuous 3D trilinear noise volume.
 * Allocated once via [remember] so per-frame 3D volumetric & surface shading runs with zero GC allocations.
 */
private class Sphere3DRenderEngine {
    val totalPixels = SPHERE_GRID_SIZE * SPHERE_GRID_SIZE
    val pixelBuffer = IntArray(totalPixels)
    val bitmap: Bitmap = Bitmap.createBitmap(SPHERE_GRID_SIZE, SPHERE_GRID_SIZE, Bitmap.Config.ARGB_8888)

    // Normalized screen-space coordinates (-1.18 .. +1.18) where radius 1.0 = base 3D sphere boundary
    val nxLut = FloatArray(totalPixels)
    val nyLut = FloatArray(totalPixels)
    val rLut = FloatArray(totalPixels)

    // Seamless 32x32x32 3D noise volume for volumetric golden plasma & 3D dark metallic/glass carapace
    val noiseVolume = FloatArray(NOISE_DIM * NOISE_DIM * NOISE_DIM)

    init {
        val center = (SPHERE_GRID_SIZE - 1) * 0.5f
        val baseRadiusPx = SPHERE_GRID_SIZE * 0.425f // Leaves 15% radial margin for peeling outer 3D shells
        val invRadius = 1f / baseRadiusPx

        var idx = 0
        for (py in 0 until SPHERE_GRID_SIZE) {
            val ny = (py - center) * invRadius
            for (px in 0 until SPHERE_GRID_SIZE) {
                val nx = (px - center) * invRadius
                val r = sqrt(nx * nx + ny * ny)
                nxLut[idx] = nx
                nyLut[idx] = ny
                rLut[idx] = r
                idx++
            }
        }

        // Deterministic smooth 3D noise volume initialization
        for (z in 0 until NOISE_DIM) {
            for (y in 0 until NOISE_DIM) {
                for (x in 0 until NOISE_DIM) {
                    val h = (x * 374761393 + y * 668265263 + z * 2147483647) xor ((x * y * z) * 1274126177)
                    val mixed = (h xor (h ushr 13)) * 1274126177
                    val norm = ((mixed xor (mixed ushr 16)) and 0x7FFFFFFF).toFloat() / 2147483647f
                    noiseVolume[(z shl 10) or (y shl 5) or x] = norm
                }
            }
        }
    }

    /**
     * Fast, continuous trilinear-interpolated 3D noise in [0f..1f] on the seamless 32^3 volume.
     */
    fun sampleNoise3D(x: Float, y: Float, z: Float): Float {
        val sx = x + 1024f
        val sy = y + 1024f
        val sz = z + 1024f

        val xi = sx.toInt()
        val yi = sy.toInt()
        val zi = sz.toInt()

        val xf = sx - xi
        val yf = sy - yi
        val zf = sz - zi

        // Smooth Hermite interpolation curve: 3t^2 - 2t^3
        val u = xf * xf * (3f - 2f * xf)
        val v = yf * yf * (3f - 2f * yf)
        val w = zf * zf * (3f - 2f * zf)

        val x0 = xi and NOISE_MASK
        val x1 = (xi + 1) and NOISE_MASK
        val y0 = (yi and NOISE_MASK) shl 5
        val y1 = ((yi + 1) and NOISE_MASK) shl 5
        val z0 = (zi and NOISE_MASK) shl 10
        val z1 = ((zi + 1) and NOISE_MASK) shl 10

        val nv = noiseVolume
        val c000 = nv[z0 or y0 or x0]
        val c100 = nv[z0 or y0 or x1]
        val c010 = nv[z0 or y1 or x0]
        val c110 = nv[z0 or y1 or x1]
        val c001 = nv[z1 or y0 or x0]
        val c101 = nv[z1 or y0 or x1]
        val c011 = nv[z1 or y1 or x0]
        val c111 = nv[z1 or y1 or x1]

        val nx00 = c000 + u * (c100 - c000)
        val nx10 = c010 + u * (c110 - c010)
        val nx01 = c001 + u * (c101 - c001)
        val nx11 = c011 + u * (c111 - c011)

        val nxy0 = nx00 + v * (nx10 - nx00)
        val nxy1 = nx01 + v * (nx11 - nx01)

        return nxy0 + w * (nxy1 - nxy0)
    }

    /**
     * Renders the 3D spherical cosmic orb per pixel using true 3D ray-sphere intersection,
     * 3D surface normals, internal refracted 3D domain-warped golden plasma, dark metallic/smoked-glass
     * regions, Blinn-Phong curved illumination, Fresnel rim glow, and 3D outer layer peeling.
     */
    fun renderSphereFrame(
        rotAngleDeg: Float,
        plasmaFlowTime: Float,
        peelProgress: Float,
        liveAmp: Float,
        state: AffyState
    ) {
        val pixels = pixelBuffer
        val nxs = nxLut
        val nys = nyLut
        val rs = rLut

        // 3D rotation matrices for outer dark metallic/glass carapace and inner counter-flowing plasma core
        val yawRad = Math.toRadians(rotAngleDeg.toDouble())
        val pitchRad = Math.toRadians((sin(yawRad * 0.5) * 16.0))
        val cosY = cos(yawRad).toFloat()
        val sinY = sin(yawRad).toFloat()
        val cosX = cos(pitchRad).toFloat()
        val sinX = sin(pitchRad).toFloat()

        // Inner plasma core rotates slightly faster in 3D for parallax depth inside the glass/metallic shell
        val coreYawRad = Math.toRadians((rotAngleDeg * 1.28f).toDouble())
        val cosCY = cos(coreYawRad).toFloat()
        val sinCY = sin(coreYawRad).toFloat()

        // Subtle, organic 3D sphere breathing/pulsing from actual audio amplitude
        val sphereRadiusScale = when (state) {
            AffyState.SPEAKING -> 1.0f + liveAmp * 0.065f
            AffyState.LISTENING -> 1.0f + peelProgress * 0.03f + liveAmp * 0.055f
            else -> 1.0f
        }
        val invSphereScale = 1f / sphereRadiusScale

        // Outer peeling shell layer radius during LISTENING mode
        val peelOpen = (peelProgress * 0.72f + (if (state == AffyState.LISTENING) liveAmp * 0.45f else 0f)).coerceIn(0f, 1.15f)
        val peeledShellRadius = sphereRadiusScale * (1.0f + 0.095f * peelOpen)
        val invPeeledRadius = 1f / peeledShellRadius
        val maxRenderRadius = max(sphereRadiusScale, peeledShellRadius) + 0.04f

        // State-specific subtle secondary plasma tint (Gold always remains dominant)
        val (accentR, accentG, accentB) = when (state) {
            AffyState.LISTENING -> Triple(40f, 225f, 255f)  // Controlled Cyan-Gold energy beneath peeling shell
            AffyState.SPEAKING -> Triple(255f, 215f, 95f)   // Warm Solar Gold
            AffyState.THINKING -> Triple(175f, 105f, 255f)  // Subtle Royal Violet-Gold internal swirl
            AffyState.EXECUTING -> Triple(30f, 235f, 145f)  // Restrained Emerald-Gold action tint
            AffyState.VERIFYING -> Triple(45f, 220f, 255f)  // Optical Cyan-Gold verification tint
            AffyState.ERROR -> Triple(255f, 85f, 85f)       // Crimson alert tint
            else -> Triple(255f, 185f, 45f)                 // Pure Warm 24k Gold
        }

        val accentMix = when (state) {
            AffyState.LISTENING -> 0.28f + liveAmp * 0.18f
            AffyState.THINKING -> 0.26f
            AffyState.EXECUTING, AffyState.VERIFYING -> 0.24f
            AffyState.ERROR -> 0.42f
            else -> 0.08f
        }

        // Dark metallic/smoked-glass shell threshold: increases in LISTENING so dark shell plates peel back smoothly
        val shellThreshold = 0.44f + peelOpen * 0.19f
        val verificationBandY = if (state == AffyState.VERIFYING) {
            sin(plasmaFlowTime * 2.2f) * 0.78f
        } else {
            -99f
        }

        for (i in 0 until totalPixels) {
            val r = rs[i]
            if (r > maxRenderRadius) {
                pixels[i] = 0
                continue
            }

            val nx = nxs[i]
            val ny = nys[i]

            var finalR = 0f
            var finalG = 0f
            var finalB = 0f
            var finalAlpha = 0f

            // =========================================================================
            // 1. MAIN 3D SPHERICAL BODY (Interior Volumetric Golden Plasma + Curved Surface)
            // =========================================================================
            val sr = r * invSphereScale
            if (sr <= 1.0f) {
                val sx = nx * invSphereScale
                val sy = ny * invSphereScale
                val sz = sqrt(max(0f, 1f - sx * sx - sy * sy))

                // Rotate 3D surface normal for outer dark metallic/glass carapace
                val rxTmp = sx * cosY + sz * sinY
                val rzTmp = -sx * sinY + sz * cosY
                val rx = rxTmp
                val ry = sy * cosX - rzTmp * sinX
                val rz = sy * sinX + rzTmp * cosX

                // Refracted interior 3D coordinate for deep volumetric golden plasma (parallax depth)
                val refractScale = 0.68f + 0.32f * sz
                val ixRaw = sx * refractScale
                val iyRaw = sy * refractScale
                val izRaw = sz * 1.08f
                val ix = ixRaw * cosCY + izRaw * sinCY
                val iz = -ixRaw * sinCY + izRaw * cosCY
                val iy = iyRaw

                // 3-Octave 3D domain-warped volumetric golden plasma inside the sphere
                val warp = sampleNoise3D(
                    ix * 2.4f + plasmaFlowTime * 0.55f,
                    iy * 2.4f - plasmaFlowTime * 0.40f,
                    iz * 2.4f + plasmaFlowTime * 0.35f
                )
                val p1 = sampleNoise3D(
                    ix * 3.4f + warp * 1.55f,
                    iy * 3.4f + warp * 1.55f,
                    iz * 3.4f - plasmaFlowTime * 0.65f
                )
                val p2 = sampleNoise3D(
                    ix * 6.8f - warp * 1.2f,
                    iy * 6.8f + plasmaFlowTime * 0.9f,
                    iz * 6.8f + warp * 1.1f
                )
                val plasmaDensity = (p1 * 0.64f + p2 * 0.36f).coerceIn(0f, 1f)

                // Sharp 3D internal golden plasma filaments / veins
                val ridge = 1f - abs(plasmaDensity * 2f - 1f)
                val plasmaVein = ridge * ridge * ridge

                // Volumetric core radiance (brighter toward the 3D center of the sphere, boosted by audio)
                val audioEnergyBoost = if (state == AffyState.SPEAKING || state == AffyState.LISTENING) {
                    liveAmp * 0.42f
                } else {
                    0f
                }
                val coreDepthGlow = (sz * sz) * (0.48f + audioEnergyBoost)
                val totalPlasmaEnergy = (plasmaDensity * 0.62f + plasmaVein * 0.68f + coreDepthGlow).coerceIn(0f, 1.35f)

                // Map internal plasma energy to rich 3D golden palette (Obsidian Umber -> Burnished Gold -> 24k Gold -> Incandescent White-Gold)
                val eNorm = (totalPlasmaEnergy * 0.82f).coerceIn(0f, 1f)
                val eSq = eNorm * eNorm
                var pR = 16f + eNorm * 239f
                var pG = 9f + eSq * 198f + (eSq * eSq) * 45f
                var pB = 3f + (eSq * eSq) * 185f

                // Blend subtle state accent into the plasma veins
                val veinAccentWeight = plasmaVein * accentMix
                pR = pR * (1f - veinAccentWeight) + accentR * veinAccentWeight
                pG = pG * (1f - veinAccentWeight) + accentG * veinAccentWeight
                pB = pB * (1f - veinAccentWeight) + accentB * veinAccentWeight

                // =========================================================================
                // 2. CURVED DARK METALLIC / SMOKED-GLASS REGIONS & SUBTLE SURFACE DETAIL
                // =========================================================================
                val shellN1 = sampleNoise3D(rx * 2.8f + 4.2f, ry * 2.8f + 1.7f, rz * 2.8f + 8.5f)
                val shellN2 = sampleNoise3D(rx * 6.2f, ry * 6.2f, rz * 6.2f)
                // Subtle brushed metallic/glass micro-detail curving along the 3D sphere
                val surfaceMicroDetail = sin((ry * 16f + shellN2 * 3.5f).toDouble()).toFloat() * 0.035f
                val shellValue = shellN1 * 0.76f + shellN2 * 0.24f + surfaceMicroDetail

                // Smooth transition between open plasma fissures and dark metallic/glass carapace plates
                val deltaShell = shellValue - shellThreshold
                val metallicCoverage = ((deltaShell + 0.06f) / 0.12f).coerceIn(0f, 1f)
                val smoothMetallic = metallicCoverage * metallicCoverage * (3f - 2f * metallicCoverage)

                // Subsurface golden energy seam glowing right along the beveled edge of the dark metallic plates
                val seamProximity = (1f - abs(deltaShell) / 0.075f).coerceIn(0f, 1f)
                val seamGlow = seamProximity * seamProximity * (0.65f + peelOpen * 0.55f + audioEnergyBoost)

                // =========================================================================
                // 3. TRUE 3D CURVED SURFACE LIGHTING (Blinn-Phong + Rim + Fresnel)
                // =========================================================================
                // Primary Key Light from top-left-front: L1 = (-0.46, -0.52, 0.72)
                val ndotl = max(0.08f, sx * -0.46f + sy * -0.52f + sz * 0.72f)
                // Half-vector H1 = (-0.25, -0.28, 0.93)
                val ndoth = max(0f, sx * -0.25f + sy * -0.28f + sz * 0.93f)
                val ndoth2 = ndoth * ndoth
                val ndoth4 = ndoth2 * ndoth2
                val ndoth8 = ndoth4 * ndoth4
                val ndoth32 = ndoth8 * ndoth8 * ndoth8 * ndoth8

                // Secondary warm rim light from bottom-right: L2 = (0.55, 0.48, 0.68)
                val rimLightDot = max(0f, sx * 0.55f + sy * 0.48f + sz * 0.68f)

                // Dark metallic / smoked-glass surface color (curved 3D obsidian-titanium with warm bronze reflection)
                val glassTranslucency = 0.22f // Smoked glass lets 22% of deep internal plasma glow through
                val metalBaseR = (12f + 28f * ndotl + 34f * ndoth8) * (1f - glassTranslucency) + pR * glassTranslucency
                val metalBaseG = (13f + 22f * ndotl + 26f * ndoth8) * (1f - glassTranslucency) + pG * glassTranslucency
                val metalBaseB = (18f + 18f * ndotl + 14f * ndoth8) * (1f - glassTranslucency) + pB * glassTranslucency

                // Combine internal golden plasma with curved dark metallic/glass regions
                var surfR = pR * (1f - smoothMetallic) + metalBaseR * smoothMetallic
                var surfG = pG * (1f - smoothMetallic) + metalBaseG * smoothMetallic
                var surfB = pB * (1f - smoothMetallic) + metalBaseB * smoothMetallic

                // Add glowing golden subsurface seam along the edges of the metallic/glass plates
                surfR += seamGlow * (255f * (1f - accentMix * 0.5f) + accentR * accentMix * 0.5f)
                surfG += seamGlow * (195f * (1f - accentMix * 0.5f) + accentG * accentMix * 0.5f)
                surfB += seamGlow * (65f * (1f - accentMix * 0.5f) + accentB * accentMix * 0.5f)

                // Apply 3D spherical shading curve (natural terminator shadow & volumetric limb)
                val sphericalShade = 0.42f + 0.58f * ndotl
                surfR *= sphericalShade
                surfG *= sphericalShade
                surfB *= sphericalShade

                // Crisp curved 3D specular highlight on glass & metallic shell
                val specIntensity = ndoth32 * (0.45f + 0.55f * smoothMetallic) + ndoth8 * 0.18f
                surfR += specIntensity * 255f
                surfG += specIntensity * 242f
                surfB += specIntensity * 205f

                // 3D Spherical Fresnel grazing-angle golden rim illumination
                val oneMinusSz = 1f - sz
                val fresnel = oneMinusSz * oneMinusSz * oneMinusSz
                val rimGlow = fresnel * (0.55f + 0.45f * rimLightDot + audioEnergyBoost * 0.4f)
                surfR += rimGlow * (245f * (1f - accentMix) + accentR * accentMix)
                surfG += rimGlow * (180f * (1f - accentMix) + accentG * accentMix)
                surfB += rimGlow * (50f * (1f - accentMix) + accentB * accentMix)

                // Restrained optical verification band curving across the 3D sphere in VERIFYING state
                if (state == AffyState.VERIFYING) {
                    val bandDist = abs(sy - verificationBandY)
                    if (bandDist < 0.08f) {
                        val bandFactor = (1f - bandDist / 0.08f) * sz
                        surfR += bandFactor * 90f
                        surfG += bandFactor * 225f
                        surfB += bandFactor * 255f
                    }
                }

                // Smooth anti-aliased spherical edge drop-off at sr -> 1.0
                val edgeAA = ((1.0f - sr) / 0.028f).coerceIn(0f, 1f)
                finalR = surfR.coerceIn(0f, 255f)
                finalG = surfG.coerceIn(0f, 255f)
                finalB = surfB.coerceIn(0f, 255f)
                finalAlpha = edgeAA
            }

            // =========================================================================
            // 4. LISTENING MODE: 3D OUTER SHELL PEELING & EXPANDING LAYER
            // =========================================================================
            if (peelOpen > 0.02f) {
                val pr = r * invPeeledRadius
                if (pr in 0.72f..1.0f) {
                    val psx = nx * invPeeledRadius
                    val psy = ny * invPeeledRadius
                    val psz = sqrt(max(0f, 1f - psx * psx - psy * psy))

                    // Rotate peeled outer shell in 3D (counter-tilted slightly as it peels outward)
                    val prx = psx * cosY - psz * sinY
                    val prz = psx * sinY + psz * cosY
                    val pry = psy

                    val peelNoise = sampleNoise3D(prx * 2.5f + 1.3f, pry * 2.5f, prz * 2.5f + 5.1f)
                    // Outer shell plates exist near the limb and across curved segments, peeling open
                    val limbFocus = (pr - 0.72f) / 0.28f
                    val plateVal = peelNoise * 0.78f + limbFocus * 0.28f
                    val plateCutoff = 0.62f - peelOpen * 0.12f

                    if (plateVal > plateCutoff) {
                        val plateAlphaMask = ((plateVal - plateCutoff) / 0.09f).coerceIn(0f, 1f)
                        val outerEdgeAA = ((1.0f - pr) / 0.035f).coerceIn(0f, 1f)
                        val shellOpacity = plateAlphaMask * outerEdgeAA * (peelOpen * 0.78f).coerceIn(0f, 0.82f)

                        // 3D curved lighting on the peeled outer shell segment
                        val pNdotL = max(0.15f, psx * -0.46f + psy * -0.52f + psz * 0.72f)
                        val pNdotH = max(0f, psx * -0.25f + psy * -0.28f + psz * 0.93f)
                        val pSpec = pNdotH * pNdotH * pNdotH * pNdotH * pNdotH * pNdotH

                        // Glowing golden-cyan energy rim on the inner beveled edge of the peeled shell
                        val innerBevel = (1f - (plateVal - plateCutoff) / 0.08f).coerceIn(0f, 1f)

                        val sR = (24f * pNdotL + 220f * pSpec + innerBevel * 255f).coerceIn(0f, 255f)
                        val sG = (22f * pNdotL + 185f * pSpec + innerBevel * (200f + liveAmp * 45f)).coerceIn(0f, 255f)
                        val sB = (28f * pNdotL + 110f * pSpec + innerBevel * (85f + liveAmp * 160f)).coerceIn(0f, 255f)

                        // Alpha-composite the lifted 3D outer shell over the inner sphere / space
                        val invS = 1f - shellOpacity
                        finalR = sR * shellOpacity + finalR * invS
                        finalG = sG * shellOpacity + finalG * invS
                        finalB = sB * shellOpacity + finalB * invS
                        finalAlpha = shellOpacity + finalAlpha * invS
                    }
                }
            }

            val aInt = (finalAlpha * 255f).roundToInt().coerceIn(0, 255)
            if (aInt == 0) {
                pixels[i] = 0
            } else {
                // Pre-multiply or standard ARGB_8888 pixel packing
                val rInt = finalR.roundToInt().coerceIn(0, 255)
                val gInt = finalG.roundToInt().coerceIn(0, 255)
                val bInt = finalB.roundToInt().coerceIn(0, 255)
                pixels[i] = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
            }
        }

        bitmap.setPixels(pixels, 0, SPHERE_GRID_SIZE, 0, 0, SPHERE_GRID_SIZE, SPHERE_GRID_SIZE)
    }
}

/**
 * Large, Visually Dominant, Realistic 3D Spherical Cosmic Orb for AFFY 2.5.
 *
 * Uses a real-time per-pixel 3D Ray-Sphere & Volumetric Plasma Shader Engine ([Sphere3DRenderEngine]):
 * - True 3D spherical normals (nx, ny, nz = sqrt(1 - r^2)) with curved surface illumination.
 * - Refracted 3D domain-warped internal golden plasma veins and deep volumetric lighting.
 * - Curved dark metallic / smoked-glass outer carapace regions with Blinn-Phong specular reflections
 *   and glowing subsurface golden seams.
 * - State-driven behavior:
 *   - IDLE: Calm, slowly rotating 3D golden cosmic sphere.
 *   - LISTENING: Outer dark metallic/glass spherical layers smoothly expand and peel outward in 3D,
 *     revealing controlled golden & cyan-gold plasma energy beneath the surface that responds to
 *     real microphone [amplitude], then gradually seals back.
 *   - SPEAKING: Subtle, weighty 3D orb pulses and internal solar-gold luminance driven by real outgoing [amplitude].
 *   - EXECUTING / VERIFYING: Restrained, distinct 3D surface and close-perimeter feedback.
 */
@Composable
fun AffyAiCoreOrb(
    state: AffyState,
    amplitude: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "realistic_3d_cosmic_orb")
    val liveAmp = amplitude.coerceIn(0f, 1f)

    // Continuous 3D sphere rotation (0..360)
    val baseRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sphere_3d_rotation"
    )

    // Continuous 3D internal plasma turbulence flow phase
    val plasmaPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 62.83185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "plasma_flow_phase"
    )

    // Smoothly animated 3D outer layer peeling factor for LISTENING mode (returns gradually when done)
    val listeningPeelProgress by animateFloatAsState(
        targetValue = when (state) {
            AffyState.LISTENING -> 1.0f
            AffyState.SPEAKING -> 0.22f
            AffyState.THINKING -> 0.30f
            else -> 0.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "listening_peel_progress"
    )

    // Smoothly animated speed multiplier based on actual state
    val speedMultiplier by animateFloatAsState(
        targetValue = when (state) {
            AffyState.IDLE -> 1.0f
            AffyState.CONNECTING -> 1.45f
            AffyState.LISTENING -> 1.35f + liveAmp * 0.65f
            AffyState.THINKING -> 1.95f
            AffyState.SPEAKING -> 1.30f + liveAmp * 0.75f
            AffyState.EXECUTING -> 1.65f
            AffyState.VERIFYING -> 1.40f
            AffyState.ERROR -> 1.15f
        },
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "sphere_speed_mult"
    )

    val renderEngine = remember { Sphere3DRenderEngine() }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 340.dp, minHeight = 340.dp)
            .fillMaxSize()
            .then(modifier)
            .testTag("affy_ai_core_orb")
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minDim = min(w, h)
            val center = Offset(w * 0.5f, if (h > w * 1.25f) h * 0.45f else h * 0.5f)

            // Large, visually dominant 3D sphere size (~76% of screen width for the sphere + peeling margin)
            val renderBoxDiameter = minDim * 0.86f
            val sphereRadius = renderBoxDiameter * 0.425f

            val rotDeg = (baseRotation * speedMultiplier) % 360f
            val flowTime = plasmaPhase * (0.22f * speedMultiplier)

            // =========================================================================
            // 1. SOFT VOLUMETRIC ATMOSPHERIC LIMB GLOW BEHIND THE 3D SPHERE
            // =========================================================================
            val auraGlowColor = when (state) {
                AffyState.LISTENING -> CosmicGoldWarm
                AffyState.SPEAKING -> CosmicGoldPrimary
                AffyState.THINKING -> NeonViolet
                AffyState.EXECUTING -> EmeraldActive
                AffyState.VERIFYING -> CyanCore
                AffyState.ERROR -> CrimsonError
                else -> CosmicGoldDeep
            }

            val auraRadius = sphereRadius * (1.36f + listeningPeelProgress * 0.14f + liveAmp * 0.12f)
            val auraIntensity = (0.28f + listeningPeelProgress * 0.12f + liveAmp * 0.16f).coerceAtMost(0.56f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CosmicGoldPrimary.copy(alpha = auraIntensity),
                        auraGlowColor.copy(alpha = auraIntensity * 0.48f),
                        CosmicGoldDeep.copy(alpha = auraIntensity * 0.16f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = auraRadius
                ),
                radius = auraRadius,
                center = center
            )

            // =========================================================================
            // 2. RENDER & DRAW THE TRUE 3D RAY-SPHERE & VOLUMETRIC PLASMA TEXTURE
            // =========================================================================
            renderEngine.renderSphereFrame(
                rotAngleDeg = rotDeg,
                plasmaFlowTime = flowTime,
                peelProgress = listeningPeelProgress,
                liveAmp = liveAmp,
                state = state
            )

            val topLeftInt = IntOffset(
                x = (center.x - renderBoxDiameter * 0.5f).roundToInt(),
                y = (center.y - renderBoxDiameter * 0.5f).roundToInt()
            )
            val dstSizeInt = IntSize(
                width = renderBoxDiameter.roundToInt(),
                height = renderBoxDiameter.roundToInt()
            )

            drawImage(
                image = renderEngine.bitmap.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(SPHERE_GRID_SIZE, SPHERE_GRID_SIZE),
                dstOffset = topLeftInt,
                dstSize = dstSizeInt,
                filterQuality = FilterQuality.High
            )

            // =========================================================================
            // 3. RESTRAINED CLOSE-PERIMETER FEEDBACK FOR LISTENING / EXECUTING / VERIFYING
            // =========================================================================
            if (listeningPeelProgress > 0.04f) {
                // Controlled subsurface energy halo right around the peeling 3D outer shell
                val peelHaloRadius = sphereRadius * (1.05f + listeningPeelProgress * 0.08f + liveAmp * 0.06f)
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            CosmicGoldPrimary.copy(alpha = 0.32f * listeningPeelProgress),
                            CyanAccent.copy(alpha = (0.24f + liveAmp * 0.28f) * listeningPeelProgress),
                            CosmicGoldLight.copy(alpha = 0.38f * listeningPeelProgress),
                            CosmicGoldPrimary.copy(alpha = 0.32f * listeningPeelProgress)
                        ),
                        center = center
                    ),
                    radius = peelHaloRadius,
                    center = center,
                    style = Stroke(width = (1.5f + liveAmp * 1.8f).dp.toPx())
                )
            }

            if (state == AffyState.EXECUTING || state == AffyState.VERIFYING) {
                // Restrained, high-precision close perimeter indicator for EXECUTING / VERIFYING
                val ringRadius = sphereRadius * 1.10f
                val indicatorColor = if (state == AffyState.EXECUTING) EmeraldActive else CyanCore
                for (seg in 0 until 4) {
                    val startAngle = (rotDeg * 1.4f + seg * 90f) % 360f
                    drawArc(
                        color = indicatorColor.copy(alpha = 0.72f),
                        startAngle = startAngle,
                        sweepAngle = 36f,
                        useCenter = false,
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = Size(ringRadius * 2f, ringRadius * 2f),
                        style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}
