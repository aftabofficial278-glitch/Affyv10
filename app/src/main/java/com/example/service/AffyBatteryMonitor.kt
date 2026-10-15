package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.example.data.datastore.AffyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Monitors battery status (plugged/unplugged, percentage thresholds)
 * and triggers voice announcements according to user preferences in Settings.
 */
class AffyBatteryMonitor(
    private val context: Context,
    private val preferences: AffyPreferences = AffyPreferences(context),
    private val onAnnounce: (String) -> Unit
) {
    companion object {
        private const val TAG = "AffyBatteryMonitor"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isReceiverRegistered = false
    private var lastAnnouncedState: String? = null
    private var lastKnownLevel: Int = -1
    private var isLastKnownCharging: Boolean = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBatteryChanged(intent)
        }
    }

    fun start() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
            isReceiverRegistered = true
            Log.d(TAG, "Battery monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    fun stop() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(batteryReceiver)
            isReceiverRegistered = false
            Log.d(TAG, "Battery monitoring stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        if (level < 0 || scale <= 0) return
        val batteryPct = ((level / scale.toFloat()) * 100).toInt()
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged > 0
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL || batteryPct >= 100

        scope.launch {
            val isEnabled = preferences.batteryAwarenessEnabledFlow.first()
            if (!isEnabled) return@launch

            val announceCharging = preferences.batteryAnnounceChargingFlow.first()
            val announceLow = preferences.batteryAnnounceLowFlow.first()
            val announceFull = preferences.batteryAnnounceFullFlow.first()
            val lowThreshold = preferences.batteryLowThresholdFlow.first()

            // 1. CHARGING STATE CHANGE DETECTION (Silent logging only — never trigger audible voice interruptions)
            if (lastKnownLevel != -1 && isCharging != isLastKnownCharging) {
                Log.d(TAG, "Charger state changed: isCharging=$isCharging batteryPct=$batteryPct% (Voice notification suppressed)")
            }

            // 2. 100% FULL CHARGE ALERT
            if (isFull && isCharging && announceFull) {
                val stateKey = "battery_full_100"
                if (lastAnnouncedState != stateKey) {
                    val msg = "Battery 100% full ho chuki hai. Charger unplug kar sakte hain."
                    triggerAnnouncement(stateKey, msg)
                }
            }

            // 3. LOW BATTERY THRESHOLD ALERT
            if (batteryPct <= lowThreshold && !isCharging && announceLow) {
                val stateKey = "battery_low_$lowThreshold"
                if (lastAnnouncedState != stateKey) {
                    val msg = "Battery kam ho rahi hai, sirf $batteryPct% bachi hai. Please charger laga lijiye."
                    triggerAnnouncement(stateKey, msg)
                }
            } else if (batteryPct > lowThreshold && isCharging) {
                // Reset low battery trigger state once charging
                if (lastAnnouncedState?.startsWith("battery_low_") == true) {
                    lastAnnouncedState = null
                }
            }

            lastKnownLevel = batteryPct
            isLastKnownCharging = isCharging
        }
    }

    private fun triggerAnnouncement(stateKey: String, speechText: String) {
        lastAnnouncedState = stateKey
        Log.i(TAG, "Battery announcement: $speechText")
        onAnnounce(speechText)
    }
}
