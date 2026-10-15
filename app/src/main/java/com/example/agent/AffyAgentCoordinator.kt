package com.example.agent

import android.content.Context
import android.util.Log
import com.example.agent.mission.AffyMission
import com.example.agent.mission.AffyMissionParser
import com.example.agent.mission.AffyMissionScheduler
import com.example.agent.mission.GoalStatus
import com.example.agent.mission.MissionStatus
import com.example.agent.task.AffyEnvironmentObserver
import com.example.agent.task.AgentExecutionResult
import com.example.agent.task.AgentResultStatus
import com.example.agent.task.AgentTask
import com.example.agent.task.AgentTaskLifecycle
import com.example.agent.task.AgentTaskStep
import com.example.agent.task.DynamicPlanGenerator
import com.example.agent.task.StepExecutionStatus
import com.example.agent.task.VerificationResult
import com.example.data.datastore.AffyPreferences
import com.example.data.db.AffyDatabase
import com.example.data.db.ConversationEntity
import com.example.data.model.ActionTypes
import com.example.data.model.AffyPlan
import com.example.data.model.AffyState
import com.example.data.model.AffyUiState
import com.example.data.remote.GeminiRepository
import com.example.service.AffyAccessibilityService
import com.example.voice.AffySpeechRecognizer
import com.example.voice.AffyTextToSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AffyAgentCoordinator(
    private val context: Context,
    private val preferences: AffyPreferences = AffyPreferences(context),
    private val geminiRepository: GeminiRepository = GeminiRepository(),
    private val actionExecutor: AffyActionExecutor = AffyActionExecutor(context),
    private val database: AffyDatabase = AffyDatabase.getDatabase(context),
    private val personalityEngine: AffyPersonalityEngine = AffyPersonalityEngine(),
    private val environmentObserver: AffyEnvironmentObserver = AffyEnvironmentObserver(context),
    private val missionScheduler: AffyMissionScheduler = AffyMissionScheduler(
        context = context,
        actionExecutor = actionExecutor,
        environmentObserver = environmentObserver,
        personalityEngine = personalityEngine
    )
) {
    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(coordinatorJob + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(AffyUiState())
    val uiState: StateFlow<AffyUiState> = _uiState.asStateFlow()

    private var speechRecognizer: AffySpeechRecognizer? = null
    private var textToSpeech: AffyTextToSpeech? = null
    val ttsEngine: AffyTextToSpeech? get() = textToSpeech

    private var geminiLiveSession: com.example.voice.GeminiLiveSession? = null
    val liveSession: com.example.voice.GeminiLiveSession? get() = geminiLiveSession

    private var audioStreamer: com.example.voice.AudioStreamer? = null
    val audioCaptureStreamer: com.example.voice.AudioStreamer? get() = audioStreamer

    private var batteryMonitor: com.example.service.AffyBatteryMonitor? = null

    // Continuous conversation (Jarvis Mode) context
    private var continuousWindowJob: kotlinx.coroutines.Job? = null
    private var activeExecutionJob: kotlinx.coroutines.Job? = null
    private var thinkingWatchdogJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var currentExecutionToken: Long = 0L
    private var lastActiveApp: String? = null
    private var lastActionType: String? = null
    private var lastSearchQuery: String? = null

    val notificationAssistant = AffyNotificationAssistant.getInstance(context).apply {
        bind(
            onSpeak = { text, onComplete ->
                _uiState.update { it.copy(assistantResponse = text, state = AffyState.SPEAKING) }
                val live = geminiLiveSession
                if (live != null && live.isLiveReady()) {
                    Log.i("AffyCoordinator", "[NOTIF_TO_LIVE] Forwarding notification announcement to active Gemini Live/Puck: '$text'")
                    val sent = live.sendNotificationAnnouncement(text)
                    if (sent) {
                        onComplete?.invoke()
                    } else {
                        Log.w("AffyCoordinator", "[NOTIF_TO_LIVE_FALLBACK] Live announcement send failed, falling back to TTS")
                        textToSpeech?.speak(text) {
                            onComplete?.invoke()
                        }
                    }
                } else {
                    // Live session unavailable - state honestly handled
                    Log.i("AffyCoordinator", "[NOTIF_LIVE_UNAVAILABLE] Gemini Live session is not connected. Speaking via TTS or reporting state honestly.")
                    val tts = textToSpeech
                    if (tts != null) {
                        tts.speak(text) {
                            onComplete?.invoke()
                        }
                    } else {
                        _uiState.update { it.copy(errorMessage = "Live session unavailable") }
                        onComplete?.invoke()
                    }
                }
            },
            onListen = {
                val voiceService = com.example.service.AffyVoiceService.instance
                if (voiceService != null && com.example.service.AffyVoiceService.isRunning.value) {
                    voiceService.startTemporaryResponseWindow()
                } else {
                    com.example.ui.overlay.AffyFloatingOrbManager.show(context, this@AffyAgentCoordinator)
                    startContinuousListeningWindow()
                }
            }
        )
    }

    init {
        Log.i("AffyCoordinator", "[LIFECYCLE] AffyAgentCoordinator.init starting, scopeActive=${scope.isActive}, hashCode=${this.hashCode()}")
        initVoiceEngine()
        com.example.service.AffyNotificationListenerService.notificationAssistant = notificationAssistant
        com.example.service.AffyNotificationListenerService.gmailManager = actionExecutor.gmailManager

        scope.launch {
            preferences.apiKeyFlow.collect { key ->
                Log.i("AffyCoordinator", "[LIFECYCLE] apiKeyFlow collected in coordinator, keyLength=${key.length}")
                textToSpeech?.setApiKey(key)
                val effectiveKey = preferences.getEffectiveApiKey()
                if (effectiveKey.isNotBlank()) {
                    geminiLiveSession?.startSession(effectiveKey)
                }
            }
        }

        scope.launch {
            preferences.liveVoiceFlow.collect { voiceName ->
                Log.i("AffyCoordinator", "Live voice preference updated: $voiceName")
                geminiLiveSession?.setVoiceName(voiceName)
                textToSpeech?.setVoiceName(voiceName)
            }
        }

        // Initialize Battery Awareness Monitor (Strictly silent if Gemini Live is active or executing tasks)
        batteryMonitor = com.example.service.AffyBatteryMonitor(
            context = context,
            preferences = preferences,
            onAnnounce = { speechText ->
                _uiState.update { it.copy(assistantResponse = speechText) }
                if (geminiLiveSession?.isSessionActive != true && _uiState.value.state != AffyState.EXECUTING && _uiState.value.state != AffyState.VERIFYING) {
                    textToSpeech?.speak(speechText)
                }
            }
        ).also { it.start() }
    }

    private fun cancelContinuousWindow() {
        continuousWindowJob?.cancel()
        continuousWindowJob = null
    }

    private fun startThinkingWatchdog(token: Long, userSpeech: String) {
        thinkingWatchdogJob?.cancel()
        thinkingWatchdogJob = scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(7500L)
            if (currentExecutionToken == token && _uiState.value.state == AffyState.THINKING) {
                Log.w("AffyCoordinator", "Thinking Watchdog triggered after 7.5s hang. Auto-recovering...")
                AffyDiagnostics.logConversationStage(
                    stage = "THINKING_WATCHDOG_RECOVERY",
                    details = "Auto-recovering from prolonged thinking for '$userSpeech'",
                    isSuccess = false
                )
                val fallbackPlan = geminiRepository.createOfflineFallbackPlan(userSpeech)
                val fallbackSpeech = personalityEngine.formatResponse(fallbackPlan.speechResponse, true, "HINDI_URDU_CASUAL")
                _uiState.update {
                    it.copy(
                        assistantResponse = fallbackSpeech,
                        state = AffyState.SPEAKING,
                        currentActionDescription = ""
                    )
                }
                textToSpeech?.speak(fallbackSpeech) {
                    if (currentExecutionToken == token) {
                        startContinuousListeningWindow()
                    }
                }
            }
        }
    }

    fun cancelActiveExecution() {
        cancelContinuousWindow()
        thinkingWatchdogJob?.cancel()
        thinkingWatchdogJob = null
        activeExecutionJob?.cancel()
        activeExecutionJob = null
        currentExecutionToken++
        geminiLiveSession?.interruptPlayback()
        textToSpeech?.stop()
        actionExecutor.singerEngine.stop()
        val currentTask = _uiState.value.activeAgentTask
        if (currentTask != null && !currentTask.isTerminal) {
            val cancelledTask = currentTask.copy(lifecycle = AgentTaskLifecycle.CANCEL, isCancelled = true)
            _uiState.update { it.copy(activeAgentTask = cancelledTask, currentActionDescription = "Task cancelled") }
            AffyDiagnostics.logTaskStage("CANCELLED", taskId = currentTask.id, details = "Task cancelled by stop/cancel.")
        }
    }

    fun pauseActiveTask() {
        val currentTask = _uiState.value.activeAgentTask ?: return
        if (!currentTask.isTerminal) {
            val paused = currentTask.copy(lifecycle = AgentTaskLifecycle.PAUSE, isPaused = true)
            _uiState.update { it.copy(activeAgentTask = paused, currentActionDescription = "Task paused") }
            AffyDiagnostics.logTaskStage("PAUSED", taskId = currentTask.id, details = "Task paused by user.")
        }
    }

    fun resumeActiveTask() {
        val currentTask = _uiState.value.activeAgentTask ?: return
        if (currentTask.isPaused) {
            val token = ++currentExecutionToken
            activeExecutionJob = scope.launch {
                val personality = preferences.personalityFlow.first()
                executeAgentTask(currentTask.copy(isPaused = false, lifecycle = AgentTaskLifecycle.EXECUTE), personality, token)
            }
        }
    }

    private fun updateState(transform: (AffyUiState) -> AffyUiState) {
        _uiState.update { current ->
            val updated = transform(current)
            if (current.state != updated.state) {
                Log.i("AffyCoordinator", "[8] STATE CHANGED - ${current.state} -> ${updated.state}")
            }
            updated
        }
    }

    private fun initVoiceEngine() {
        textToSpeech = AffyTextToSpeech(context) { isSpeaking ->
            _uiState.update { current ->
                if (isSpeaking) {
                    current.copy(state = AffyState.SPEAKING)
                } else {
                    if (current.state == AffyState.SPEAKING) {
                        current.copy(state = if (current.isContinuousActive) AffyState.LISTENING else AffyState.IDLE, audioAmplitude = 0.05f)
                    } else {
                        current
                    }
                }
            }
        }

        // Dedicated AudioStreamer for continuous PCM16 16kHz microphone capture
        audioStreamer = com.example.voice.AudioStreamer(
            context = context,
            onAudioChunk = { pcmChunk ->
                // Stream directly to Gemini Live WebSocket
                geminiLiveSession?.sendRealtimeAudioChunk(pcmChunk)
            },
            onAmplitudeChanged = { amp ->
                // Visualizer amplitude during listening
                if (_uiState.value.state == AffyState.LISTENING || _uiState.value.state == AffyState.CONNECTING) {
                    _uiState.update { it.copy(audioAmplitude = amp) }
                }
            },
            onVoiceDetected = {
                // Immediate local Barge-in / Interruption
                if (_uiState.value.state == AffyState.SPEAKING || geminiLiveSession?.isAudioStreaming() == true) {
                    Log.d("AffyCoordinator", "Barge-in voice detected by AudioStreamer! Interrupting model playback immediately.")
                    AffyDiagnostics.logConversationStage(
                        stage = "SPEECH_INTERRUPTED_AUDIO",
                        details = "User interrupted model speech with fresh voice input",
                        isSuccess = true
                    )
                    geminiLiveSession?.interruptPlayback()
                    _uiState.update {
                        it.copy(
                            state = AffyState.LISTENING,
                            isContinuousActive = true,
                            currentActionDescription = "Listening..."
                        )
                    }
                }
            }
        )

        geminiLiveSession = com.example.voice.GeminiLiveSession(
            context = context,
            onTranscriptReceived = { text, isUser ->
                thinkingWatchdogJob?.cancel()
                if (isUser && text.isNotBlank() && notificationAssistant.assistantState.value != NotificationAssistantState.IDLE) {
                    if (notificationAssistant.handleSpeech(text)) {
                        _uiState.update { it.copy(spokenText = text, partialTranscription = "") }
                        return@GeminiLiveSession
                    }
                }
                _uiState.update { current ->
                    if (isUser) {
                        current.copy(spokenText = text, partialTranscription = "")
                    } else {
                        current.copy(assistantResponse = text, state = AffyState.SPEAKING)
                    }
                }
                if (isUser && text.isNotBlank()) {
                    AffyConversationContextManager.getInstance().recordSuccessfulTurn(
                        userUtterance = text,
                        affyResponse = null,
                        intent = null
                    )
                }
                if (!isUser && text.isNotBlank()) {
                    AffyConversationContextManager.getInstance().recordSuccessfulTurn(
                        userUtterance = _uiState.value.spokenText,
                        affyResponse = text,
                        intent = null
                    )
                    scope.launch(Dispatchers.IO) {
                        try {
                            database.conversationDao().insertConversation(
                                ConversationEntity(sender = "AFFY", message = text)
                            )
                        } catch (e: Exception) {
                            Log.w("AffyCoordinator", "Failed to save bot message: ${e.message}")
                        }
                    }
                }
            },
            onAmplitudeChanged = { amp ->
                // Visualizer amplitude from native 24kHz PCM audio playback
                if (_uiState.value.state == AffyState.SPEAKING) {
                    _uiState.update { it.copy(audioAmplitude = amp) }
                }
            },
            onToolCall = { toolCall ->
                thinkingWatchdogJob?.cancel()
                _uiState.update { it.copy(state = AffyState.EXECUTING, currentActionDescription = "Executing ${toolCall.functionName}...") }
                val result = executeToolFromLiveSession(toolCall)
                _uiState.update { it.copy(currentActionDescription = "") }
                result
            },
            onSessionStateChanged = { liveState ->
                when (liveState) {
                    com.example.voice.LiveSessionState.STREAMING_AUDIO -> {
                        thinkingWatchdogJob?.cancel()
                        _uiState.update { it.copy(state = AffyState.SPEAKING, errorMessage = null) }
                    }
                    com.example.voice.LiveSessionState.EXECUTING_TOOL -> {
                        thinkingWatchdogJob?.cancel()
                        _uiState.update { it.copy(state = AffyState.EXECUTING) }
                    }
                    com.example.voice.LiveSessionState.CONNECTED -> {
                        Log.d("AffyCoordinator", "Gemini Live session connected")
                    }
                    com.example.voice.LiveSessionState.DISCONNECTED -> {
                        _uiState.update { current ->
                            if (current.state == AffyState.SPEAKING || current.state == AffyState.CONNECTING) {
                                current.copy(state = AffyState.IDLE, isContinuousActive = false, isMicActive = false, currentActionDescription = "")
                            } else current
                        }
                    }
                    com.example.voice.LiveSessionState.ERROR -> {
                        _uiState.update { current ->
                            current.copy(state = AffyState.IDLE, isContinuousActive = false, isMicActive = false, currentActionDescription = "")
                        }
                    }
                    else -> {}
                }
            },
            onLiveReady = {
                // Triggered strictly upon "setupComplete" from Gemini Live WebSocket
                Log.i("AffyCoordinator", "SETUP_COMPLETE -> LIVE_READY -> Starting AudioStreamer")
                _uiState.update {
                    it.copy(
                        state = AffyState.LISTENING,
                        isMicActive = true,
                        isContinuousActive = true,
                        currentActionDescription = "Listening...",
                        errorMessage = null
                    )
                }
                audioStreamer?.startStreaming()
            },
            onTurnComplete = {
                thinkingWatchdogJob?.cancel()
                Log.i("AffyCoordinator", "LISTENING AGAIN - Gemini Live turn completed. Continuous listening active.")
                _uiState.update {
                    it.copy(
                        state = AffyState.LISTENING,
                        isMicActive = true,
                        isContinuousActive = true,
                        currentActionDescription = "Listening...",
                        audioAmplitude = 0.05f
                    )
                }
                if (audioStreamer?.isRecording() != true) {
                    audioStreamer?.startStreaming()
                }
                startLiveInactivityWatchdog()
            },
            onConnectionError = { errorReason ->
                Log.e("AffyCoordinator", "CONNECTION_ERROR received: $errorReason")
                audioStreamer?.stopStreaming()
                _uiState.update { current ->
                    current.copy(
                        state = AffyState.IDLE,
                        isMicActive = false,
                        isContinuousActive = false,
                        currentActionDescription = "",
                        errorMessage = "Gemini Live error: $errorReason"
                    )
                }
            }
        )

        speechRecognizer = AffySpeechRecognizer(
            context = context,
            onResult = { speech ->
                handleUserSpeech(speech)
            },
            onPartialResult = { partial ->
                _uiState.update { it.copy(partialTranscription = partial) }
            },
            onAmplitudeChanged = { amp ->
                _uiState.update { it.copy(audioAmplitude = amp) }
            },
            onListeningStateChanged = { listening ->
                _uiState.update { current ->
                    current.copy(
                        isMicActive = listening,
                        state = if (listening) {
                            if (current.state == AffyState.THINKING || current.state == AffyState.EXECUTING || current.state == AffyState.SPEAKING) {
                                current.state
                            } else {
                                AffyState.LISTENING
                            }
                        } else {
                            if (current.state == AffyState.LISTENING) AffyState.IDLE else current.state
                        }
                    )
                }
            }
        )
    }

    private fun ensureVoiceEngine() {
        if (geminiLiveSession == null || audioStreamer == null || textToSpeech == null) {
            Log.i("AffyCoordinator", "[LIVE_SESSION_REINITIALIZED] Reinitializing voice engines in coordinator=${this.hashCode()}")
            initVoiceEngine()
            scope.launch {
                val voice = preferences.liveVoiceFlow.first()
                geminiLiveSession?.setVoiceName(voice)
                textToSpeech?.setVoiceName(voice)
                val key = preferences.getEffectiveApiKey()
                if (key.isNotBlank()) {
                    textToSpeech?.setApiKey(key)
                }
            }
        }
    }

    fun ensureLiveReadyForReopen() {
        Log.i("AffyCoordinator", "[LIVE_REOPEN_WARMUP_REQUEST] coordinator=${this.hashCode()}")
        ensureVoiceEngine()
        val session = geminiLiveSession
        if (session == null) {
            Log.w("AffyCoordinator", "[LIVE_REOPEN_WARMUP_SKIPPED] session is null")
            return
        }

        if (session.isLiveReady()) {
            Log.i("AffyCoordinator", "[LIVE_REOPEN_WARMUP_READY] Live session already active and ready")
            return
        }

        if (session.isConnecting()) {
            Log.i("AffyCoordinator", "[LIVE_REOPEN_WARMUP_SKIPPED] Live session connection already in flight")
            return
        }

        scope.launch {
            val effectiveKey = preferences.getEffectiveApiKey()
            if (effectiveKey.isNotBlank() && !effectiveKey.contains("your_gemini_api_key", ignoreCase = true)) {
                if (session.isLiveReady()) {
                    Log.i("AffyCoordinator", "[LIVE_REOPEN_WARMUP_READY] Session became ready before reconnect")
                    return@launch
                }
                if (session.isConnecting()) {
                    Log.i("AffyCoordinator", "[LIVE_REOPEN_WARMUP_SKIPPED] Session started connecting concurrently")
                    return@launch
                }
                Log.i("AffyCoordinator", "[LIVE_REOPEN_WARMUP_STARTED] Starting background Live warm-up on reopen")
                session.startSession(effectiveKey)
            } else {
                Log.w("AffyCoordinator", "[LIVE_REOPEN_WARMUP_SKIPPED] No valid API key configured")
            }
        }
    }

    fun startListening(isFollowUp: Boolean = false) {
        ensureVoiceEngine()
        val isLiveReady = geminiLiveSession?.isLiveReady() == true
        val isConnecting = geminiLiveSession?.isConnecting() == true
        Log.i("AffyCoordinator", "[LIVE_START_REQUEST] AffyAgentCoordinator.startListening() called, isFollowUp=$isFollowUp isLiveReady=$isLiveReady isConnecting=$isConnecting scopeActive=${scope.isActive}")
        cancelContinuousWindow()
        geminiLiveSession?.interruptPlayback()
        textToSpeech?.stop()
        activeExecutionJob?.cancel()

        AffyDiagnostics.logConversationStage(
            stage = if (isFollowUp) "SESSION_CONTINUED" else "LISTENING_STARTED",
            details = if (isFollowUp) "Continuous hands-free window active" else "Gemini Live audio stream initiated"
        )

        _uiState.update {
            it.copy(
                state = if (isLiveReady) AffyState.LISTENING else AffyState.CONNECTING,
                spokenText = "",
                partialTranscription = "",
                isContinuousActive = true,
                isMicActive = true,
                currentActionDescription = if (isLiveReady) "Listening..." else "Connecting to Gemini Live...",
                errorMessage = null
            )
        }

        scope.launch {
            ensureVoiceEngine()
            val customKey = preferences.userCustomApiKeyFlow.first().trim()
            val apiKey = customKey.ifBlank { com.example.BuildConfig.GEMINI_API_KEY.trim() }

            if (apiKey.isBlank() || apiKey.contains("your_gemini_api_key", ignoreCase = true)) {
                Log.w("AffyCoordinator", "[LIVE_START_CONNECTION_FAILED] API key is invalid or blank")
                _uiState.update {
                    it.copy(
                        state = AffyState.IDLE,
                        isMicActive = false,
                        isContinuousActive = false,
                        errorMessage = "Please enter your Gemini API key in Settings to activate Live Voice."
                    )
                }
                return@launch
            }

            Log.i("AffyCoordinator", "[LIVE_SESSION_AVAILABLE] sessionId=${geminiLiveSession?.hashCode()} isLiveReady=${geminiLiveSession?.isLiveReady()} isConnecting=${geminiLiveSession?.isConnecting()}")

            if (geminiLiveSession?.isLiveReady() == true) {
                Log.i("AffyCoordinator", "[LIVE_START_ALREADY_READY] Session already live ready, starting AudioStreamer")
                _uiState.update {
                    it.copy(
                        state = AffyState.LISTENING,
                        isMicActive = true,
                        isContinuousActive = true,
                        currentActionDescription = "Listening..."
                    )
                }
                audioStreamer?.startStreaming()
            } else if (geminiLiveSession?.isConnecting() == true) {
                Log.i("AffyCoordinator", "[LIVE_START_ALREADY_CONNECTING] Connection already in-flight, waiting for onLiveReady callback without recreating socket")
                // UI is already set to CONNECTING, connection will trigger onLiveReady when complete
            } else {
                Log.i("AffyCoordinator", "[LIVE_START_RECONNECT] Starting fresh Live session connection")
                geminiLiveSession?.startSession(apiKey)
            }
        }
    }

    fun stopListening() {
        cancelActiveExecution()
        AffyDiagnostics.logConversationStage("LISTENING_STOPPED", details = "Live conversation stopped")
        audioStreamer?.stopStreaming()
        geminiLiveSession?.disconnect()
        speechRecognizer?.stopListening()
        com.example.ui.overlay.AffyFloatingOrbManager.hide()
        _uiState.update {
            it.copy(
                isContinuousActive = false,
                isMicActive = false,
                state = AffyState.IDLE,
                currentActionDescription = "",
                audioAmplitude = 0.05f
            )
        }
    }

    fun handleUserSpeech(speech: String) {
        cancelContinuousWindow()
        speechRecognizer?.stopListening()
        activeExecutionJob?.cancel()
        val token = ++currentExecutionToken

        val trimmed = speech.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(state = AffyState.IDLE, isContinuousActive = false) }
            return
        }

        val contextManager = AffyConversationContextManager.getInstance()
        val hasWakeWord = AffySttNormalizer.containsWakeWord(speech)
        val isAffySpeaking = _uiState.value.state == AffyState.SPEAKING
        val isContinuous = _uiState.value.isContinuousActive
        val isResponseWindow = notificationAssistant.assistantState.value != NotificationAssistantState.IDLE

        val attentionCategory = contextManager.classifySpeechAttention(
            rawSpeech = speech,
            normalized = trimmed,
            isAffySpeaking = isAffySpeaking,
            hasWakeWord = hasWakeWord,
            isResponseWindow = isResponseWindow,
            isContinuous = isContinuous
        )

        Log.i("AffyCoordinator", "[ATTENTION_CLASSIFIER] Speech: '$trimmed' -> Category: $attentionCategory")

        // 1. If user speech is clearly directed to another person or background conversation -> Stay completely silent!
        if (attentionCategory == SpeechAttentionCategory.LIKELY_DIRECTED_TO_OTHER_PERSON ||
            attentionCategory == SpeechAttentionCategory.BACKGROUND_CONVERSATION) {
            Log.i("AffyCoordinator", "[INTENT_GATE] Suppressed speech directed to another person / background: '$trimmed'")
            _uiState.update { it.copy(state = AffyState.IDLE, isContinuousActive = false, currentActionDescription = "") }
            return
        }

        // 2. Interruption handling
        if (isAffySpeaking && attentionCategory == SpeechAttentionCategory.INTERRUPTION_OF_AFFY) {
            Log.i("AffyCoordinator", "[INTERRUPTION] User interrupted AFFY with: '$trimmed'")
            cancelActiveExecution()
            val lower = trimmed.lowercase()
            if (lower == "chup" || lower == "stop" || lower == "ruk jao" || lower == "chup ho jao" || lower == "band ho jao" || lower == "cancel") {
                _uiState.update { it.copy(state = AffyState.IDLE, isContinuousActive = false, currentActionDescription = "") }
                return
            }
        }

        // Notification Assistant conversational branch (e.g. "Boss, ek message aya hai. Parh ke sunaun?" -> "haan")
        if (notificationAssistant.handleSpeech(trimmed)) {
            _uiState.update { it.copy(spokenText = trimmed, partialTranscription = "") }
            return
        }

        val pendingPlan = _uiState.value.pendingConfirmationPlan
        if (pendingPlan != null) {
            handleConfirmationResponse(trimmed, pendingPlan)
            return
        }

        // Mid-Mission user modification (Phase 9.7: "TikTok wala part chhor do, baqi continue karo")
        val currentMission = _uiState.value.activeMission
        val lowerTrimmed = trimmed.lowercase(java.util.Locale.ROOT)

        // Phase 17.3 & 18.7: Smart User Follow-up & Recovery ("Ye wala chhor do", "Is step ko skip karo", "Retry karo", "Isko dobara karo", "Previous step dobara karo", "Isko save kar do")
        if (currentMission != null && !currentMission.isComplete) {
            // 1. Skip / Drop Current Step/Goal
            if (lowerTrimmed == "ye wala chhor do" || lowerTrimmed == "ye wala choro" || lowerTrimmed == "is step ko skip karo" ||
                lowerTrimmed == "is goal ko skip karo" || lowerTrimmed == "skip karo" || lowerTrimmed == "ye goal cancel karo") {
                val updatedGoals = currentMission.goals.mapIndexed { idx, goal ->
                    if (idx == currentMission.currentGoalIndex) goal.copy(status = GoalStatus.SKIPPED, resultSummary = "User requested skip") else goal
                }
                val skippedMission = currentMission.copy(goals = updatedGoals, currentGoalIndex = (currentMission.currentGoalIndex + 1).coerceAtMost(updatedGoals.size))
                missionScheduler.checkpointManager.saveCheckpoint(skippedMission, "User skipped step")
                val skipMsg = "Theek hai boss, yeh step skip karke agla step shuru kar raha hoon."
                _uiState.update { it.copy(activeMission = skippedMission, assistantResponse = skipMsg, state = AffyState.SPEAKING) }
                textToSpeech?.speak(skipMsg) {
                    handleUserSpeech("continue")
                }
                return
            }

            // 2. Retry / Redo Step
            if (lowerTrimmed == "isko dobara karo" || lowerTrimmed == "retry karo" || lowerTrimmed == "dobara try karo" ||
                lowerTrimmed == "previous step dobara karo" || lowerTrimmed == "previous wala karo") {
                val targetIndex = if (lowerTrimmed.contains("previous")) (currentMission.currentGoalIndex - 1).coerceAtLeast(0) else currentMission.currentGoalIndex
                val resetGoals = currentMission.goals.mapIndexed { idx, goal ->
                    if (idx >= targetIndex) goal.copy(status = GoalStatus.PENDING, failureReason = null) else goal
                }
                val retryMission = currentMission.copy(goals = resetGoals, currentGoalIndex = targetIndex, isPaused = false)
                missionScheduler.checkpointManager.saveCheckpoint(retryMission, "User retry requested")
                val retryMsg = "Theek hai boss, step dobara fresh screen par execute kar raha hoon."
                _uiState.update { it.copy(activeMission = retryMission, assistantResponse = retryMsg, state = AffyState.SPEAKING) }
                textToSpeech?.speak(retryMsg) {
                    handleUserSpeech("continue")
                }
                return
            }

            // 3. Contextual Save
            if (lowerTrimmed == "isko save kar do" || lowerTrimmed == "save kar do" || lowerTrimmed == "save karo") {
                activeExecutionJob = scope.launch {
                    val res = actionExecutor.executeAction(com.example.data.model.AffyActionItem(type = com.example.data.model.ActionTypes.TAP, target = "Save"))
                    val reply = if (res.isSuccess) "Boss, save action execute ho gaya." else "Boss, save button nahi mila."
                    _uiState.update { it.copy(assistantResponse = reply, state = AffyState.SPEAKING) }
                    textToSpeech?.speak(reply)
                }
                return
            }

            // 4. Contextual App Replacement ("Ab iske bajaye WhatsApp kholo", "Ab different goal karo")
            if (lowerTrimmed.contains("iske bajaye") || lowerTrimmed.contains("is ke bajaye") || lowerTrimmed.contains("different goal")) {
                val newAppOrGoal = trimmed.replace(Regex("(?i).*?\\b(iske bajaye|is ke bajaye|different goal|ke bajaye)\\b[:\\s]*"), "").trim()
                if (newAppOrGoal.isNotBlank()) {
                    val mod = AffyMissionParser.modifyMission(currentMission, newAppOrGoal)
                    val modMsg = "Theek hai boss, plan adapt karke '$newAppOrGoal' shuru kar raha hoon."
                    _uiState.update { it.copy(activeMission = mod, assistantResponse = modMsg, state = AffyState.SPEAKING) }
                    textToSpeech?.speak(modMsg) {
                        handleUserSpeech("continue")
                    }
                    return
                }
            }
        }
        if (lowerTrimmed == "ye choro" || lowerTrimmed == "ye chhor do" || lowerTrimmed == "cancel this task" ||
            lowerTrimmed == "cancel task" || lowerTrimmed == "task cancel karo" || lowerTrimmed == "stop task") {
            val cancelledMission = currentMission?.copy(missionStatus = MissionStatus.CANCELLED)
            missionScheduler.checkpointManager.clearCheckpoint()
            activeExecutionJob?.cancel()
            AffyDiagnostics.logTaskStage("TASK_CANCELLED", details = "User cancelled active task")
            val cancelMsg = "Theek hai boss, task cancel kar diya hai."
            _uiState.update { it.copy(activeMission = cancelledMission, activeAgentTask = null, assistantResponse = cancelMsg, state = AffyState.SPEAKING, currentActionDescription = "") }
            textToSpeech?.speak(cancelMsg)
            return
        }

        // Phase 15.6 & 16.7: Dynamic Objective Replacement ("Ab ye karo...", "Now do this...")
        if (lowerTrimmed.startsWith("ab ye karo") || lowerTrimmed.startsWith("ab yeh karo") || lowerTrimmed.startsWith("now do this")) {
            val newObjective = trimmed.replace(Regex("(?i)^(?:ab ye karo|ab yeh karo|now do this)[:\\s,]*"), "").trim()
            if (newObjective.isNotBlank()) {
                activeExecutionJob?.cancel()
                AffyDiagnostics.logTaskStage("TASK_INTERRUPTED", details = "User replaced objective with: $newObjective")
                val ackNewMsg = "Theek hai boss, purana task chhor kar ab '$newObjective' shuru kar raha hoon."
                _uiState.update { it.copy(assistantResponse = ackNewMsg, state = AffyState.SPEAKING) }
                textToSpeech?.speak(ackNewMsg) {
                    handleUserSpeech(newObjective)
                }
                return
            }
        }

        // Phase 12.3: User Pause Commands ("Affy stop", "Affy ruk jao", "Pause", "Abhi ruk")
        if (currentMission != null && !currentMission.isComplete &&
            (lowerTrimmed == "affy stop" || lowerTrimmed == "affy ruk jao" || lowerTrimmed == "ruk jao" ||
             lowerTrimmed == "ruko" || lowerTrimmed == "pause" || lowerTrimmed == "abhi ruk" || lowerTrimmed == "abhi ruko")) {
            val pausedMission = currentMission.copy(isPaused = true, missionStatus = MissionStatus.PAUSED, pauseReason = "User paused")
            missionScheduler.checkpointManager.saveCheckpoint(pausedMission, "User paused")
            activeExecutionJob?.cancel()
            AffyDiagnostics.logTaskStage("TASK_INTERRUPTED", taskId = currentMission.missionId, details = "User paused active task")
            val pauseMsg = "Theek hai boss, mission pause kar diya hai."
            _uiState.update { it.copy(activeMission = pausedMission, assistantResponse = pauseMsg, state = AffyState.SPEAKING, currentActionDescription = "Paused") }
            textToSpeech?.speak(pauseMsg)
            return
        }

        // Phase 12.4: User Resume Commands ("Affy continue karo", "Jahan ruke thay wahan se continue karo", "Resume")
        if (lowerTrimmed.contains("continue karo") || lowerTrimmed.contains("resume") || lowerTrimmed.contains("jahan ruke") || lowerTrimmed == "continue") {
            val missionToResume = currentMission?.copy(isPaused = false) ?: run {
                val cp = missionScheduler.checkpointManager.getLatestCheckpoint()
                if (cp != null) {
                    AffyMissionParser.parseMission(cp.originalUserRequest).copy(currentGoalIndex = cp.currentGoalIndex)
                } else null
            }
            if (missionToResume != null) {
                val resumeMsg = "Jahan ruke thay wahan se continue kar raha hoon boss."
                _uiState.update { it.copy(state = AffyState.EXECUTING, activeMission = missionToResume, assistantResponse = resumeMsg, currentActionDescription = "Resuming mission...") }
                activeExecutionJob = scope.launch {
                    if (currentExecutionToken != token) return@launch
                    val personality = preferences.personalityFlow.first()
                    val completed = missionScheduler.executeMission(
                        mission = missionToResume,
                        personality = personality,
                        executionToken = token,
                        isTokenActive = { currentExecutionToken == token },
                        onProgress = { updatedMission, statusText ->
                            _uiState.update { it.copy(activeMission = updatedMission, currentActionDescription = statusText) }
                        }
                    )
                    _uiState.update { it.copy(activeMission = completed, assistantResponse = completed.finalReport ?: "Mission complete ho gaya hai boss.", state = AffyState.SPEAKING, currentActionDescription = "") }
                    withContext(Dispatchers.Main) {
                        if (currentExecutionToken != token) return@withContext
                        textToSpeech?.speak(completed.finalReport ?: "Mission complete ho gaya hai boss.") {
                            startListening(isFollowUp = true)
                        }
                    }
                }
                textToSpeech?.speak(resumeMsg)
                return
            }
        }

        // Phase 12.10: Mission Progress Query ("Kitna kaam hua?", "Progress batao", "Status kya hai")
        if (lowerTrimmed.contains("kitna kaam hua") || lowerTrimmed.contains("progress batao") || lowerTrimmed.contains("status kya hai") || lowerTrimmed.contains("mission status")) {
            if (currentMission != null) {
                val done = currentMission.completedGoals.size
                val total = currentMission.goals.size
                val rep = if (done == 0) {
                    "Boss, abhi mission shuru hua hai, $total goals baqi hain."
                } else {
                    "Boss, $done out of $total goals complete ho chuke hain: ${currentMission.completedGoals.joinToString(", ") { it.description }}."
                }
                _uiState.update { it.copy(assistantResponse = rep, state = AffyState.SPEAKING) }
                textToSpeech?.speak(rep)
                return
            }
        }

        if (currentMission != null && !currentMission.isComplete && AffyMissionParser.isMissionModification(trimmed)) {
            val modified = AffyMissionParser.modifyMission(currentMission, trimmed)
            val ackMsg = "Theek hai boss, yeh part skip karke baqi mission continue kar raha hoon."
            _uiState.update { it.copy(activeMission = modified, assistantResponse = ackMsg, state = AffyState.SPEAKING) }
            textToSpeech?.speak(ackMsg)
            return
        }

        val contextMgr = AffyConversationContextManager.getInstance()
        val pendingContactDisambiguation = contextMgr.getPendingContactDisambiguation()
        if (pendingContactDisambiguation != null && !pendingContactDisambiguation.isExpired()) {
            if (handlePendingContactDisambiguation(trimmed, pendingContactDisambiguation, token)) {
                return
            }
        }

        val normalized = AffySttNormalizer.normalize(trimmed)

        // 1. Human Conversation Understanding (Maya-style: incomplete sentences, hesitations, trailing thoughts)
        if (contextMgr.isIncompleteSpeech(trimmed, normalized)) {
            Log.d("AffyCoordinator", "Incomplete speech/hesitation detected: '$trimmed'. Saving context & reassuring.")
            contextMgr.recordIncompleteSpeech(trimmed)
            val reassurance = contextMgr.getNaturalIncompleteReassurance(trimmed)
            _uiState.update {
                it.copy(
                    spokenText = trimmed,
                    partialTranscription = "",
                    assistantResponse = reassurance,
                    state = AffyState.SPEAKING,
                    currentActionDescription = "Listening..."
                )
            }
            textToSpeech?.speak(reassurance) {
                startListening(isFollowUp = true)
            }
            return
        }

        // 2. Resolve stitched speech (self-corrections, continuations, repetitions cleaned)
        val resolvedSpeech = contextMgr.resolveStitchedSpeech(trimmed, normalized)
        val resolvedNormalized = AffySttNormalizer.normalize(resolvedSpeech)

        AffyDiagnostics.markRouterStart()

        // 3. Classify intent via local AffyIntentRouter (Greetings, Casual, Emotional, Ack, Follow-ups, Commands)
        val classification = AffyIntentRouter.classify(
            rawSpeech = resolvedSpeech,
            normalizedSpeech = resolvedNormalized,
            lastActiveApp = lastActiveApp,
            lastSearchQuery = lastSearchQuery,
            isContinuousActive = _uiState.value.isContinuousActive
        )
        AffyDiagnostics.markRouterResult(
            category = classification.category.name,
            isFastPath = !classification.needsGemini
        )

        val planToExecute = classification.matchedPlan ?: tryContextualFollowUp(resolvedSpeech) ?: AffyFastCommandRouter.tryFastMatch(resolvedNormalized)

        if (planToExecute != null) {
            if (planToExecute.actions.isNotEmpty()) {
                AffyEmotionEngine.getInstance().processUserInput(resolvedSpeech, isActionCommand = true)
            }
            AffyDiagnostics.markIntentDetected(planToExecute.intent, isFastPath = true)
            Log.d("AffyCoordinator", "Intent classified as ${classification.category}: '$resolvedSpeech' -> intent: ${planToExecute.intent}")
            contextMgr.recordSuccessfulTurn(userUtterance = resolvedSpeech, affyResponse = planToExecute.speechResponse, intent = planToExecute.intent)

            // Local conversation, greetings & commands strictly bypass prolonged THINKING state:
            val targetState = if (planToExecute.actions.isEmpty()) AffyState.SPEAKING else AffyState.EXECUTING
            updateState {
                it.copy(
                    spokenText = resolvedSpeech,
                    partialTranscription = "",
                    state = targetState,
                    currentActionDescription = if (planToExecute.actions.isEmpty()) "" else "Executing..."
                )
            }
            activeExecutionJob = scope.launch {
                if (currentExecutionToken != token) return@launch
                val personality = preferences.personalityFlow.first()
                executePlan(planToExecute, resolvedSpeech, personality, token)
            }
            return
        }

        // 4. Primary Gemini Live Voice Brain Path (Live continuous conversation with native audio & tool calls)
        if (geminiLiveSession?.isLiveReady() == true) {
            val emotionTransition = AffyEmotionEngine.getInstance().processUserInput(resolvedSpeech, isActionCommand = false)
            geminiLiveSession?.updateEmotionContext(emotionTransition.toneInstruction)

            AffyDiagnostics.markIntentDetected("GEMINI_LIVE_CONVERSATION", isFastPath = true)
            updateState {
                it.copy(
                    spokenText = resolvedSpeech,
                    partialTranscription = "",
                    state = AffyState.THINKING,
                    currentActionDescription = ""
                )
            }
            startThinkingWatchdog(token, resolvedSpeech)
            scope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().insertConversation(
                        ConversationEntity(sender = "USER", message = resolvedSpeech)
                    )
                } catch (e: Exception) {
                    Log.w("AffyCoordinator", "Failed to save message: ${e.message}")
                }
            }
            contextMgr.recordSuccessfulTurn(userUtterance = resolvedSpeech, affyResponse = null, intent = "GEMINI_LIVE")
            val sent = geminiLiveSession?.sendUserSpeech(resolvedSpeech) ?: false
            if (sent) {
                return
            } else {
                thinkingWatchdogJob?.cancel()
            }
        } else {
            // Attempt connecting Gemini Live in background if API key is available
            scope.launch {
                val apiKey = preferences.getEffectiveApiKey()
                if (apiKey.isNotBlank() && geminiLiveSession?.isConnected() != true) {
                    geminiLiveSession?.startSession(apiKey)
                }
            }
        }

        // Multi-Goal Mission Engine Check (Phase 9 & 10)
        if (AffyMissionParser.isMissionPrompt(resolvedSpeech)) {
            val mission = AffyMissionParser.parseMission(resolvedSpeech)
            if (mission.goals.isNotEmpty()) {
                AffyDiagnostics.markIntentDetected("MULTI_GOAL_MISSION", isFastPath = true)
                _uiState.update {
                    it.copy(
                        spokenText = resolvedSpeech,
                        partialTranscription = "",
                        state = AffyState.EXECUTING,
                        activeMission = mission,
                        currentActionDescription = "Planning Multi-Goal Mission (${mission.goals.size} goals)..."
                    )
                }
                activeExecutionJob = scope.launch {
                    if (currentExecutionToken != token) return@launch
                    val personality = preferences.personalityFlow.first()
                    val completedMission = missionScheduler.executeMission(
                        mission = mission,
                        personality = personality,
                        executionToken = token,
                        isTokenActive = { currentExecutionToken == token },
                        onProgress = { updatedMission, statusText ->
                            _uiState.update {
                                it.copy(
                                    activeMission = updatedMission,
                                    currentActionDescription = statusText
                                )
                            }
                        }
                    )
                    _uiState.update {
                        it.copy(
                            activeMission = completedMission,
                            assistantResponse = completedMission.finalReport ?: "Mission complete ho gaya hai boss.",
                            state = AffyState.SPEAKING,
                            currentActionDescription = ""
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (currentExecutionToken != token) return@withContext
                        textToSpeech?.speak(completedMission.finalReport ?: "Mission complete ho gaya hai boss.") {
                            startListening(isFollowUp = true)
                        }
                    }
                }
                return
            }
        }

        // Check if query is a Multi-Step Task suitable for local autonomous dynamic planning
        if (classification.category == AffyIntentCategory.MULTI_STEP_TASK || DynamicPlanGenerator.isMultiStepTaskPrompt(resolvedSpeech)) {
            val dynamicSteps = DynamicPlanGenerator.createPlanFromPrompt(resolvedSpeech)
            if (dynamicSteps.size >= 2) {
                AffyDiagnostics.markIntentDetected("DYNAMIC_MULTI_STEP_TASK", isFastPath = true)
                val task = AgentTask(
                    goal = resolvedSpeech,
                    rawPrompt = resolvedSpeech,
                    steps = dynamicSteps
                )
                _uiState.update {
                    it.copy(
                        spokenText = resolvedSpeech,
                        partialTranscription = "",
                        state = AffyState.EXECUTING,
                        activeAgentTask = task,
                        currentActionDescription = "Planning autonomous task..."
                    )
                }
                activeExecutionJob = scope.launch {
                    if (currentExecutionToken != token) return@launch
                    val personality = preferences.personalityFlow.first()
                    executeAgentTask(task, personality, token)
                }
                return
            }
        }

        // FAST CONVERSATIONAL PATH vs AGENT PLANNING PATH
        val isActionRequest = isDeviceActionRequest(resolvedSpeech) ||
                classification.category == AffyIntentCategory.MULTI_STEP_TASK ||
                classification.category == AffyIntentCategory.SIMPLE_DEVICE_COMMAND ||
                classification.category == AffyIntentCategory.WHATSAPP_CONTEXT ||
                classification.category == AffyIntentCategory.GMAIL_CONTEXT ||
                classification.category == AffyIntentCategory.NOTIFICATION_CONTEXT

        if (!isActionRequest) {
            // FAST CONVERSATIONAL PATH (Direct Gemini generation, no JSON schema overhead, no action verification)
            AffyDiagnostics.markIntentDetected("FAST_CONVERSATION", isFastPath = true)
            _uiState.update {
                it.copy(
                    spokenText = resolvedSpeech,
                    partialTranscription = "",
                    state = AffyState.THINKING,
                    currentActionDescription = ""
                )
            }
            startThinkingWatchdog(token, resolvedSpeech)
            activeExecutionJob = scope.launch {
                if (currentExecutionToken != token) return@launch
                processFastConversationalPipeline(resolvedSpeech, token)
            }
            return
        }

        // FULL AGENT PLANNING PATH (Complex device actions, app automation, multi-step execution)
        AffyDiagnostics.markIntentDetected("GEMINI_PLANNING", isFastPath = false)
        val actionDesc = personalityEngine.getWaitingPhrase(resolvedSpeech)
        _uiState.update {
            it.copy(
                spokenText = resolvedSpeech,
                partialTranscription = "",
                state = AffyState.THINKING,
                isContinuousActive = false,
                currentActionDescription = actionDesc
            )
        }
        startThinkingWatchdog(token, resolvedSpeech)

        activeExecutionJob = scope.launch {
            if (currentExecutionToken != token) return@launch
            processAgentPipeline(resolvedSpeech, token)
        }
    }

    private fun isDeviceActionRequest(speech: String): Boolean {
        val lower = speech.lowercase().trim()
        val actionKeywords = listOf(
            "kholo", "open", "launch", "chalao", "play", "start",
            "call", "phone", "dial", "milao",
            "whatsapp", "message", "msg", "sms", "text karo", "bhejo", "send", "type karo",
            "torch", "flashlight", "batti", "light on", "light off",
            "volume", "awaz", "sound", "mute", "unmute", "loud",
            "brightness", "roshni", "screen light",
            "bluetooth", "wifi", "hotspot", "airplane mode", "flight mode",
            "click", "tap", "dabao", "select karo", "press", "scroll", "swipe", "upar karo", "neeche karo",
            "setting", "settings", "camera", "photo", "tasveer", "picture",
            "alarm", "timer", "reminder",
            "read notification", "notification parho", "email check", "unread email", "gmail",
            "screen pe", "screen par", "on the screen", "look at screen",
            "lock", "mobile lock", "screen lock", "phone lock", "lock karo", "lock kar do",
            "screenshot", "screen capture", "security check", "permission inspect", "permissions check", "image banao"
        )
        return actionKeywords.any { lower.contains(it) }
    }

    private suspend fun processFastConversationalPipeline(userSpeech: String, token: Long = currentExecutionToken) = withContext(Dispatchers.Default) {
        if (currentExecutionToken != token) return@withContext
        try {
            // Save user message to database asynchronously without blocking
            scope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().insertConversation(
                        ConversationEntity(sender = "USER", message = userSpeech)
                    )
                } catch (e: Exception) {
                    Log.w("AffyCoordinator", "Failed to insert user message: ${e.message}")
                }
            }

            val apiKey = preferences.getEffectiveApiKey()
            val model = preferences.modelFlow.first()
            val personality = preferences.personalityFlow.first()
            val voiceSpeed = preferences.voiceSpeedFlow.first()
            val voicePitch = preferences.voicePitchFlow.first()
            val voiceLang = preferences.voiceLanguageFlow.first()

            textToSpeech?.updateConfig(voiceSpeed, voicePitch, voiceLang)

            // Get recent conversation history (lightweight, top 6 turns)
            val historyTurns = try {
                val historyEntities = database.conversationDao().getRecentConversationsList(6)
                historyEntities.reversed().map { it.sender to it.message }
            } catch (e: Exception) {
                emptyList()
            }

            val memories = try {
                val memoryEntities = database.conversationDao().getAllMemoriesList()
                memoryEntities.map { it.key to it.value }
            } catch (e: Exception) {
                emptyList()
            }

            AffyDiagnostics.markGeminiRequestStart(model)
            val replyResult = geminiRepository.generateConversationalReply(
                apiKey = apiKey,
                model = model,
                userSpeech = userSpeech,
                personality = personality,
                recentConversationContext = historyTurns,
                memories = memories
            )

            if (currentExecutionToken != token) return@withContext
            AffyDiagnostics.markGeminiResponseReceived(replyResult.isSuccess)

            val rawReply = if (replyResult.isSuccess) {
                replyResult.getOrNull()?.trim() ?: "Ji boss!"
            } else {
                geminiRepository.createOfflineFallbackPlan(userSpeech).speechResponse
            }

            val finalReply = personalityEngine.formatResponse(rawReply, replyResult.isSuccess, personality).ifBlank {
                "Ji boss, main sun raha hoon."
            }

            if (currentExecutionToken != token) return@withContext

            // Save assistant reply to database asynchronously
            scope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().insertConversation(
                        ConversationEntity(sender = "AFFY", message = finalReply, intent = "CONVERSATIONAL_FAST_PATH", status = "SUCCESS")
                    )
                } catch (e: Exception) {
                    Log.w("AffyCoordinator", "Failed to insert assistant message: ${e.message}")
                }
            }

            _uiState.update {
                it.copy(
                    assistantResponse = finalReply,
                    state = AffyState.SPEAKING,
                    currentActionDescription = ""
                )
            }

            withContext(Dispatchers.Main) {
                if (currentExecutionToken != token) return@withContext
                textToSpeech?.speak(finalReply) {
                    if (currentExecutionToken != token) return@speak
                    startContinuousListeningWindow()
                }
            }
        } catch (e: CancellationException) {
            Log.d("AffyCoordinator", "Fast conversational pipeline cancelled for token $token")
            throw e
        } catch (e: Throwable) {
            Log.e("AffyCoordinator", "Fast conversational path exception: ${e.message}", e)
            if (currentExecutionToken == token) {
                val fallbackPlan = geminiRepository.createOfflineFallbackPlan(userSpeech)
                val fallbackSpeech = personalityEngine.formatResponse(fallbackPlan.speechResponse, true, "HINDI_URDU_CASUAL")
                _uiState.update {
                    it.copy(
                        assistantResponse = fallbackSpeech,
                        state = AffyState.SPEAKING,
                        currentActionDescription = ""
                    )
                }
                withContext(Dispatchers.Main) {
                    if (currentExecutionToken == token) {
                        textToSpeech?.speak(fallbackSpeech) {
                            if (currentExecutionToken == token) {
                                startContinuousListeningWindow()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePendingContactDisambiguation(
        userSpeech: String,
        pending: PendingContactDisambiguation,
        token: Long
    ): Boolean {
        val lower = userSpeech.lowercase().trim()

        // 1. Cancellation check
        val isCancelled = lower == "nahi" || lower == "no" || lower == "cancel" ||
                lower == "chup" || lower == "stop" || lower == "mat karo" || lower == "rehne do" ||
                lower.startsWith("cancel ") || lower.startsWith("nahi ") || lower == "kisi ko nahi"
        if (isCancelled) {
            AffyConversationContextManager.getInstance().clearPendingContactDisambiguation()
            val cancelSpeech = "Theek hai boss, cancel kar diya."
            _uiState.update {
                it.copy(
                    spokenText = userSpeech,
                    partialTranscription = "",
                    assistantResponse = cancelSpeech,
                    state = AffyState.SPEAKING,
                    currentActionDescription = ""
                )
            }
            scope.launch {
                withContext(Dispatchers.Main) {
                    textToSpeech?.speak(cancelSpeech) {
                        startContinuousListeningWindow()
                    }
                }
            }
            return true
        }

        // 2. Candidate match check
        val matchedCandidate = pending.matchCandidate(userSpeech)
        if (matchedCandidate != null) {
            AffyConversationContextManager.getInstance().clearPendingContactDisambiguation()
            _uiState.update {
                it.copy(
                    spokenText = userSpeech,
                    partialTranscription = "",
                    state = AffyState.EXECUTING,
                    currentActionDescription = "Selected ${matchedCandidate.displayString}"
                )
            }

            activeExecutionJob = scope.launch {
                if (currentExecutionToken != token) return@launch
                val result = when (pending.actionType) {
                    DisambiguationActionType.CALL -> {
                        actionExecutor.executeDirectCall(matchedCandidate.phoneNumber, matchedCandidate.name, matchedCandidate.typeLabel)
                    }
                    DisambiguationActionType.WHATSAPP -> {
                        actionExecutor.executeDirectWhatsApp(
                            recipientPhone = matchedCandidate.phoneNumber,
                            recipientName = matchedCandidate.name,
                            message = pending.whatsappMessage ?: "",
                            targetPackage = pending.whatsappTargetPackage
                        )
                    }
                }

                val speech = result.speechResponseOverride ?: result.summary
                val personality = preferences.personalityFlow.first()
                val formattedSpeech = personalityEngine.formatResponse(speech, result.isSuccess, personality)

                _uiState.update {
                    it.copy(
                        assistantResponse = formattedSpeech,
                        state = AffyState.SPEAKING,
                        currentActionDescription = if (result.isSuccess) "Action completed" else "Action failed"
                    )
                }

                scope.launch(Dispatchers.IO) {
                    try {
                        database.conversationDao().insertConversation(
                            ConversationEntity(
                                sender = "AFFY",
                                message = formattedSpeech,
                                intent = if (pending.actionType == DisambiguationActionType.CALL) ActionTypes.CALL_CONTACT else ActionTypes.SEND_WHATSAPP_MESSAGE,
                                actionSummary = result.summary,
                                status = if (result.isSuccess) "SUCCESS" else "FAILED"
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("AffyCoordinator", "Failed to log disambiguated conversation: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    if (currentExecutionToken == token) {
                        textToSpeech?.speak(formattedSpeech) {
                            if (currentExecutionToken == token) {
                                startContinuousListeningWindow()
                            }
                        }
                    }
                }
            }
            return true
        }

        // 3. If the user attempted a selection (e.g. invalid number "5" or "kaunsa wala"), re-prompt without canceling
        if (pending.isInvalidSelectionAttempt(userSpeech)) {
            val count = pending.candidates.size
            val repromptSpeech = "Boss, sirf $count options hain. 1 se $count mein se kaunsa number select karun?"
            _uiState.update {
                it.copy(
                    spokenText = userSpeech,
                    partialTranscription = "",
                    assistantResponse = repromptSpeech,
                    state = AffyState.SPEAKING,
                    currentActionDescription = "Awaiting contact selection..."
                )
            }
            scope.launch {
                withContext(Dispatchers.Main) {
                    textToSpeech?.speak(repromptSpeech) {
                        startListening(isFollowUp = true)
                    }
                }
            }
            return true
        }

        // 4. If the speech is completely unrelated, clear disambiguation and return false so normal routing handles it!
        AffyConversationContextManager.getInstance().clearPendingContactDisambiguation()
        return false
    }

    private fun handleConfirmationResponse(userSpeech: String, pendingPlan: AffyPlan) {
        val lower = userSpeech.lowercase().trim()
        val isConfirmed = lower.contains("haan") || lower.contains("yes") || lower.contains("bhej do") ||
                lower.contains("kar do") || lower.contains("theek hai") || lower.contains("sure") ||
                lower.contains("ok") || lower.contains("confirm")

        val isRejected = lower.contains("nahi") || lower.contains("no") || lower.contains("cancel") ||
                lower.contains("ruk") || lower.contains("mat karo") || lower.contains("stop")

        _uiState.update { it.copy(pendingConfirmationPlan = null) }

        val currentTask = _uiState.value.activeAgentTask
        if (isConfirmed) {
            val token = ++currentExecutionToken
            _uiState.update {
                it.copy(
                    spokenText = userSpeech,
                    state = AffyState.THINKING,
                    currentActionDescription = "Confirm kar liya! Executing..."
                )
            }
            activeExecutionJob = scope.launch {
                val personality = preferences.personalityFlow.first()
                if (currentTask != null && currentTask.requiresConfirmation) {
                    executeAgentTask(currentTask.copy(requiresConfirmation = false, lifecycle = AgentTaskLifecycle.EXECUTE), personality, token)
                } else {
                    executePlan(pendingPlan.copy(requiresConfirmation = false), userSpeech, personality, token)
                }
            }
        } else if (isRejected) {
            cancelActiveExecution()
            val cancelReply = "Theek hai boss, action cancel kar diya."
            if (currentTask != null) {
                _uiState.update {
                    it.copy(
                        spokenText = userSpeech,
                        assistantResponse = cancelReply,
                        state = AffyState.SPEAKING,
                        activeAgentTask = currentTask.copy(lifecycle = AgentTaskLifecycle.CANCEL, isCancelled = true)
                    )
                }
                AffyDiagnostics.logTaskStage("CANCELLED", taskId = currentTask.id, details = "Task cancelled on user rejection.")
            } else {
                _uiState.update {
                    it.copy(
                        spokenText = userSpeech,
                        assistantResponse = cancelReply,
                        state = AffyState.SPEAKING
                    )
                }
            }
            scope.launch {
                withContext(Dispatchers.Main) {
                    textToSpeech?.speak(cancelReply) {
                        startContinuousListeningWindow()
                    }
                }
            }
        } else {
            // User said something new instead of confirming
            handleUserSpeech(userSpeech)
        }
    }

    fun confirmPendingAction() {
        val pendingPlan = _uiState.value.pendingConfirmationPlan
        val currentTask = _uiState.value.activeAgentTask
        val token = ++currentExecutionToken
        _uiState.update { it.copy(pendingConfirmationPlan = null, state = AffyState.THINKING) }
        activeExecutionJob = scope.launch {
            val personality = preferences.personalityFlow.first()
            if (currentTask != null && currentTask.requiresConfirmation) {
                executeAgentTask(currentTask.copy(requiresConfirmation = false, lifecycle = AgentTaskLifecycle.EXECUTE), personality, token)
            } else if (pendingPlan != null) {
                executePlan(pendingPlan.copy(requiresConfirmation = false), "confirmed via button", personality, token)
            }
        }
    }

    fun cancelPendingAction() {
        val currentTask = _uiState.value.activeAgentTask
        if (currentTask != null && !currentTask.isTerminal) {
            _uiState.update { it.copy(pendingConfirmationPlan = null, state = AffyState.IDLE, activeAgentTask = currentTask.copy(lifecycle = AgentTaskLifecycle.CANCEL, isCancelled = true)) }
        } else {
            _uiState.update { it.copy(pendingConfirmationPlan = null, state = AffyState.IDLE) }
        }
    }

    private suspend fun processAgentPipeline(userSpeech: String, token: Long = currentExecutionToken) = withContext(Dispatchers.Default) {
        if (currentExecutionToken != token) return@withContext
        try {
            // Save user message to database asynchronously without blocking execution
            scope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().insertConversation(
                        ConversationEntity(
                            sender = "USER",
                            message = userSpeech
                        )
                    )
                } catch (e: Exception) {
                    Log.w("AffyCoordinator", "Failed to insert user message: ${e.message}")
                }
            }

            val apiKey = preferences.getEffectiveApiKey()
            val model = preferences.modelFlow.first()
            val personality = preferences.personalityFlow.first()
            val voiceSpeed = preferences.voiceSpeedFlow.first()
            val voicePitch = preferences.voicePitchFlow.first()
            val voiceLang = preferences.voiceLanguageFlow.first()

            textToSpeech?.updateConfig(voiceSpeed, voicePitch, voiceLang)

            // Capture accessibility screen context if available
            val screenContext = try {
                AffyAccessibilityService.instance?.getScreenSummary()
            } catch (e: Exception) {
                null
            }
            val installedAppsSummary = try {
                actionExecutor.getInstalledAppsSummary()
            } catch (e: Exception) {
                ""
            }

            // Get long-term memories
            val memories = try {
                val memoryEntities = database.conversationDao().getAllMemoriesList()
                memoryEntities.map { it.key to it.value }
            } catch (e: Exception) {
                emptyList()
            }

            // Get recent notifications context
            val notificationContext = try {
                val notificationLogs = database.conversationDao().getRecentNotificationLogsList(3)
                if (notificationLogs.isNotEmpty()) {
                    notificationLogs.joinToString("\n") { "[${it.packageName}] ${it.title}: ${it.text}" }
                } else null
            } catch (e: Exception) {
                null
            }

            // Get recent conversation history for memory
            val historyTurns = try {
                val historyEntities = database.conversationDao().getRecentConversationsList(10)
                historyEntities.reversed().map { it.sender to it.message }
            } catch (e: Exception) {
                emptyList()
            }

            // Analyze & Plan with Gemini (generous 60-second window to accommodate complex thinking models)
            AffyDiagnostics.markGeminiRequestStart(model)
            val planResult = kotlinx.coroutines.withTimeoutOrNull(60000L) {
                geminiRepository.analyzeAndPlan(
                    apiKey = apiKey,
                    model = model,
                    userSpeech = userSpeech,
                    personality = personality,
                    screenContext = screenContext,
                    installedAppsSummary = installedAppsSummary,
                    memories = memories,
                    notificationContext = notificationContext,
                    recentConversationContext = historyTurns
                )
            }

            if (currentExecutionToken != token) return@withContext
            val isSuccess = planResult?.isSuccess == true
            AffyDiagnostics.markGeminiResponseReceived(isSuccess)

            val plan = if (planResult != null) {
                planResult.getOrElse {
                    geminiRepository.createOfflineFallbackPlan(userSpeech)
                }
            } else {
                val timeoutClassification = GeminiRepository.GeminiErrorClassification.NetworkTimeout("Gemini timeout exceeded")
                AffyDiagnostics.markGeminiStage(
                    stage = timeoutClassification.diagnosticAction,
                    model = model,
                    details = timeoutClassification.userFriendlyMessage,
                    isSuccess = false,
                    failureReason = timeoutClassification.userFriendlyMessage
                )
                geminiRepository.createCategorizedFailurePlan(userSpeech, timeoutClassification)
            }

            if (currentExecutionToken != token) return@withContext

            // Check if plan requires explicit confirmation
            if (plan.requiresConfirmation) {
                val prompt = plan.confirmationPrompt
                    ?: plan.clarificationQuestion
                    ?: "Kya aap ye action confirm karte hain?"

                _uiState.update {
                    it.copy(
                        pendingConfirmationPlan = plan,
                        assistantResponse = prompt,
                        state = AffyState.SPEAKING,
                        currentActionDescription = "Waiting for confirmation..."
                    )
                }

                withContext(Dispatchers.Main) {
                    if (currentExecutionToken != token) return@withContext
                    textToSpeech?.speak(prompt) {
                        if (currentExecutionToken != token) return@speak
                        _uiState.update { it.copy(state = AffyState.LISTENING) }
                        speechRecognizer?.startListening()
                    }
                }
                return@withContext
            }

            executePlan(plan, userSpeech, personality, token)
        } catch (e: CancellationException) {
            Log.d("AffyCoordinator", "Pipeline cancelled for token $token")
            throw e
        } catch (e: Throwable) {
            Log.e("AffyCoordinator", "Error in processAgentPipeline: ${e.message}", e)
            if (currentExecutionToken == token) {
                val fallbackPlan = geminiRepository.createOfflineFallbackPlan(userSpeech)
                val fallbackSpeech = personalityEngine.formatResponse(fallbackPlan.speechResponse, true, "HINDI_URDU_CASUAL")
                _uiState.update {
                    it.copy(
                        assistantResponse = fallbackSpeech,
                        state = AffyState.SPEAKING,
                        currentActionDescription = ""
                    )
                }
                withContext(Dispatchers.Main) {
                    if (currentExecutionToken == token) {
                        textToSpeech?.speak(fallbackSpeech) {
                            if (currentExecutionToken == token) {
                                startContinuousListeningWindow()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun executePlan(
        plan: AffyPlan,
        originalSpeech: String,
        personality: String,
        token: Long = currentExecutionToken
    ) {
        if (currentExecutionToken != token) return

        try {
            // If plan has actions, delegate to the structured Autonomous Task Engine
            if (plan.actions.isNotEmpty()) {
                val steps = DynamicPlanGenerator.fromAffyPlan(plan)
                val task = AgentTask(
                    goal = originalSpeech,
                    rawPrompt = originalSpeech,
                    steps = steps,
                    lifecycle = AgentTaskLifecycle.PLAN
                )
                executeAgentTask(task, personality, token)
                return
            }

            // Conversational / Greeting / Fast path with no actions
            val finalSpeech = personalityEngine.formatResponse(plan.speechResponse, true, personality).ifBlank {
                "Ji boss, main sun raha hoon."
            }

            // Save conversational response to DB asynchronously
            scope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().insertConversation(
                        ConversationEntity(
                            sender = "AFFY",
                            message = finalSpeech,
                            intent = plan.intent,
                            actionSummary = "",
                            status = "SUCCESS"
                        )
                    )
                } catch (e: Exception) {
                    Log.w("AffyCoordinator", "Failed to insert conversation: ${e.message}")
                }
            }

            updateState {
                it.copy(
                    assistantResponse = finalSpeech,
                    state = AffyState.SPEAKING,
                    currentActionDescription = ""
                )
            }

            if (currentExecutionToken != token) return

            // Voice output
            withContext(Dispatchers.Main) {
                if (currentExecutionToken != token) return@withContext
                textToSpeech?.speak(finalSpeech) {
                    if (currentExecutionToken != token) return@speak
                    if (plan.intent == ActionTypes.STOP_CANCEL || plan.intent == ActionTypes.DEACTIVATE_OVERLAY) {
                        stopListening()
                        com.example.ui.overlay.AffyFloatingOrbManager.hide()
                    } else {
                        // Enter Continuous Conversation Mode (Jarvis Mode)
                        startContinuousListeningWindow()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("AffyCoordinator", "Exception in executePlan: ${e.message}", e)
            if (currentExecutionToken == token) {
                startContinuousListeningWindow()
            }
        }
    }

    suspend fun executeAgentTask(
        task: AgentTask,
        personality: String,
        token: Long = currentExecutionToken
    ) {
        if (currentExecutionToken != token || task.isCancelled) return

        var activeTask = task.copy(
            lifecycle = AgentTaskLifecycle.PLAN,
            updatedAt = System.currentTimeMillis()
        )
        _uiState.update { it.copy(activeAgentTask = activeTask) }
        AffyDiagnostics.logTaskStage(
            stage = "TASK_PLANNED",
            taskId = activeTask.id,
            details = "Total steps: ${activeTask.steps.size} for goal: '${activeTask.goal}'"
        )

        val completedStepSummaries = mutableListOf<String>()
        val executedSteps = activeTask.steps.toMutableList()
        var taskFailed = false
        var failureMessage: String? = null
        var lastVerifiedSpeechOverride: String? = null

        activeTask = activeTask.copy(lifecycle = AgentTaskLifecycle.EXECUTE)

        for (i in executedSteps.indices) {
            if (currentExecutionToken != token || activeTask.isCancelled) {
                activeTask = activeTask.copy(lifecycle = AgentTaskLifecycle.CANCEL, isCancelled = true)
                _uiState.update { it.copy(activeAgentTask = activeTask) }
                AffyDiagnostics.logTaskStage("CANCELLED", taskId = activeTask.id, details = "Task cancelled by user interrupt.")
                return
            }

            var currentStep = executedSteps[i]
            activeTask = activeTask.copy(
                currentStepIndex = i,
                currentAction = currentStep.actionType,
                updatedAt = System.currentTimeMillis()
            )

            // Update context tracking
            lastActionType = currentStep.actionType
            if (!currentStep.target.isNullOrBlank()) {
                lastActiveApp = currentStep.target
            }
            if (!currentStep.text.isNullOrBlank()) {
                lastSearchQuery = currentStep.text
            }

            // Step 1: NOTIFY UI & LOG
            _uiState.update {
                it.copy(
                    state = AffyState.EXECUTING,
                    activeAgentTask = activeTask,
                    currentActionDescription = "Step ${i + 1}/${executedSteps.size}: ${currentStep.description}"
                )
            }
            AffyDiagnostics.logTaskStage(
                stage = "ACTION_EXECUTE",
                taskId = activeTask.id,
                action = currentStep.actionType,
                details = currentStep.description
            )

            // Step 2: EXECUTE WITH BOUNDED RETRY & VERIFICATION
            var stepSuccess = false
            var stepResultSummary = ""
            var attempt = 0
            val maxRetries = 2

            while (attempt <= maxRetries && !stepSuccess) {
                if (currentExecutionToken != token || activeTask.isCancelled) return

                val execResult = actionExecutor.executeAgentStep(currentStep.copy(retryCount = attempt))
                stepResultSummary = execResult.summary

                if (execResult.status == AgentResultStatus.NEEDS_CONFIRMATION) {
                    val prompt = execResult.speechOverride ?: "Kya aap '${currentStep.actionType}' confirm karte hain?"
                    activeTask = activeTask.copy(
                        requiresConfirmation = true,
                        confirmationPrompt = prompt,
                        steps = executedSteps
                    )
                    _uiState.update {
                        it.copy(
                            state = AffyState.SPEAKING,
                            activeAgentTask = activeTask,
                            assistantResponse = prompt,
                            currentActionDescription = "Confirmation required: ${currentStep.actionType}"
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (currentExecutionToken != token) return@withContext
                        textToSpeech?.speak(prompt) {
                            _uiState.update { it.copy(state = AffyState.LISTENING) }
                            speechRecognizer?.startListening()
                        }
                    }
                    return
                }

                if (execResult.status == AgentResultStatus.SUCCESS) {
                    // Step 3: OBSERVE & VERIFY
                    _uiState.update {
                        it.copy(
                            state = AffyState.VERIFYING,
                            currentActionDescription = "Verifying ${currentStep.description}..."
                        )
                    }
                    activeTask = activeTask.copy(lifecycle = AgentTaskLifecycle.OBSERVE)
                    AffyDiagnostics.logTaskStage("OBSERVING", taskId = activeTask.id, action = currentStep.actionType, details = "Observing UI state...")

                    val verification = environmentObserver.observeAndVerify(currentStep)
                    if (verification.isVerified) {
                        stepSuccess = true
                        if (!execResult.speechOverride.isNullOrBlank()) {
                            lastVerifiedSpeechOverride = execResult.speechOverride
                        }
                        currentStep = currentStep.copy(
                            status = StepExecutionStatus.VERIFIED,
                            resultSummary = execResult.summary,
                            verificationObservation = verification.observation
                        )
                        executedSteps[i] = currentStep
                        completedStepSummaries.add(currentStep.description)
                        AffyDiagnostics.logTaskStage(
                            stage = "VERIFIED",
                            taskId = activeTask.id,
                            action = currentStep.actionType,
                            details = verification.observation,
                            isSuccess = true
                        )
                        break
                    } else {
                        // Verification unconfirmed - bounded recovery retry
                        attempt++
                        if (attempt <= maxRetries && execResult.canRecover) {
                            AffyDiagnostics.logTaskStage(
                                stage = "RECOVERING",
                                taskId = activeTask.id,
                                action = currentStep.actionType,
                                details = "Verification unconfirmed (${verification.failureReason}). Retrying attempt $attempt...",
                                isSuccess = false
                            )
                            _uiState.update {
                                it.copy(
                                    currentActionDescription = "Recovering: Retrying ${currentStep.description}..."
                                )
                            }
                            kotlinx.coroutines.delay(600)
                        } else {
                            currentStep = currentStep.copy(
                                status = StepExecutionStatus.FAILED,
                                resultSummary = execResult.summary,
                                failureReason = verification.failureReason ?: "Verification unverified"
                            )
                            executedSteps[i] = currentStep
                            AffyDiagnostics.logTaskStage(
                                stage = "FAILED",
                                taskId = activeTask.id,
                                action = currentStep.actionType,
                                details = "Verification failed: ${verification.failureReason}",
                                isSuccess = false,
                                failureReason = verification.failureReason
                            )
                            break
                        }
                    }
                } else {
                    // Action execution failure - bounded recovery
                    attempt++
                    if (attempt <= maxRetries && execResult.canRecover) {
                        AffyDiagnostics.logTaskStage(
                            stage = "RECOVERING",
                            taskId = activeTask.id,
                            action = currentStep.actionType,
                            details = "Step failed (${execResult.summary}). Attempting recovery $attempt...",
                            isSuccess = false
                        )
                        _uiState.update {
                            it.copy(
                                currentActionDescription = "Recovering step ${i + 1}..."
                            )
                        }
                        kotlinx.coroutines.delay(600)
                    } else {
                        currentStep = currentStep.copy(
                            status = if (execResult.status == AgentResultStatus.UNSUPPORTED) StepExecutionStatus.UNSUPPORTED else StepExecutionStatus.FAILED,
                            resultSummary = execResult.summary,
                            failureReason = execResult.summary
                        )
                        executedSteps[i] = currentStep
                        break
                    }
                }
            }

            if (!stepSuccess) {
                taskFailed = true
                failureMessage = currentStep.failureReason ?: stepResultSummary
                if (currentStep.actionType == ActionTypes.OPEN_APP || currentStep.actionType == ActionTypes.SEARCH_WEB) {
                    break
                }
            }
        }

        // Final Report Generation
        val finalLifecycle = if (!taskFailed) AgentTaskLifecycle.COMPLETE else AgentTaskLifecycle.FAILED
        val rawReport = if (!taskFailed) {
            if (executedSteps.size == 1 && !lastVerifiedSpeechOverride.isNullOrBlank()) {
                lastVerifiedSpeechOverride!!
            } else if (completedStepSummaries.isNotEmpty()) {
                "Boss, ${completedStepSummaries.joinToString(" aur ")} complete kar diya hai."
            } else {
                "Task mukammal ho gaya hai boss."
            }
        } else {
            if (!lastVerifiedSpeechOverride.isNullOrBlank() && lastVerifiedSpeechOverride!!.contains("nahi", ignoreCase = true)) {
                lastVerifiedSpeechOverride!!
            } else {
                "Boss, task poora nahi ho saka. Issue: ${failureMessage ?: "Action verify nahi ho saka"}."
            }
        }

        val finalSpeech = personalityEngine.formatResponse(rawReport, !taskFailed, personality)
        AffyDiagnostics.logPipelineFinal(
            status = if (!taskFailed) "SUCCESS" else "FAILED",
            summary = finalSpeech
        )

        activeTask = activeTask.copy(
            lifecycle = finalLifecycle,
            steps = executedSteps,
            currentResult = if (!taskFailed) "SUCCESS" else "FAILED",
            finalReport = finalSpeech,
            updatedAt = System.currentTimeMillis()
        )

        AffyDiagnostics.logTaskStage(
            stage = if (!taskFailed) "COMPLETED" else "FAILED",
            taskId = activeTask.id,
            details = finalSpeech,
            isSuccess = !taskFailed,
            failureReason = failureMessage
        )

        // Save conversation record in DB
        scope.launch(Dispatchers.IO) {
            database.conversationDao().insertConversation(
                ConversationEntity(
                    sender = "AFFY",
                    message = finalSpeech,
                    intent = "AUTONOMOUS_TASK",
                    actionSummary = completedStepSummaries.joinToString("; "),
                    status = if (!taskFailed) "SUCCESS" else "FAILED"
                )
            )
        }

        _uiState.update {
            it.copy(
                state = AffyState.SPEAKING,
                activeAgentTask = activeTask,
                assistantResponse = finalSpeech,
                currentActionDescription = if (!taskFailed) "Task completed" else "Task failed"
            )
        }

        if (currentExecutionToken != token) return

        withContext(Dispatchers.Main) {
            if (currentExecutionToken != token) return@withContext
            textToSpeech?.speak(finalSpeech) {
                if (currentExecutionToken != token) return@speak
                startContinuousListeningWindow()
            }
        }
    }

    private fun startLiveInactivityWatchdog() {
        cancelContinuousWindow()
        continuousWindowJob = scope.launch {
            kotlinx.coroutines.delay(15000L)
            if (_uiState.value.isContinuousActive && (_uiState.value.state == AffyState.LISTENING || _uiState.value.state == AffyState.CONNECTING)) {
                Log.d("AffyCoordinator", "Live session inactivity timeout (15s) elapsed -> Returning UI to Standby (preserving healthy Live session).")
                _uiState.update {
                    it.copy(
                        isContinuousActive = false,
                        isMicActive = false,
                        state = AffyState.IDLE,
                        currentActionDescription = "AFFY is ready"
                    )
                }
                com.example.ui.overlay.AffyFloatingOrbManager.hide()
            }
        }
    }

    private fun startContinuousListeningWindow() {
        cancelContinuousWindow()
        AffyDiagnostics.logConversationStage(
            stage = "SESSION_CONTINUED",
            details = "AFFY completed speaking; opening hands-free listening window",
            isSuccess = true
        )
        _uiState.update {
            it.copy(
                state = AffyState.LISTENING,
                isContinuousActive = true,
                currentActionDescription = "Listening for follow-up..."
            )
        }
        speechRecognizer?.startListening(isFollowUp = true)

        continuousWindowJob = scope.launch {
            // Hands-free continuous session allows natural back-and-forth conversation (15s inactivity window)
            kotlinx.coroutines.delay(15000L)
            if (_uiState.value.isContinuousActive && _uiState.value.state == AffyState.LISTENING) {
                Log.d("AffyCoordinator", "Continuous listening window elapsed -> Returning UI to Standby (preserving healthy Live session).")
                AffyDiagnostics.logConversationStage(
                    stage = "SESSION_TIMEOUT",
                    details = "Hands-free continuous window expired after prolonged silence; preserved Live connection",
                    isSuccess = true
                )
                _uiState.update {
                    it.copy(
                        isContinuousActive = false,
                        isMicActive = false,
                        state = AffyState.IDLE,
                        currentActionDescription = "AFFY is ready"
                    )
                }
                com.example.ui.overlay.AffyFloatingOrbManager.hide()
            }
        }
    }

    private fun tryContextualFollowUp(speech: String): AffyPlan? {
        val lower = speech.lowercase().trim()
        val currentApp = lastActiveApp

        // 1. Follow-up search or play in last active app (e.g. YouTube, Spotify)
        if (!currentApp.isNullOrBlank()) {
            val searchPrefixes = listOf("ab ", "fir ", "phir ", "aur ", "and ")
            var queryText = lower
            for (prefix in searchPrefixes) {
                if (queryText.startsWith(prefix)) {
                    queryText = queryText.removePrefix(prefix).trim()
                }
            }

            val searchVerbs = listOf("search karo", "search kar do", "dhoondo", "chalao", "play karo", "lagao")
            for (verb in searchVerbs) {
                if (queryText.contains(verb)) {
                    val query = queryText.replace(verb, "").replace("par", "").replace("pe", "").trim()
                    if (query.isNotBlank() && query.length >= 2) {
                        return AffyPlan(
                            intent = ActionTypes.MULTI_STEP_APP_COMMAND,
                            speechResponse = "Haan, $currentApp par $query chala raha hoon.",
                            actions = listOf(
                                com.example.data.model.AffyActionItem(
                                    type = ActionTypes.MULTI_STEP_APP_COMMAND,
                                    target = currentApp,
                                    text = query,
                                    value = if (verb.contains("search") || verb.contains("dhoond")) "search" else "play"
                                )
                            )
                        )
                    }
                }
            }

            // 2. "Pehla wala chalao" / "ye wala chalao" / "first wala"
            if (lower.contains("pehla wala") || lower.contains("ye wala") || lower.contains("first wala") ||
                lower.contains("pehli video") || lower.contains("isko chalao")) {
                return AffyPlan(
                    intent = ActionTypes.TAP_LIKE,
                    speechResponse = "Pehla item chala raha hoon.",
                    actions = listOf(
                        com.example.data.model.AffyActionItem(
                            type = ActionTypes.TAP_LIKE,
                            target = currentApp,
                            text = "first_result"
                        )
                    )
                )
            }
        }

        return null
    }

    private suspend fun executeToolFromLiveSession(toolCall: com.example.voice.LiveToolCall): String {
        val name = toolCall.functionName
        val args = toolCall.arguments
        Log.i("AffyCoordinator", "Executing Gemini Live requested tool: $name with args $args")
        _uiState.update { it.copy(state = AffyState.EXECUTING, currentActionDescription = "Executing $name...") }

        return try {
            when (name) {
                "sendWhatsAppMessage" -> {
                    val rawRecipient = args["recipient"]?.toString() ?: ""
                    val message = args["message"]?.toString() ?: ""
                    val cleanRec = rawRecipient.trim().lowercase()
                    val isPronoun = cleanRec in listOf("iski", "iska", "uska", "unka", "isko", "inhe", "unhe", "is", "us", "this", "him", "her", "them")
                    val recipient = if (isPronoun) {
                        notificationAssistant.resolveContextualSender(cleanRec)?.sender ?: rawRecipient
                    } else {
                        rawRecipient
                    }
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(
                            type = ActionTypes.SEND_WHATSAPP_MESSAGE,
                            target = recipient,
                            text = message
                        )
                    )
                    if (result.isSuccess) result.summary else "Failed: ${result.summary}"
                }
                "makePhoneCall" -> {
                    val contact = args["contactNameOrNumber"]?.toString()
                        ?: args["contact"]?.toString()
                        ?: args["name"]?.toString()
                        ?: args["phoneNumber"]?.toString()
                        ?: ""
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(
                            type = ActionTypes.CALL_CONTACT,
                            target = contact
                        )
                    )
                    if (result.isSuccess) result.summary else "Failed: ${result.summary}"
                }
                "openApp" -> {
                    val appName = args["appName"]?.toString() ?: ""
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(
                            type = ActionTypes.OPEN_APP,
                            target = appName
                        )
                    )
                    result.summary
                }
                "setAlarm" -> {
                    val rawTime = args["time"]?.toString() ?: ""
                    val label = args["label"]?.toString() ?: ""
                    val cleanTime = if (rawTime.isBlank()) "08:00" else rawTime
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(
                            type = ActionTypes.SET_ALARM,
                            target = cleanTime,
                            text = label
                        )
                    )
                    result.summary
                }
                "setTimer" -> {
                    val rawSeconds = args["seconds"]
                    val seconds = (rawSeconds as? Number)?.toInt()
                        ?: rawSeconds?.toString()?.toDoubleOrNull()?.toInt()
                        ?: rawSeconds?.toString()?.toIntOrNull()
                        ?: 60
                    val label = args["label"]?.toString() ?: "Timer"
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(
                            type = ActionTypes.CREATE_REMINDER,
                            target = seconds.toString(),
                            text = label
                        )
                    )
                    result.summary
                }
                "setDeviceSetting" -> {
                    val setting = args["setting"]?.toString() ?: ""
                    val value = args["value"]?.toString() ?: ""
                    val cleanSetting = setting.trim().lowercase()
                    val type = when {
                        cleanSetting.contains("flashlight") || cleanSetting.contains("torch") -> ActionTypes.TOGGLE_FLASHLIGHT
                        cleanSetting.contains("volume_up") || cleanSetting.contains("volume up") || cleanSetting.contains("awaaz badhao") -> ActionTypes.VOLUME_UP
                        cleanSetting.contains("volume_down") || cleanSetting.contains("volume down") || cleanSetting.contains("awaaz kam") -> ActionTypes.VOLUME_DOWN
                        cleanSetting.contains("volume") || cleanSetting.contains("sound") || cleanSetting.contains("audio") -> ActionTypes.SET_VOLUME
                        cleanSetting.contains("brightness") || cleanSetting.contains("roshni") -> ActionTypes.SET_BRIGHTNESS
                        else -> ActionTypes.OPEN_QUICK_SETTINGS
                    }
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(type = type, target = setting, value = value)
                    )
                    result.summary
                }
                "readNotifications" -> {
                    val filter = args["filter"]?.toString()?.takeIf { it.isNotBlank() } ?: "all"
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(type = ActionTypes.READ_NOTIFICATION, target = filter)
                    )
                    result.summary
                }
                "replyToNotification" -> {
                    val sender = args["sender"]?.toString() ?: ""
                    val message = args["message"]?.toString() ?: ""
                    val (success, summary) = notificationAssistant.executeDirectReply(sender, message)
                    if (success) summary else "Failed: $summary"
                }
                "executeAutonomousTask" -> {
                    val goal = args["goal"]?.toString() ?: ""
                    val steps = DynamicPlanGenerator.createPlanFromPrompt(goal)
                    val task = AgentTask(goal = goal, rawPrompt = goal, steps = steps)
                    val personality = preferences.personalityFlow.first()
                    executeAgentTask(task, personality, currentExecutionToken)
                    "Task executed for: $goal"
                }
                "inspectScreen", "readScreen" -> {
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(type = ActionTypes.READ_SCREEN)
                    )
                    if (result.isSuccess) result.summary else "Failed: ${result.summary}"
                }
                "lockDevice" -> {
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(type = ActionTypes.LOCK_DEVICE)
                    )
                    if (result.isSuccess) result.summary else "Failed: ${result.summary}"
                }
                "analyzeSecurity" -> {
                    val query = args["query"]?.toString() ?: ""
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(type = ActionTypes.ANALYZE_SECURITY, target = query)
                    )
                    if (result.isSuccess) result.summary else "Failed: ${result.summary}"
                }
                "performScreenAction" -> {
                    val action = args["action"]?.toString()?.uppercase() ?: "TAP"
                    val target = args["target"]?.toString()
                    val text = args["text"]?.toString()
                    val actionType = when (action) {
                        "TAP", "CLICK" -> ActionTypes.TAP
                        "TYPE", "TYPE_TEXT" -> ActionTypes.TYPE_TEXT
                        "SCROLL_UP" -> ActionTypes.SCROLL_UP
                        "SCROLL_DOWN", "SCROLL" -> ActionTypes.SCROLL_DOWN
                        "LIKE" -> ActionTypes.LIKE_POST
                        "COMMENT" -> ActionTypes.COMMENT_ON_POST
                        "SHARE" -> ActionTypes.SHARE_POST
                        "BACK" -> ActionTypes.GO_BACK
                        "HOME" -> ActionTypes.GO_HOME
                        "SCREENSHOT" -> ActionTypes.TAKE_SCREENSHOT
                        "LOCK" -> ActionTypes.LOCK_DEVICE
                        else -> ActionTypes.TAP
                    }
                    val result = actionExecutor.executeAction(
                        com.example.data.model.AffyActionItem(
                            type = actionType,
                            target = target,
                            text = text
                        )
                    )
                    if (result.isSuccess) result.summary else "Failed: ${result.summary}"
                }
                "saveMemory", "rememberFact" -> {
                    val fact = args["fact"]?.toString()
                        ?: args["text"]?.toString()
                        ?: args["memory"]?.toString()
                        ?: ""
                    val (success, summary) = AffyLongTermMemoryManager.getInstance(context).rememberFact(fact)
                    if (success) summary else "Failed: $summary"
                }
                "recallMemory", "recallFact" -> {
                    val query = args["query"]?.toString()?.takeIf { it.isNotBlank() }
                    AffyLongTermMemoryManager.getInstance(context).recallMemories(query)
                }
                "forgetMemory", "deleteMemory" -> {
                    val query = args["query"]?.toString() ?: args["fact"]?.toString() ?: ""
                    val (success, summary) = AffyLongTermMemoryManager.getInstance(context).forgetFact(query)
                    if (success) summary else "Failed: $summary"
                }
                else -> {
                    "Action $name executed."
                }
            }
        } catch (e: Exception) {
            Log.e("AffyCoordinator", "Error executing tool $name: ${e.message}")
            "Error performing $name: ${e.message}"
        }
    }

    fun setPartialTranscription(partial: String) {
        _uiState.update { it.copy(partialTranscription = partial) }
    }

    fun setAudioAmplitude(amp: Float) {
        _uiState.update { it.copy(audioAmplitude = amp) }
    }

    fun shutdown() {
        audioStreamer?.release()
        audioStreamer = null
        geminiLiveSession?.release()
        geminiLiveSession = null
        batteryMonitor?.stop()
        batteryMonitor = null
        com.example.service.AffyNotificationListenerService.notificationAssistant = null
        com.example.service.AffyNotificationListenerService.gmailManager = null
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }

    companion object {
        @Volatile
        private var instance: AffyAgentCoordinator? = null

        fun getInstance(context: Context): AffyAgentCoordinator {
            val hadInstance = instance != null
            val result = instance ?: synchronized(this) {
                instance ?: AffyAgentCoordinator(
                    context = context.applicationContext
                ).also { instance = it }
            }
            Log.i("AffyCoordinator", "[LIFECYCLE] AffyAgentCoordinator.getInstance() returned instance@${Integer.toHexString(System.identityHashCode(result))}, wasExisting=$hadInstance scopeActive=${result.scope.isActive}")
            return result
        }
    }
}

