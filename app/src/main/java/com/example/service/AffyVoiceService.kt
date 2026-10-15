package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.agent.AffyAgentCoordinator
import com.example.data.model.AffyState
import com.example.agent.AffySttNormalizer
import com.example.ui.overlay.AffyFloatingOrbManager
import com.example.voice.AffySpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AffyVoiceService : Service() {

    enum class ServiceMode {
        STANDBY_WAKE,
        ACTIVE_CONVERSATION,
        RESPONSE_WINDOW
    }

    companion object {
        const val CHANNEL_ID = "affy_voice_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_AFFY_VOICE"
        const val ACTION_STOP = "ACTION_STOP_AFFY_VOICE"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        var instance: AffyVoiceService? = null
            private set

        var onWakeWordHeard: ((String) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: AffySpeechRecognizer? = null
    private var currentMode = ServiceMode.STANDBY_WAKE
    private var stateCollectorJob: Job? = null
    private var responseWindowTimeoutJob: Job? = null

    private val coordinator: AffyAgentCoordinator by lazy {
        AffyAgentCoordinator.getInstance(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _isRunning.value = false
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("AFFY is ready (Say 'Hey Affy' to wake)"))
        _isRunning.value = true

        initVoiceListener()
        observeCoordinatorState()

        return START_STICKY
    }

    private fun initVoiceListener() {
        speechRecognizer?.destroy()
        speechRecognizer = AffySpeechRecognizer(
            context = applicationContext,
            onResult = { spokenText ->
                Log.d("AffyVoiceService", "Voice listener captured: '$spokenText' (Mode: $currentMode)")
                val lower = spokenText.lowercase().trim()

                // 1. Check for explicit dismiss/off command
                if (lower.contains("off ho jao") || lower.contains("band ho jao") ||
                    lower.contains("chup ho jao") || lower.contains("dismiss") || lower == "cancel") {
                    Log.i("AffyVoiceService", "Dismiss command heard -> returning to standby")
                    val notifAssistant = com.example.agent.AffyNotificationAssistant.getInstance(applicationContext)
                    notifAssistant.reset()
                    returnToStandby()
                    return@AffySpeechRecognizer
                }

                // 2. If in temporary notification response window, feed directly to NotificationAssistant
                if (currentMode == ServiceMode.RESPONSE_WINDOW) {
                    responseWindowTimeoutJob?.cancel()
                    val notifAssistant = com.example.agent.AffyNotificationAssistant.getInstance(applicationContext)
                    val consumed = notifAssistant.handleSpeech(spokenText)
                    if (consumed) {
                        Log.d("AffyVoiceService", "Speech consumed by NotificationAssistant in RESPONSE_WINDOW")
                        if (notifAssistant.assistantState.value == com.example.agent.NotificationAssistantState.IDLE) {
                            returnToStandby()
                        }
                        return@AffySpeechRecognizer
                    }
                }

                val hasWakeWord = AffySttNormalizer.containsWakeWord(spokenText)
                val isOrbShowing = AffyFloatingOrbManager.isShowing.value
                val isSleeping = AffyDeviceCareManager.getInstance(applicationContext).isUserSleeping.value

                // If AFFY is in sleep mode, only an explicit wake-word can wake AFFY
                if (isSleeping && !hasWakeWord) {
                    Log.d("AffyVoiceService", "AFFY is in sleep mode ('Affy, so jao'). Ignoring background speech without wake-word: '$spokenText'")
                    return@AffySpeechRecognizer
                }

                if (isSleeping && hasWakeWord) {
                    Log.i("AffyVoiceService", "Wake word heard while sleeping! Waking AFFY up.")
                    AffyDeviceCareManager.getInstance(applicationContext).wakeUpFromSleep()
                }

                if (hasWakeWord || isOrbShowing || currentMode == ServiceMode.ACTIVE_CONVERSATION || currentMode == ServiceMode.RESPONSE_WINDOW) {
                    onWakeWordDetected(spokenText, hasWakeWord)
                }
            },
            onPartialResult = { partial ->
                if (AffyFloatingOrbManager.isShowing.value) {
                    coordinator.setPartialTranscription(partial)
                }
            },
            onAmplitudeChanged = { amp ->
                if (AffyFloatingOrbManager.isShowing.value) {
                    coordinator.setAudioAmplitude(amp)
                }
            },
            onListeningStateChanged = { isListening ->
                Log.d("AffyVoiceService", "Voice service STT state: $isListening (Mode: $currentMode)")
            }
        )

        currentMode = ServiceMode.STANDBY_WAKE
        speechRecognizer?.startWakeWordListening()
    }

    /**
     * Opens a temporary hands-free response window to capture the user's answer
     * (e.g., "haan", "sunaao", "batao", or dictated reply) without requiring the "Hey Affy" wake word.
     */
    fun startTemporaryResponseWindow(timeoutMs: Long = 12_000L) {
        serviceScope.launch(Dispatchers.Main) {
            Log.i("AffyVoiceService", "Opening temporary voice response window ($timeoutMs ms)")
            currentMode = ServiceMode.RESPONSE_WINDOW
            updateNotification("AFFY is listening for your response...")

            // Show floating orb overlay to guarantee mic capture in background
            AffyFloatingOrbManager.show(applicationContext, coordinator)

            // Start active listening (no wake word needed)
            speechRecognizer?.startListening(isFollowUp = true)

            // Timeout watchdog
            responseWindowTimeoutJob?.cancel()
            responseWindowTimeoutJob = serviceScope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                if (currentMode == ServiceMode.RESPONSE_WINDOW) {
                    Log.d("AffyVoiceService", "Response window timed out -> returning to STANDBY_WAKE")
                    val notifAssistant = com.example.agent.AffyNotificationAssistant.getInstance(applicationContext)
                    if (notifAssistant.assistantState.value != com.example.agent.NotificationAssistantState.IDLE) {
                        notifAssistant.reset()
                    }
                    returnToStandby()
                }
            }
        }
    }

    /**
     * Safely pauses the speech recognizer while AFFY is speaking to avoid audio conflict or recording self-voice.
     */
    fun pauseForSpeaking() {
        serviceScope.launch(Dispatchers.Main) {
            responseWindowTimeoutJob?.cancel()
            speechRecognizer?.stopListening()
        }
    }

    /**
     * Safely returns AffyVoiceService to standard low-power STANDBY_WAKE mode and hides orb.
     */
    fun returnToStandby() {
        responseWindowTimeoutJob?.cancel()
        responseWindowTimeoutJob = null
        currentMode = ServiceMode.STANDBY_WAKE
        updateNotification("AFFY is ready (Say 'Hey Affy' to wake)")
        AffyFloatingOrbManager.hide()
        speechRecognizer?.startWakeWordListening()
    }

    private fun onWakeWordDetected(spokenText: String, hasWakeWord: Boolean) {
        responseWindowTimeoutJob?.cancel()
        Log.i("AffyVoiceService", "[WAKE_WORD_DETECTED] text='$spokenText' hasWakeWord=$hasWakeWord")
        Log.i("AffyVoiceService", "WAKE/ACTIVE PHRASE DETECTED -> Activating AFFY (App UI remains in background)")
        currentMode = ServiceMode.ACTIVE_CONVERSATION
        Log.i("AffyVoiceService", "[SPEECH_RECOGNIZER_STOP_REQUEST] Stopping STT for Live handoff")
        speechRecognizer?.stopListening()
        updateNotification("AFFY is active")

        // 1. Show the floating orb overlay (DO NOT launch MainActivity)
        AffyFloatingOrbManager.show(applicationContext, coordinator)

        // 2. Route speech command to existing coordinator
        val cleanSpeech = if (hasWakeWord) AffySttNormalizer.stripWakeWord(spokenText) else spokenText
        if (cleanSpeech.isBlank()) {
            coordinator.handleUserSpeech("AFFY")
        } else {
            coordinator.handleUserSpeech(cleanSpeech)
        }
    }

    private fun observeCoordinatorState() {
        stateCollectorJob?.cancel()
        stateCollectorJob = serviceScope.launch {
            coordinator.uiState.collect { uiState ->
                if (uiState.state == AffyState.IDLE && !uiState.isContinuousActive && !AffyFloatingOrbManager.isShowing.value) {
                    if (currentMode == ServiceMode.ACTIVE_CONVERSATION || currentMode == ServiceMode.RESPONSE_WINDOW) {
                        Log.d("AffyVoiceService", "Active session ended -> Returning to STANDBY_WAKE mode")
                        currentMode = ServiceMode.STANDBY_WAKE
                        updateNotification("AFFY is ready (Say 'Hey Affy' to wake)")
                        speechRecognizer?.startWakeWordListening()
                    }
                } else if (uiState.state != AffyState.IDLE) {
                    if (currentMode == ServiceMode.STANDBY_WAKE) {
                        currentMode = ServiceMode.ACTIVE_CONVERSATION
                        speechRecognizer?.stopListening()
                        updateNotification("AFFY is active")
                    }
                }
            }
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AFFY Background Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps AFFY ready to respond to your voice commands."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AFFY Personal AI")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        responseWindowTimeoutJob?.cancel()
        stateCollectorJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        serviceScope.cancel()
        _isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
