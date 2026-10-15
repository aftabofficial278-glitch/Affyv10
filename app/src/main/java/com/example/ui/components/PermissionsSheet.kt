package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanCore
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSheet(
    isMicGranted: Boolean,
    isAccessibilityGranted: Boolean,
    isContactsGranted: Boolean,
    isPhoneGranted: Boolean,
    isCameraGranted: Boolean,
    isNotificationAccessGranted: Boolean,
    isOverlayGranted: Boolean,
    isVoiceServiceRunning: Boolean,
    onRequestMicPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onRequestPhonePermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onToggleVoiceService: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val batteryInfo = remember {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val statusIntent = context.registerReceiver(null, iFilter)
        val level = statusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = statusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = statusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged > 0
        Pair(pct, charging)
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Permission Center",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanCore
                )
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_permissions_button")) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AFFY requests only permissions strictly necessary for hands-free voice operations, background notification detection, floating orb presence, and verified device control.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Microphone Permission
            PermissionCard(
                title = "Microphone",
                description = "Required to hear your voice commands, continuous conversation, and wake word.",
                icon = Icons.Default.Mic,
                isGranted = isMicGranted,
                onGrantClick = onRequestMicPermission
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Notification Access (NEW)
            PermissionCard(
                title = "Notification Access",
                description = "Enables AFFY to detect WhatsApp & incoming messages in the background and read them to you hands-free upon confirmation.",
                icon = Icons.Default.Notifications,
                isGranted = isNotificationAccessGranted,
                onGrantClick = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Camera Access (NEW)
            PermissionCard(
                title = "Camera",
                description = "Enables AFFY to open the camera and take verified photos on your command.",
                icon = Icons.Default.CameraAlt,
                isGranted = isCameraGranted,
                onGrantClick = onRequestCameraPermission
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Accessibility Service Permission
            PermissionCard(
                title = "Accessibility Service",
                description = "Enables AFFY to interact with screens (click buttons, like videos, scroll, back & home gestures, send messages).",
                icon = Icons.Default.Accessibility,
                isGranted = isAccessibilityGranted,
                onGrantClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Display Over Other Apps / Floating Orb (NEW)
            PermissionCard(
                title = "Display Over Other Apps",
                description = "Allows AFFY's glowing holographic Orb to float over WhatsApp, browser, and home screen for instant access.",
                icon = Icons.Default.Layers,
                isGranted = isOverlayGranted,
                onGrantClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Contacts Permission
            PermissionCard(
                title = "Contacts",
                description = "Enables AFFY to search your address book when you say 'Call Ali' or message someone.",
                icon = Icons.Default.Contacts,
                isGranted = isContactsGranted,
                onGrantClick = onRequestContactsPermission
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Phone Calls Permission
            PermissionCard(
                title = "Phone Calls",
                description = "Enables direct hands-free calling without extra confirmation taps.",
                icon = Icons.Default.Call,
                isGranted = isPhoneGranted,
                onGrantClick = onRequestPhonePermission
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 8. Foreground Background Service
            PermissionCard(
                title = "Background Voice Service",
                description = "Runs AFFY as a persistent Android Foreground Service with continuous wake-word and notification readiness.",
                icon = Icons.Default.RecordVoiceOver,
                isGranted = isVoiceServiceRunning,
                grantButtonText = if (isVoiceServiceRunning) "Running (Tap to Stop)" else "Start Background Service",
                onGrantClick = onToggleVoiceService
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 9. Real Device Battery Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (batteryInfo.second) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = "Battery",
                            tint = if (batteryInfo.second) EmeraldActive else CyanCore
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Battery Observer",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = if (batteryInfo.second) "Charging (${batteryInfo.first}%)" else "Battery Level: ${batteryInfo.first}%",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                color = if (batteryInfo.second) EmeraldActive.copy(alpha = 0.2f) else CyanCore.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${batteryInfo.first}%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (batteryInfo.second) EmeraldActive else CyanCore
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_app_settings_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent)
            ) {
                Text("Open App System Settings")
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    grantButtonText: String? = null,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) EmeraldActive else CyanCore
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isGranted) "Granted" else "Not Granted",
                        tint = if (isGranted) EmeraldActive else TextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isGranted) "Active" else "Missing",
                        fontSize = 12.sp,
                        color = if (isGranted) EmeraldActive else TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 16.sp
            )

            if (!isGranted || grantButtonText != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanCore, contentColor = SpaceBlack)
                ) {
                    Text(grantButtonText ?: "Grant $title Permission", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}
