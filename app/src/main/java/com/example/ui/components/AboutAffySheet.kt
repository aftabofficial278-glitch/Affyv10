package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAffySheet(
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = CyanCore,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "About AFFY",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_about_affy_sheet_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. AFFY Identity & What AFFY Is
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(CyanCore.copy(alpha = 0.5f), Color(0xFF8B5CF6).copy(alpha = 0.4f))
                    )
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "AFFY — Personal AI Assistant",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Created by Aftab",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFA78BFA)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "AFFY is an evolving personal AI assistant designed to understand natural voice communication, observe on-device context, execute multi-step workflows, and assist the user like an intelligent, trusted companion.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. What AFFY Can Do
            SectionHeader(title = "WHAT AFFY CAN DO")

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.RecordVoiceOver,
                title = "Natural Voice Interaction",
                description = "AFFY can communicate naturally through voice and understand conversational instructions.",
                accentColor = CyanCore
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.Visibility,
                title = "Screen Intelligence",
                description = "AFFY can observe and understand visible Android screen content, UI elements, buttons, fields, dialogs, and relevant screen context.",
                accentColor = Color(0xFF60A5FA)
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.TouchApp,
                title = "Android Control",
                description = "AFFY is designed to interact with the Android device through its available accessibility and system capabilities.",
                accentColor = EmeraldActive
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.PhoneAndroid,
                title = "Intelligent Task Execution",
                description = "AFFY can break a complex request into multiple steps and execute those steps sequentially.",
                accentColor = Color(0xFFF59E0B)
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.Layers,
                title = "Multi-App Workflows",
                description = "AFFY can move between applications as part of a larger user-requested task while maintaining the relevant mission context.",
                accentColor = Color(0xFFA78BFA)
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.Language,
                title = "Multilingual Communication",
                description = "AFFY can understand and respond according to the language used by the user, including multilingual and mixed-language conversations where supported.",
                accentColor = Color(0xFF38BDF8)
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 3. How AFFY Works
            SectionHeader(title = "HOW AFFY WORKS")

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.Psychology,
                title = "Goal-Based Intelligence",
                description = "AFFY focuses on the user's desired outcome rather than relying only on fixed commands.",
                accentColor = Color(0xFF818CF8)
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.AutoAwesome,
                title = "Adaptive Reasoning",
                description = "When the screen or situation changes, AFFY can re-observe the environment, adapt its plan, and continue where possible.",
                accentColor = CyanAccent
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.CheckCircle,
                title = "Verification",
                description = "AFFY verifies observable results instead of assuming an action succeeded.",
                accentColor = EmeraldActive
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.Memory,
                title = "Memory",
                description = "AFFY can remember information when the user explicitly asks it to remember, while protecting sensitive credentials.",
                accentColor = Color(0xFFFB923C)
            )

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.PauseCircle,
                title = "Pause, Resume & Recovery",
                description = "Longer tasks can be paused, resumed, interrupted, cancelled, and recovered from verified checkpoints.",
                accentColor = Color(0xFF34D399)
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 4. Long-Term Vision
            SectionHeader(title = "LONG-TERM VISION")

            Spacer(modifier = Modifier.height(10.dp))

            CapabilityCard(
                icon = Icons.Default.AutoAwesome,
                title = "Personal AI Companion",
                description = "The long-term vision is for AFFY to become a highly capable personal AI companion that understands the user, the device, and the current situation naturally.",
                accentColor = Color(0xFFA78BFA)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Permissions / Accessibility Accuracy Note
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "System Note: On-device interaction depends on Android Accessibility permissions and the accessibility metadata exposed by installed applications. Sensitive credentials and device security gates are always protected.",
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = TextSecondary,
        letterSpacing = 1.5.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.5.sp,
                    color = TextSecondary,
                    lineHeight = 17.5.sp
                )
            }
        }
    }
}
