package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.example.data.datastore.AffyPreferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AffyDiagnostics
import com.example.agent.DiagnosticEntry
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    customApiKey: String,
    isApiKeyConfigured: Boolean,
    maskedApiKey: String,
    selectedModel: String,
    personality: String,
    liveVoice: String = "Puck",
    voiceSpeed: Float,
    voicePitch: Float,
    voiceLanguage: String,
    wakeWordEnabled: Boolean,
    backgroundListening: Boolean,
    notificationAssistantEnabled: Boolean = true,
    quietModeEnabled: Boolean = false,
    isTestingConnection: Boolean,
    connectionTestResult: String?,
    isTestingTts: Boolean,
    ttsTestResult: String?,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: (String, String) -> Unit,
    onTestVoiceOutput: () -> Unit,
    onToggleNotificationAssistant: (Boolean) -> Unit = {},
    onToggleQuietMode: (Boolean) -> Unit = {},
    onSelectModel: (String) -> Unit,
    onSelectPersonality: (String) -> Unit,
    onSelectLiveVoice: (String) -> Unit = {},
    onSaveVoiceSpeed: (Float) -> Unit,
    onSaveVoicePitch: (Float) -> Unit,
    onSaveVoiceLanguage: (String) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onToggleBackgroundListening: (Boolean) -> Unit,
    batteryAwarenessEnabled: Boolean = true,
    batteryAnnounceCharging: Boolean = true,
    batteryAnnounceLow: Boolean = true,
    batteryAnnounceFull: Boolean = true,
    batteryLowThreshold: Int = 20,
    onToggleBatteryAwareness: (Boolean) -> Unit = {},
    onToggleBatteryAnnounceCharging: (Boolean) -> Unit = {},
    onToggleBatteryAnnounceLow: (Boolean) -> Unit = {},
    onToggleBatteryAnnounceFull: (Boolean) -> Unit = {},
    onSaveBatteryLowThreshold: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Title Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AFFY Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanCore
                )
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_settings_button")) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. GEMINI AI CONFIGURATION CARD
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = CyanCore)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Live Chat with Gemini", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }

                        // Storage Status Badge
                        if (isApiKeyConfigured) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(EmeraldActive.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Active • Sweet Voice",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldActive
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFFAB00).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Key Required",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFAB00)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter API Key to activate Live Chat with Gemini & Sweet Natural Voice.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )

                    if (isApiKeyConfigured && maskedApiKey.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Current Key: $maskedApiKey",
                            fontSize = 12.sp,
                            color = CyanAccent,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("get_api_key_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanCore),
                        border = BorderStroke(1.dp, CyanCore.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GET GEMINI API KEY (FREE)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input"),
                        label = { Text(if (isApiKeyConfigured) "Update Gemini API Key" else "Enter Gemini API Key to activate Live Chat") },
                        placeholder = { Text("AIzaSy...") },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle key visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanCore,
                            unfocusedBorderColor = TextMuted,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (apiKeyInput.isNotBlank()) {
                                    onSaveApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                }
                            },
                            enabled = apiKeyInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanCore, contentColor = SpaceBlack),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_api_key_button")
                        ) {
                            Text("Save Key", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { onTestConnection(apiKeyInput, selectedModel) },
                            enabled = !isTestingConnection && (isApiKeyConfigured || apiKeyInput.isNotBlank()),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_connection_button")
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.padding(2.dp), color = CyanCore, strokeWidth = 2.dp)
                            } else {
                                Text("Test Gemini")
                            }
                        }

                        if (isApiKeyConfigured) {
                            IconButton(
                                onClick = onClearApiKey,
                                modifier = Modifier.testTag("clear_api_key_button")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear API Key", tint = Color(0xFFFF5252))
                            }
                        }
                    }

                    if (connectionTestResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val isSuccess = connectionTestResult.contains("✓", ignoreCase = true) ||
                                connectionTestResult.contains("success", ignoreCase = true) ||
                                connectionTestResult.contains("verified", ignoreCase = true)
                        Text(
                            text = connectionTestResult,
                            color = if (isSuccess) EmeraldActive else Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. VOICE & TTS SETTINGS (Audible Voice Engine)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = CyanCore)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Voice Output (TTS)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }

                        OutlinedButton(
                            onClick = onTestVoiceOutput,
                            enabled = !isTestingTts,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                            modifier = Modifier.testTag("test_voice_button")
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Voice", fontSize = 12.sp)
                        }
                    }

                    if (ttsTestResult != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = ttsTestResult,
                            fontSize = 12.sp,
                            color = if (ttsTestResult.contains("✓", ignoreCase = true)) EmeraldActive else CyanAccent
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("AFFY Voice", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    val isLeda = liveVoice.equals("Leda", ignoreCase = true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isLeda,
                            onClick = { onSelectLiveVoice("Puck") },
                            label = { Text("Male Voice", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanCore,
                                selectedLabelColor = SpaceBlack
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isLeda,
                            onClick = { onSelectLiveVoice("Leda") },
                            label = { Text("Female Voice", fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanCore,
                                selectedLabelColor = SpaceBlack
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isLeda) {
                            "AFFY (Female Voice): Female AI assistant voice with sweet, smart, expressive, and feminine tone with full emotional depth and natural conversational reactions."
                        } else {
                            "AFFY (Male Voice): 17-year-old youthful, attractive, natural, clear, and confident voice with warm conversational energy and full emotional depth."
                        },
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Speech Speed: ${(voiceSpeed * 100).toInt()}%", fontSize = 13.sp, color = TextSecondary)
                    Slider(
                        value = voiceSpeed,
                        onValueChange = onSaveVoiceSpeed,
                        valueRange = 0.7f..1.4f,
                        colors = SliderDefaults.colors(thumbColor = CyanCore, activeTrackColor = CyanCore)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Voice Pitch: ${(voicePitch * 100).toInt()}% (Young male tone)", fontSize = 13.sp, color = TextSecondary)
                    Slider(
                        value = voicePitch,
                        onValueChange = onSaveVoicePitch,
                        valueRange = 0.8f..1.3f,
                        colors = SliderDefaults.colors(thumbColor = CyanCore, activeTrackColor = CyanCore)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Response Language", fontSize = 13.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    val languages = listOf("auto" to "Auto Detect", "ur" to "Urdu / Roman", "hi" to "Hindi", "en" to "English")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        languages.forEach { (code, label) ->
                            FilterChip(
                                selected = voiceLanguage == code,
                                onClick = { onSaveVoiceLanguage(code) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanCore,
                                    selectedLabelColor = SpaceBlack
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. PERSONALITY ENGINE
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = CyanCore)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Personality Engine", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Young male, friendly, conversational, playful personal assistant.", fontSize = 13.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))

                    val personalities = listOf("Playful", "Friendly", "Calm", "Funny", "Mildly Annoyed")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        personalities.take(3).forEach { p ->
                            FilterChip(
                                selected = personality == p,
                                onClick = { onSelectPersonality(p) },
                                label = { Text(p, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanCore,
                                    selectedLabelColor = SpaceBlack
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. WAKE WORD & BACKGROUND SERVICE
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wake Words (\"AFFY\" & \"AFTAB\")", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Trigger voice listening by saying \"Affy\" or \"Aftab\"", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = wakeWordEnabled,
                            onCheckedChange = onToggleWakeWord,
                            colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Listening Service", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Allow voice activation & Floating Orb when minimized", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = backgroundListening,
                            onCheckedChange = onToggleBackgroundListening,
                            colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "• Say \"AFFY\" while outside the app to summon the Floating AFFY Orb.\n" +
                                "• Say \"AFFY off ho jao\" or tap X to return to quiet standby.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5.1 SMART NOTIFICATION ASSISTANT
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = CyanCore)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Spoken Notification Prompt", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("AFFY asks: \"Boss, ek message aya hai. Parh ke sunaun?\"", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = notificationAssistantEnabled,
                            onCheckedChange = onToggleNotificationAssistant,
                            colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore),
                            modifier = Modifier.testTag("notification_assistant_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Quiet Mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Suppress spoken notification prompts", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = quietModeEnabled,
                            onCheckedChange = onToggleQuietMode,
                            colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5.2 BATTERY AWARENESS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("battery_awareness_card"),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = EmeraldActive)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Battery Awareness", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("Smart charging & battery voice alerts", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = batteryAwarenessEnabled,
                            onCheckedChange = onToggleBatteryAwareness,
                            colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore),
                            modifier = Modifier.testTag("battery_awareness_switch")
                        )
                    }

                    if (batteryAwarenessEnabled) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // Announce on Charger Plugged / Unplugged
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Charging Connection Alerts", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Announce when charger is connected or disconnected", fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = batteryAnnounceCharging,
                                onCheckedChange = onToggleBatteryAnnounceCharging,
                                colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Announce 100% Full Charge
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("100% Full Charge Alert", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Voice alert when battery reaches full charge to avoid overcharge", fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = batteryAnnounceFull,
                                onCheckedChange = onToggleBatteryAnnounceFull,
                                colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Announce Low Battery
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Low Battery Warning", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Voice reminder when battery drops below threshold", fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = batteryAnnounceLow,
                                onCheckedChange = onToggleBatteryAnnounceLow,
                                colors = SwitchDefaults.colors(checkedThumbColor = SpaceBlack, checkedTrackColor = CyanCore)
                            )
                        }

                        if (batteryAnnounceLow) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Low Battery Alert Threshold: $batteryLowThreshold%", fontSize = 12.sp, color = CyanAccent)
                            Slider(
                                value = batteryLowThreshold.toFloat(),
                                onValueChange = { onSaveBatteryLowThreshold(it.toInt()) },
                                valueRange = 10f..40f,
                                steps = 5,
                                colors = SliderDefaults.colors(thumbColor = CyanCore, activeTrackColor = CyanCore)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5.4 REAL-TIME SYSTEM DIAGNOSTICS
            var showDiagnostics by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = CyanCore)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("System Diagnostics", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        OutlinedButton(
                            onClick = { showDiagnostics = !showDiagnostics },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent)
                        ) {
                            Text(if (showDiagnostics) "Hide" else "View Logs", fontSize = 12.sp)
                        }
                    }

                    if (showDiagnostics) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val voiceProvider by AffyDiagnostics.currentVoiceProvider.collectAsState()
                        Text("Voice Engine: $voiceProvider", fontSize = 12.sp, color = EmeraldActive)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Recent WhatsApp Activity:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                        val stages: List<DiagnosticEntry> = AffyDiagnostics.getWhatsAppStages()
                        if (stages.isEmpty()) {
                            Text("No recent WhatsApp events recorded.", fontSize = 11.sp, color = TextMuted)
                        } else {
                            for (s in stages.take(4)) {
                                Text("• [${s.stage}] ${s.details}", fontSize = 11.sp, color = if (s.isSuccess) TextSecondary else Color(0xFFFF8A80))
                            }
                        }
                        val notifLogs: List<DiagnosticEntry> = AffyDiagnostics.getNotificationLogs()
                        if (notifLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Recent Notification Announcements:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            for (n in notifLogs.take(3)) {
                                Text("• [${n.stage}] ${n.details}", fontSize = 11.sp, color = if (n.isSuccess) TextSecondary else Color(0xFFFF8A80))
                            }
                        }
                        val contactLogs: List<DiagnosticEntry> = AffyDiagnostics.getContactLogs()
                        if (contactLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Recent Contact Resolution:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            for (c in contactLogs.take(3)) {
                                Text("• [${c.action}] ${c.details}", fontSize = 11.sp, color = if (c.isSuccess) TextSecondary else Color(0xFFFF8A80))
                            }
                        }
                        val convLogs: List<DiagnosticEntry> = AffyDiagnostics.getConversationLogs()
                        if (convLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Recent Hands-Free & Barge-In:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            for (cv in convLogs.take(3)) {
                                Text("• [${cv.action}] ${cv.details}", fontSize = 11.sp, color = if (cv.isSuccess) TextSecondary else Color(0xFFFF8A80))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Recent Agent Logs:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                        val recentLogs: List<DiagnosticEntry> = AffyDiagnostics.logs.value.take(4)
                        if (recentLogs.isEmpty()) {
                            Text("No recent logs recorded.", fontSize = 11.sp, color = TextMuted)
                        } else {
                            for (l in recentLogs) {
                                Text("• [${l.category}] ${l.action}: ${l.input.take(35)}", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. PRIVACY & SECURITY
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldActive)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Privacy & Security", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "• API keys are stored encrypted locally in DataStore on your device.\n" +
                                "• Auto-replies and phone automation run strictly via Android Accessibility & Direct Reply.\n" +
                                "• Never uploads private conversation logs to external servers.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

