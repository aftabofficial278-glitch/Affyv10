package com.example.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.ui.components.VoiceOrb
import com.example.agent.ui.theme.AffyTheme
import com.example.agent.ui.theme.DeepNavy
import com.example.agent.ui.theme.NeonCyan
import com.example.agent.ui.theme.NeonPurple
import com.example.agent.ui.theme.TextMuted
import com.example.agent.ui.theme.TextWhite
import com.example.service.AffyAccessibilityService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AffyTheme {
                AffyAppScreen(
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AffyAppScreen(
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember { AffyAgentCoordinator(context) }

    val uiState by coordinator.uiState.collectAsState()
    val status = uiState.state.name
    var inputText by remember { mutableStateOf("") }
    var isAccessibilityRunning by remember { mutableStateOf(AffyAccessibilityService.instance != null) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permissionsLauncher.launch(
            arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.CALL_PHONE
            )
        )
    }

    LaunchedEffect(status) {
        isAccessibilityRunning = AffyAccessibilityService.instance != null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DeepNavy,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AFFY 2.5", color = TextWhite, fontSize = 20.sp)
                        Text("Autonomous Voice & Screen AI", color = NeonCyan, fontSize = 12.sp)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Accessibility Settings", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Accessibility status banner
            if (!isAccessibilityRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF332A00)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAccessibilitySettings() }
                        .testTag("accessibility_banner"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFD54F))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Service Disabled", color = Color(0xFFFFD54F), fontSize = 13.sp)
                            Text("Tap here to enable AFFY in Settings for real screen automation.", color = Color(0xFFFFECB3), fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Voice Orb
            VoiceOrb(
                size = 140.dp,
                isActive = (status != "Idle"),
                modifier = Modifier.testTag("voice_orb")
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status Badge
            Surface(
                color = Color(0xFF1E283D),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("status_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (status == "Idle") NeonCyan else NeonPurple)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(status, color = TextWhite, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick suggestion chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                val chips = listOf(
                    "Ask ChatGPT for wallpaper",
                    "Download it",
                    "Set as my wallpaper",
                    "Call contact"
                )
                items(chips) { chip ->
                    Surface(
                        color = Color(0xFF192231),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .clickable {
                                val prompt = when (chip) {
                                    "Ask ChatGPT for wallpaper" -> "AFFY, ask ChatGPT to generate an attractive phone wallpaper"
                                    "Download it" -> "Download it"
                                    "Set as my wallpaper" -> "Set it as my wallpaper"
                                    "Call contact" -> "Call Mom"
                                    else -> chip
                                }
                                scope.launch { coordinator.handleUserSpeech(prompt) }
                            }
                            .testTag("chip_${chip.replace(' ', '_').lowercase()}")
                    ) {
                        Text(
                            text = chip,
                            color = NeonCyan,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Logs feed
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("activity_logs"),
                reverseLayout = false
            ) {
                if (uiState.spokenText.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Surface(
                                color = Color(0xFF005060),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                Text(
                                    text = uiState.spokenText,
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
                if (uiState.assistantResponse.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Surface(
                                color = Color(0xFF1A233A),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(0.95f)
                            ) {
                                Text(
                                    text = uiState.assistantResponse,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Say or type command...", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = Color(0xFF131A29),
                        unfocusedContainerColor = Color(0xFF131A29)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        val toSend = inputText
                        if (toSend.isNotBlank()) {
                            scope.launch { coordinator.handleUserSpeech(toSend) }
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(NeonCyan)
                        .testTag("send_button")
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send Command", tint = DeepNavy)
                }
            }
        }
    }
}
