package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.AffyState
import com.example.ui.components.AboutAffySheet
import com.example.ui.components.AboutDeveloperSheet
import com.example.ui.components.AffyAiCoreOrb
import com.example.ui.components.AffyHologramPresence
import com.example.ui.components.OnboardingDialog
import com.example.ui.components.PermissionsSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CosmicBlueAccent
import com.example.ui.theme.CosmicGoldDeep
import com.example.ui.theme.CosmicGoldLight
import com.example.ui.theme.CosmicGoldPrimary
import com.example.ui.theme.CosmicGoldWarm
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AffyMainScreen(
    viewModel: AffyMainViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.coordinatorUiState.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()
    val isApiKeyConfigured by viewModel.isApiKeyConfigured.collectAsState()
    val maskedApiKey by viewModel.maskedApiKey.collectAsState()
    val notificationAssistantEnabled by viewModel.notificationAssistantEnabled.collectAsState()
    val quietModeEnabled by viewModel.quietModeEnabled.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val personality by viewModel.personality.collectAsState()
    val liveVoice by viewModel.liveVoice.collectAsState()
    val voiceSpeed by viewModel.voiceSpeed.collectAsState()
    val voicePitch by viewModel.voicePitch.collectAsState()
    val voiceLanguage by viewModel.voiceLanguage.collectAsState()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsState()
    val backgroundListening by viewModel.backgroundListening.collectAsState()
    val isAccessibilityActive by viewModel.isAccessibilityActive.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()
    val connectionTestResult by viewModel.connectionTestResult.collectAsState()
    val isTestingTts by viewModel.isTestingTts.collectAsState()
    val ttsTestResult by viewModel.ttsTestResult.collectAsState()
    val showSettings by viewModel.showSettingsSheet.collectAsState()
    val showPermissions by viewModel.showPermissionsSheet.collectAsState()
    val showOnboarding by viewModel.showOnboardingDialog.collectAsState()
    val showAboutAffy by viewModel.showAboutAffySheet.collectAsState()
    val showAboutDeveloper by viewModel.showAboutDeveloperSheet.collectAsState()
    val batteryAwarenessEnabled by viewModel.batteryAwarenessEnabled.collectAsState()
    val batteryAnnounceCharging by viewModel.batteryAnnounceCharging.collectAsState()
    val batteryAnnounceLow by viewModel.batteryAnnounceLow.collectAsState()
    val batteryAnnounceFull by viewModel.batteryAnnounceFull.collectAsState()
    val batteryLowThreshold by viewModel.batteryLowThreshold.collectAsState()

    var isMicGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var isContactsGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    var isPhoneGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
    }
    var isCameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val isVoiceServiceRunning by com.example.service.AffyVoiceService.isRunning.collectAsState()

    val isNotificationAccessGranted = remember(showPermissions) {
        val enabledPackages = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
        enabledPackages.contains(context.packageName)
    }

    val isOverlayGranted = remember(showPermissions) {
        android.provider.Settings.canDrawOverlays(context)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isMicGranted = granted
        if (granted) viewModel.startListening()
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isContactsGranted = granted
    }

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isPhoneGranted = granted
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isCameraGranted = granted
    }

    fun handleMicToggle() {
        if (!isMicGranted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (uiState.state == AffyState.IDLE) {
            viewModel.startListening()
        } else {
            viewModel.stopListening()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val micPulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_pulse_scale"
    )

    // Full-Screen Edge-to-Edge Immersive 3D Golden Universe Canvas
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF04060C)),
        contentAlignment = Alignment.Center
    ) {
        // 1. FULL-SCREEN HOLOGRAPHIC CHAIR PRESENCE (Edge-to-Edge)
        AffyHologramPresence(
            state = uiState.state,
            amplitude = uiState.audioAmplitude,
            modifier = Modifier.fillMaxSize(),
            onClick = { handleMicToggle() }
        )

        // 3. TOP HUD BAR (Respects statusBar insets while universe bleeds behind it)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Status Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Accessibility Badge
                IconButton(
                    onClick = { viewModel.openPermissions() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0D1322))
                        .border(1.dp, CosmicGoldPrimary.copy(alpha = 0.25f), CircleShape)
                        .testTag("accessibility_status_icon")
                ) {
                    Icon(
                        imageVector = Icons.Default.Accessibility,
                        contentDescription = "Accessibility Status",
                        tint = if (isAccessibilityActive) EmeraldActive else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Voice / Wake Word Status
                IconButton(
                    onClick = { viewModel.openSettings() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0D1322))
                        .border(1.dp, CosmicGoldPrimary.copy(alpha = 0.25f), CircleShape)
                        .testTag("voice_service_icon")
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Voice Engine",
                        tint = if (backgroundListening) CosmicGoldPrimary else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Center Futuristic Brand Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC0D1322),
                border = androidx.compose.foundation.BorderStroke(1.dp, CosmicGoldPrimary.copy(alpha = 0.42f)),
                modifier = Modifier.clickable { viewModel.openWelcomeScreen() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (uiState.state) {
                                    AffyState.IDLE -> CosmicGoldPrimary
                                    AffyState.CONNECTING -> CyanCore
                                    AffyState.LISTENING -> CyanAccent
                                    AffyState.THINKING -> NeonViolet
                                    AffyState.SPEAKING -> CosmicGoldLight
                                    AffyState.EXECUTING -> EmeraldActive
                                    AffyState.VERIFYING -> CyanCore
                                    AffyState.ERROR -> CrimsonError
                                }
                            )
                    )
                    Text(
                        text = "LIVE VOICE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = CosmicGoldLight
                    )
                }
            }

            // Right HUD Actions (About AFFY, Developer Profile, Settings)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // About AFFY Capabilities
                IconButton(
                    onClick = { viewModel.openAboutAffy() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0D1322))
                        .border(1.dp, CosmicGoldPrimary.copy(alpha = 0.25f), CircleShape)
                        .testTag("about_affy_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "About AFFY Capabilities",
                        tint = CosmicGoldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Developer Profile (Aftab)
                IconButton(
                    onClick = { viewModel.openAboutDeveloper() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0D1322))
                        .border(1.dp, CosmicGoldPrimary.copy(alpha = 0.25f), CircleShape)
                        .testTag("developer_profile_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "About Developer (Aftab)",
                        tint = Color(0xFFA78BFA),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Settings Trigger
                IconButton(
                    onClick = { viewModel.openSettings() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0D1322))
                        .border(1.dp, CosmicGoldPrimary.copy(alpha = 0.25f), CircleShape)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "AFFY Settings",
                        tint = CosmicGoldWarm,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 4. LOWER HERO: STATE PILL & DYNAMIC CONVERSATIONAL SUBTITLES
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // State HUD Pill
            val stateText = when {
                uiState.state == AffyState.CONNECTING -> "CONNECTING LIVE..."
                uiState.state == AffyState.LISTENING -> "AFFY LISTENING"
                uiState.state == AffyState.THINKING -> "THINKING..."
                uiState.state == AffyState.SPEAKING -> "AFFY"
                uiState.state == AffyState.EXECUTING -> "EXECUTING..."
                uiState.state == AffyState.VERIFYING -> "VERIFYING..."
                uiState.state == AffyState.ERROR -> "ERROR"
                else -> "STANDBY"
            }

            val stateColor = when {
                uiState.state == AffyState.CONNECTING -> CyanCore
                uiState.state == AffyState.LISTENING -> CyanAccent
                uiState.state == AffyState.THINKING -> NeonViolet
                uiState.state == AffyState.SPEAKING -> CosmicGoldPrimary
                uiState.state == AffyState.EXECUTING -> EmeraldActive
                uiState.state == AffyState.VERIFYING -> CyanCore
                uiState.state == AffyState.ERROR -> CrimsonError
                else -> CosmicGoldWarm
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xD90A0E1A),
                border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.55f)),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Text(
                    text = stateText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    color = stateColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // DYNAMIC CONVERSATIONAL SUBTITLE (AFFY responses, confirmations, action execution, or errors)
            val displaySubtitle = when {
                uiState.errorMessage != null -> uiState.errorMessage
                uiState.pendingConfirmationPlan != null -> uiState.assistantResponse.ifBlank { "Kya aap confirm karte hain?" }
                uiState.assistantResponse.isNotBlank() && uiState.state == AffyState.SPEAKING -> uiState.assistantResponse
                uiState.currentActionDescription.isNotBlank() && (uiState.state == AffyState.EXECUTING || uiState.state == AffyState.VERIFYING) -> uiState.currentActionDescription
                else -> null
            }

            AnimatedVisibility(
                visible = displaySubtitle != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                if (displaySubtitle != null) {
                    val isError = uiState.errorMessage != null
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isError) CrimsonError.copy(alpha = 0.18f) else Color(0xE60A101E),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isError) CrimsonError.copy(alpha = 0.65f) else CosmicGoldPrimary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = displaySubtitle,
                                fontSize = 14.sp,
                                fontWeight = if (isError) FontWeight.Medium else FontWeight.Normal,
                                color = if (isError) CrimsonError else TextPrimary,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )

                            if (uiState.pendingConfirmationPlan != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { viewModel.agentCoordinator.confirmPendingAction() },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive),
                                        modifier = Modifier.testTag("confirm_action_button")
                                    ) {
                                        Text("✓ Haan / Yes", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.agentCoordinator.cancelPendingAction() },
                                        modifier = Modifier.testTag("cancel_action_button")
                                    ) {
                                        Text("✕ Nahi / Cancel", color = CrimsonError)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. BOTTOM ONE-TAP MIC TRIGGER & CONTROL DOCK
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Quick suggestions when Idle
            AnimatedVisibility(
                visible = uiState.state == AffyState.IDLE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "Tap to speak • Urdu, Hindi, English",
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                    color = TextSecondary
                )
            }

            // THE CENTRAL FUTURISTIC ONE-TAP MIC BUTTON
            val isListeningNow = uiState.state == AffyState.LISTENING || uiState.isContinuousActive
            val isActiveSession = uiState.state != AffyState.IDLE

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp)
            ) {
                // Outer Pulse Ring when listening
                if (isListeningNow) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(micPulseScale + (uiState.audioAmplitude * 0.25f))
                            .clip(CircleShape)
                            .background(CosmicGoldPrimary.copy(alpha = 0.25f))
                    )
                }

                // Main Button Circle
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = when {
                                    isListeningNow -> listOf(CosmicGoldPrimary, Color(0xFF00838F))
                                    uiState.state == AffyState.THINKING -> listOf(NeonViolet, Color(0xFF4A148C))
                                    uiState.state == AffyState.SPEAKING -> listOf(CosmicGoldPrimary, CosmicGoldDeep)
                                    else -> listOf(Color(0xFF2B2110), Color(0xFF121624))
                                }
                            )
                        )
                        .border(
                            width = 2.dp,
                            color = when {
                                isListeningNow -> CyanAccent
                                uiState.state == AffyState.THINKING -> NeonViolet
                                uiState.state == AffyState.SPEAKING -> CosmicGoldLight
                                else -> CosmicGoldPrimary.copy(alpha = 0.65f)
                            },
                            shape = CircleShape
                        )
                        .clickable { handleMicToggle() }
                        .testTag("one_tap_mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isActiveSession) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isActiveSession) "Stop Session" else "Start Speaking",
                        tint = if (uiState.state == AffyState.SPEAKING) Color(0xFF140D00) else Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }

    // SETTINGS MODAL SHEET
    if (showSettings) {
        SettingsSheet(
            customApiKey = customApiKey,
            isApiKeyConfigured = isApiKeyConfigured,
            maskedApiKey = maskedApiKey,
            selectedModel = selectedModel,
            personality = personality,
            voiceSpeed = voiceSpeed,
            voicePitch = voicePitch,
            voiceLanguage = voiceLanguage,
            wakeWordEnabled = wakeWordEnabled,
            backgroundListening = backgroundListening,
            notificationAssistantEnabled = notificationAssistantEnabled,
            quietModeEnabled = quietModeEnabled,
            isTestingConnection = isTestingConnection,
            connectionTestResult = connectionTestResult,
            isTestingTts = isTestingTts,
            ttsTestResult = ttsTestResult,
            onSaveApiKey = { viewModel.saveApiKey(it) },
            onClearApiKey = { viewModel.clearApiKey() },
            onTestConnection = { key, model -> viewModel.testConnection(key, model) },
            onTestVoiceOutput = { viewModel.testVoiceOutput() },
            onToggleNotificationAssistant = { viewModel.toggleNotificationAssistant(it) },
            onToggleQuietMode = { viewModel.toggleQuietMode(it) },
            onSelectModel = { viewModel.selectModel(it) },
            onSelectPersonality = { viewModel.selectPersonality(it) },
            liveVoice = liveVoice,
            onSelectLiveVoice = { viewModel.selectLiveVoice(it) },
            onSaveVoiceSpeed = { viewModel.saveVoiceSpeed(it) },
            onSaveVoicePitch = { viewModel.saveVoicePitch(it) },
            onSaveVoiceLanguage = { viewModel.saveVoiceLanguage(it) },
            onToggleWakeWord = { viewModel.toggleWakeWord(it) },
            onToggleBackgroundListening = { viewModel.toggleBackgroundListening(it, context) },
            batteryAwarenessEnabled = batteryAwarenessEnabled,
            batteryAnnounceCharging = batteryAnnounceCharging,
            batteryAnnounceLow = batteryAnnounceLow,
            batteryAnnounceFull = batteryAnnounceFull,
            batteryLowThreshold = batteryLowThreshold,
            onToggleBatteryAwareness = { viewModel.toggleBatteryAwareness(it) },
            onToggleBatteryAnnounceCharging = { viewModel.toggleBatteryAnnounceCharging(it) },
            onToggleBatteryAnnounceLow = { viewModel.toggleBatteryAnnounceLow(it) },
            onToggleBatteryAnnounceFull = { viewModel.toggleBatteryAnnounceFull(it) },
            onSaveBatteryLowThreshold = { viewModel.saveBatteryLowThreshold(it) },
            onDismiss = { viewModel.closeSettings() }
        )
    }

    // PERMISSIONS MODAL SHEET
    if (showPermissions) {
        PermissionsSheet(
            isMicGranted = isMicGranted,
            isAccessibilityGranted = isAccessibilityActive,
            isContactsGranted = isContactsGranted,
            isPhoneGranted = isPhoneGranted,
            isCameraGranted = isCameraGranted,
            isNotificationAccessGranted = isNotificationAccessGranted,
            isOverlayGranted = isOverlayGranted,
            isVoiceServiceRunning = isVoiceServiceRunning,
            onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onRequestContactsPermission = { contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
            onRequestPhonePermission = { phonePermissionLauncher.launch(Manifest.permission.CALL_PHONE) },
            onRequestCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onToggleVoiceService = { viewModel.toggleBackgroundListening(!isVoiceServiceRunning, context) },
            onDismiss = { viewModel.closePermissions() }
        )
    }

    // ABOUT AFFY SHEET
    if (showAboutAffy) {
        AboutAffySheet(onDismiss = { viewModel.closeAboutAffy() })
    }

    // ABOUT DEVELOPER SHEET
    if (showAboutDeveloper) {
        AboutDeveloperSheet(onDismiss = { viewModel.closeAboutDeveloper() })
    }

    // ONBOARDING / WELCOME DIALOG
    if (showOnboarding) {
        OnboardingDialog(
            onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenAboutAffy = { viewModel.openAboutAffy() },
            onOpenAboutDeveloper = { viewModel.openAboutDeveloper() },
            onComplete = { viewModel.completeOnboarding() }
        )
    }
}
