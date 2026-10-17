package com.example.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.agent.AffyAgentCoordinator
import com.example.data.model.AffyState
import com.example.data.model.AffyUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Custom LifecycleOwner enabling Compose inside WindowManager Overlay without an Activity.
 */
internal class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    init {
        try {
            savedStateRegistryController.performAttach()
        } catch (_: Throwable) {}
        try {
            savedStateRegistryController.performRestore(Bundle())
        } catch (_: Throwable) {
            try {
                savedStateRegistryController.performRestore(null)
            } catch (_: Throwable) {}
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Standalone WindowManager overlay manager for the Floating AFFY Wake Layer.
 * Renders a futuristic holographic Orb that reacts live to the existing AFFY engine.
 */
object AffyFloatingOrbManager {

    private const val TAG = "AffyFloatingOrb"

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentCoordinator: AffyAgentCoordinator? = null
    private var permissionWatchdog: Runnable? = null

    /**
     * Checks if overlay permission is granted.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Requests overlay permission settings if needed.
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Polls briefly after the permission screen is launched so the Orb appears
     * automatically the instant the user grants "Display over other apps" —
     * without this, the very first show() call silently no-ops forever and the
     * Orb never shows up even after the permission is granted.
     */
    private fun awaitPermissionThenShow(context: Context, coordinator: AffyAgentCoordinator) {
        val appContext = context.applicationContext
        permissionWatchdog?.let { mainHandler.removeCallbacks(it) }

        var attempts = 0
        val watchdog = object : Runnable {
            override fun run() {
                attempts++
                if (canDrawOverlays(appContext)) {
                    Log.i(TAG, "Overlay permission granted -> auto-showing AFFY Orb")
                    permissionWatchdog = null
                    show(appContext, coordinator)
                    return
                }
                if (attempts < 40) { // ~40 * 500ms ≈ 20s watch window
                    mainHandler.postDelayed(this, 500)
                } else {
                    permissionWatchdog = null
                    Log.w(TAG, "Gave up waiting for overlay permission after 20s")
                }
            }
        }
        permissionWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 500)
    }

    /**
     * Displays the Floating AFFY Orb immediately over other apps.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, coordinator: AffyAgentCoordinator) {
        currentCoordinator = coordinator
        val appContext = context.applicationContext ?: context

        if (!canDrawOverlays(appContext)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Requesting overlay permission...")
            requestOverlayPermission(appContext)
            awaitPermissionThenShow(appContext, coordinator)
            return
        }

        mainHandler.post {
            try {
                val existingView = overlayView
                if (existingView != null) {
                    if (existingView.isAttachedToWindow) {
                        // Already showing and genuinely on-screen — nothing to do.
                        _isShowing.value = true
                        return@post
                    }
                    // Stale reference (e.g. window was force-removed by the OS/OEM
                    // battery-saver without going through hide()). Clean it up so we
                    // don't silently skip re-adding the real view below.
                    Log.w(TAG, "Stale overlay view reference detected, cleaning up before re-show")
                    try {
                        windowManager?.removeViewImmediate(existingView)
                    } catch (_: Exception) {
                    }
                    overlayLifecycleOwner?.destroy()
                    overlayLifecycleOwner = null
                    overlayView = null
                }

                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val metrics = appContext.resources.displayMetrics
                val initialX = (metrics.widthPixels - (110 * metrics.density).toInt()) / 2
                val initialY = (metrics.heightPixels * 0.15f).toInt()

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = initialX
                    y = initialY
                }
                layoutParams = params

                val lifecycleOwner = OverlayLifecycleOwner()
                overlayLifecycleOwner = lifecycleOwner

                val composeView = ComposeView(appContext).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(lifecycleOwner)
                    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                    setViewTreeViewModelStoreOwner(lifecycleOwner)

                    setContent {
                        AffyFloatingOrbScreen(
                            coordinator = coordinator,
                            onClose = { hide() },
                            onOpenMain = {
                                val intent = Intent(appContext, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                                appContext.startActivity(intent)
                            },
                            onDrag = { dx, dy ->
                                updatePosition(dx, dy)
                            }
                        )
                    }
                }

                overlayView = composeView
                wm.addView(composeView, params)
                _isShowing.value = true
                Log.d(TAG, "Floating AFFY Orb shown successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show Floating AFFY Orb overlay: ${e.message}", e)
                // Reset potentially half-initialized state so the NEXT show() call can
                // retry cleanly instead of getting stuck thinking a view is attached.
                overlayLifecycleOwner?.destroy()
                overlayLifecycleOwner = null
                overlayView = null
                _isShowing.value = false
            }
        }
    }

    /**
     * Updates the position of the floating overlay window when dragged.
     */
    private fun updatePosition(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = layoutParams ?: return

        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout: ${e.message}")
        }
    }

    /**
     * Smoothly hides and dismisses the Floating AFFY Orb into passive standby mode.
     */
    fun hide() {
        permissionWatchdog?.let { mainHandler.removeCallbacks(it) }
        permissionWatchdog = null
        mainHandler.post {
            try {
                val view = overlayView
                val wm = windowManager
                if (view != null && wm != null) {
                    try {
                        wm.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal error in wm.removeView: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing Floating AFFY Orb overlay: ${e.message}", e)
            } finally {
                try {
                    overlayLifecycleOwner?.destroy()
                } catch (_: Exception) {}
                overlayLifecycleOwner = null
                overlayView = null
                _isShowing.value = false
                Log.d(TAG, "Floating AFFY Orb hidden and cleaned.")
            }
        }
    }
}

/**
 * Composable screen for the futuristic Floating AFFY Orb overlay.
 */
@Composable
private fun AffyFloatingOrbScreen(
    coordinator: AffyAgentCoordinator,
    onClose: () -> Unit,
    onOpenMain: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val uiState by coordinator.uiState.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Live Floating Orb
            FloatingHolographicOrb(
                uiState = uiState,
                onTap = {
                    if (uiState.state == AffyState.LISTENING) {
                        coordinator.stopListening()
                    } else if (uiState.state == AffyState.SPEAKING) {
                        coordinator.cancelActiveExecution()
                        coordinator.startListening(isFollowUp = true)
                    } else {
                        coordinator.startListening()
                    }
                },
                onLongTap = {
                    isExpanded = !isExpanded
                }
            )

            // Dynamic Mini Status Pill (Visible when active or listening)
            AnimatedVisibility(
                visible = uiState.state != AffyState.IDLE || isExpanded,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingStatusPill(
                    uiState = uiState,
                    onClose = onClose,
                    onOpenMain = onOpenMain
                )
            }
        }
    }
}

/**
 * Futuristic Holographic Orb with multi-ring animations and audio reactivity.
 */
@Composable
private fun FloatingHolographicOrb(
    uiState: AffyUiState,
    onTap: () -> Unit,
    onLongTap: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbTransitions")

    // Rotation animation
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbRotation"
    )

    // Pulse animation
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbPulse"
    )

    // State-based dynamic colors (Gold primary identity with tasteful state accents)
    val (primaryColor, secondaryColor, ringColor) = when (uiState.state) {
        AffyState.LISTENING -> Triple(
            Color(0xFFFFD700), // Metallic Gold
            Color(0xFF00F0FF), // Cyber Cyan
            Color(0xFF00F0FF).copy(alpha = 0.75f)
        )
        AffyState.THINKING -> Triple(
            Color(0xFFFFD700), // Metallic Gold
            Color(0xFFB000FF), // Cosmic Violet
            Color(0xFFB000FF).copy(alpha = 0.8f)
        )
        AffyState.SPEAKING -> Triple(
            Color(0xFFFFF4B8), // Champagne Solar Gold
            Color(0xFF2979FF), // Electric Cosmic Blue
            Color(0xFFFFD700).copy(alpha = 0.8f)
        )
        AffyState.EXECUTING, AffyState.VERIFYING -> Triple(
            Color(0xFFFFD700), // Metallic Gold
            Color(0xFF00E676), // Emerald / Cyan Action
            Color(0xFF00E5FF).copy(alpha = 0.75f)
        )
        AffyState.ERROR -> Triple(
            Color(0xFFFF5252),
            Color(0xFFFFB300),
            Color(0xFFFF5252).copy(alpha = 0.75f)
        )
        else -> Triple(
            Color(0xFFFFD700), // Calm Golden Universe
            Color(0xFFD48806), // Deep Burnished Gold
            Color(0xFFFFD700).copy(alpha = 0.45f)
        )
    }

    val amp = uiState.audioAmplitude.coerceIn(0.05f, 1f)
    val dynamicScale = if (uiState.state == AffyState.LISTENING || uiState.state == AffyState.SPEAKING) {
        1f + (amp * 0.22f)
    } else {
        pulseScale
    }

    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(dynamicScale)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        // Outer Glowing Canvas (Rings & Waveform)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 4.dp.toPx()

            // Outer reactive glow ring
            drawCircle(
                color = ringColor.copy(alpha = 0.25f * (amp * 1.5f).coerceIn(0.2f, 0.8f)),
                radius = radius + (amp * 8.dp.toPx()),
                center = center
            )

            // Rotating segmented energy ring
            val sweepAngle = 270f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(primaryColor, secondaryColor, primaryColor.copy(alpha = 0.1f), primaryColor)
                ),
                startAngle = rotationAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            )

            // Inner orbit ring
            drawCircle(
                color = primaryColor.copy(alpha = 0.45f),
                radius = radius - 4.dp.toPx(),
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )
        }

        // Inner Core Glassmorphic Disk
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF131B2E),
                            Color(0xFF0A0E17)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(primaryColor, primaryColor.copy(alpha = 0.3f))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "AFFY",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.SansSerif
                )
                // Micro State Dot
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
            }
        }
    }
}

/**
 * Compact Floating Status Pill showing speech text, action status, and quick controls.
 */
@Composable
private fun FloatingStatusPill(
    uiState: AffyUiState,
    onClose: () -> Unit,
    onOpenMain: () -> Unit
) {
    val stateText = when {
        uiState.partialTranscription.isNotBlank() -> uiState.partialTranscription
        uiState.spokenText.isNotBlank() && uiState.state == AffyState.THINKING -> uiState.spokenText
        uiState.assistantResponse.isNotBlank() && uiState.state == AffyState.SPEAKING -> uiState.assistantResponse
        uiState.currentActionDescription.isNotBlank() -> uiState.currentActionDescription
        uiState.state == AffyState.LISTENING -> "Suno boss..."
        uiState.state == AffyState.THINKING -> "Soch raha hoon..."
        uiState.state == AffyState.EXECUTING -> "Kar raha hoon..."
        else -> "AFFY Standby"
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 120.dp, max = 220.dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xEE0A0E17),
        border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stateText,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenMain() }
                    .padding(end = 4.dp)
            )

            // Close button (Dismiss overlay to standby)
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Standby / Close",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}