package com.example.service

import android.app.KeyguardManager
import android.content.Context
import android.telecom.TelecomManager
import android.util.Log
import com.example.agent.AffyDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Intelligent device usage tracker and caring guardian.
 * Monitors continuous screen usage, interaction frequency, and app workflows.
 * Proactively advises healthy breaks with cooldowns, exceptions, and non-repetitive advice.
 * Also provides conservative device locking checks.
 */
class AffyDeviceCareManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AffyDeviceCare"

        // Meaningful continuous screen active duration threshold (e.g., 45 minutes)
        private const val CONTINUOUS_USAGE_THRESHOLD_MS = 45 * 60 * 1000L

        // Cooldown between break reminders (at least 30 minutes)
        private const val BREAK_REMINDER_COOLDOWN_MS = 30 * 60 * 1000L

        // Auto-lock cooldown (at least 15 minutes)
        private const val AUTO_LOCK_COOLDOWN_MS = 15 * 60 * 1000L

        @Volatile
        private var INSTANCE: AffyDeviceCareManager? = null

        fun getInstance(context: Context): AffyDeviceCareManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AffyDeviceCareManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val screenSessionStartTime = AtomicLong(System.currentTimeMillis())
    private val lastInteractionTime = AtomicLong(System.currentTimeMillis())
    private val lastBreakAdviceTime = AtomicLong(0L)
    private val lastLockAttemptTime = AtomicLong(0L)

    private val _isUserSleeping = MutableStateFlow(false)
    val isUserSleeping: StateFlow<Boolean> = _isUserSleeping.asStateFlow()

    private var breakAdviceCallback: ((adviceText: String) -> Unit)? = null

    fun setBreakAdviceCallback(callback: (String) -> Unit) {
        this.breakAdviceCallback = callback
    }

    /**
     * Records active screen interaction (touch, text entry, or navigation).
     */
    fun recordUserInteraction(packageName: String? = null) {
        lastInteractionTime.set(System.currentTimeMillis())
    }

    /**
     * Resets active screen-on session (e.g. when screen turned off or user rested).
     */
    fun onScreenTurnedOn() {
        screenSessionStartTime.set(System.currentTimeMillis())
        lastInteractionTime.set(System.currentTimeMillis())
    }

    fun onScreenTurnedOff() {
        val now = System.currentTimeMillis()
        val duration = now - screenSessionStartTime.get()
        Log.d(TAG, "Screen turned off. Continuous session was: ${duration / 1000}s")
    }

    /**
     * Checks if user has been continuously actively using the phone for an extended period,
     * considering exceptions for phone calls, active navigation, media editing, or active missions.
     */
    fun evaluateUsageAndAdviseBreak(
        currentForegroundApp: String?,
        isMissionActive: Boolean = false,
        isGeminiLiveActive: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val sessionDuration = now - screenSessionStartTime.get()
        val timeSinceLastBreak = now - lastBreakAdviceTime.get()

        // 1. Threshold check
        if (sessionDuration < CONTINUOUS_USAGE_THRESHOLD_MS || timeSinceLastBreak < BREAK_REMINDER_COOLDOWN_MS) {
            return
        }

        // 2. Exception checks: Never interrupt during calls, navigation, video editing, or active missions
        if (isMissionActive || isGeminiLiveActive) {
            Log.d(TAG, "Break reminder suppressed: Mission or Live conversation is actively running.")
            return
        }

        if (isInPhoneCall()) {
            Log.d(TAG, "Break reminder suppressed: User is currently on a phone call.")
            return
        }

        val app = currentForegroundApp?.lowercase() ?: ""
        val isExcludedApp = app.contains("maps") || app.contains("navigation") ||
                app.contains("capcut") || app.contains("editor") ||
                app.contains("camera") || app.contains("dialer") ||
                app.contains("telecom") || app.contains("emergency")

        if (isExcludedApp) {
            Log.d(TAG, "Break reminder suppressed: Excluded critical workflow app ($app).")
            return
        }

        // 3. Trigger caring, non-repetitive advice
        lastBreakAdviceTime.set(now)
        val prompt = "Boss, aap kaafi der se mobile use kar rahe ho. Thora break le lo, aankhon aur rest ke liye acha rahega."
        Log.i(TAG, "[CARING_BREAK_ADVICE] Triggering health break prompt")
        AffyDiagnostics.logAction("DEVICE_CARE", "BREAK_ADVICE_OFFERED", prompt, true)

        breakAdviceCallback?.invoke(prompt)
    }

    /**
     * Evaluates if conservative device locking is genuinely safe.
     * Returns true only when no calls, missions, typing, editing, or navigation is occurring.
     */
    fun isSafeToLockDevice(
        currentForegroundApp: String?,
        isMissionActive: Boolean = false,
        isTypingActive: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLockAttemptTime.get() < AUTO_LOCK_COOLDOWN_MS) {
            Log.w(TAG, "Device lock blocked by recent cooldown")
            return false
        }

        if (isMissionActive) {
            Log.w(TAG, "Device lock blocked: Active task/mission in progress")
            return false
        }

        if (isTypingActive) {
            Log.w(TAG, "Device lock blocked: User is actively typing")
            return false
        }

        if (isInPhoneCall()) {
            Log.w(TAG, "Device lock blocked: In-call state")
            return false
        }

        val app = currentForegroundApp?.lowercase() ?: ""
        val isExcluded = app.contains("maps") || app.contains("navigation") ||
                app.contains("capcut") || app.contains("editor") ||
                app.contains("camera") || app.contains("dialer") ||
                app.contains("call") || app.contains("emergency")

        if (isExcluded) {
            Log.w(TAG, "Device lock blocked by active app context: $app")
            return false
        }

        lastLockAttemptTime.set(now)
        return true
    }

    private fun isInPhoneCall(): Boolean {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.isInCall == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * "Affy, so jao" - puts AFFY into quiet sleep / inactive state without killing the service.
     */
    fun enterSleepMode() {
        _isUserSleeping.value = true
        Log.i(TAG, "AFFY entered Sleep Mode ('Affy, so jao'). Perception & speech paused.")
        AffyDiagnostics.logAction("DEVICE_CARE", "SLEEP_MODE_ENTERED", "AFFY in quiet standby sleep", true)
    }

    /**
     * Wakes AFFY up from sleep mode.
     */
    fun wakeUpFromSleep() {
        _isUserSleeping.value = false
        Log.i(TAG, "AFFY woke up from Sleep Mode.")
        AffyDiagnostics.logAction("DEVICE_CARE", "SLEEP_MODE_EXITED", "AFFY active", true)
    }
}
