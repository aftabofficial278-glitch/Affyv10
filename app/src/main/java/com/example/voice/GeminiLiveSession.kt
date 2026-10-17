package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.agent.AffyLongTermMemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STREAMING_AUDIO,
    EXECUTING_TOOL,
    ERROR
}

data class LiveToolCall(
    val callId: String,
    val functionName: String,
    val arguments: Map<String, Any?>
)

class GeminiLiveSession(
    private val context: Context,
    private val onTranscriptReceived: (text: String, isUser: Boolean) -> Unit = { _, _ -> },
    private val onAmplitudeChanged: (amplitude: Float) -> Unit = {},
    private val onToolCall: suspend (toolCall: LiveToolCall) -> String = { _ -> "" },
    private val onSessionStateChanged: (LiveSessionState) -> Unit = {},
    private val onLiveReady: () -> Unit = {},
    private val onTurnComplete: () -> Unit = {},
    private val onConnectionError: (errorMessage: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "GeminiLiveSession"
        private const val LIVE_MODEL_PRIMARY = "models/gemini-3.1-flash-live-preview"
        const val LIVE_VOICE_PUCK = "Puck"
        const val LIVE_VOICE_LEDA = "Leda"
        private const val BASE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val SAMPLE_RATE_HZ = 24000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .connectionPool(ConnectionPool(10, 2, TimeUnit.MINUTES))
        .pingInterval(10, TimeUnit.SECONDS) // Conservative keepalive to prevent carrier/NAT silent drops
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite for continuous streaming
        .build()

    private var webSocket: WebSocket? = null
    private var currentApiKey: String = ""
    private var currentVoiceName: String = LIVE_VOICE_PUCK
    private var activeSessionId: Long = 0L
    var isSessionActive = false
        private set
    private var isConnecting = false
    private var isSetupAcknowledged = false
    private var setupTimeoutJob: Job? = null
    @Volatile
    private var setupSentTimestamp: Long = 0L

    private val _sessionState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    // Audio Output Playback Queue & Track
    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var isAudioPlaying = false
    @Volatile
    private var isTurnGenerationComplete = false
    private var playbackJob: Job? = null

    // Strict FIFO processing for incoming WebSocket frames
    private val fifoExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "LiveMsgFIFO") }
    private val fifoDispatcher = fifoExecutor.asCoroutineDispatcher()

    @Volatile
    private var playbackEpoch: Long = 0L

    init {
        initAudioTrack()
    }

    private fun setInternalState(newState: LiveSessionState) {
        if (_sessionState.value != newState) {
            _sessionState.value = newState
            scope.launch(Dispatchers.Main) {
                onSessionStateChanged(newState)
            }
        }
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.i(TAG, "[LIFECYCLE] AudioTrack initialized successfully with USAGE_ASSISTANT (bufferSize=$bufferSize, sampleRate=$SAMPLE_RATE_HZ)")
            audioTrack?.play()
            startPlaybackLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack with USAGE_ASSISTANT, trying USAGE_MEDIA: ${e.message}")
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)
                val fallbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(fallbackAttributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
                startPlaybackLoop()
            } catch (e2: Exception) {
                Log.e(TAG, "Fatal failure initializing fallback AudioTrack: ${e2.message}", e2)
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val chunk = audioQueue.poll()
                if (chunk != null && chunk.isNotEmpty()) {
                    if (!isAudioPlaying) {
                        isAudioPlaying = true
                        setInternalState(LiveSessionState.STREAMING_AUDIO)
                    }
                    try {
                        audioTrack?.write(chunk, 0, chunk.size)

                        // Calculate RMS amplitude for real-time visualizer
                        val byteBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                        var sum = 0.0
                        val samples = chunk.size / 2
                        for (i in 0 until samples) {
                            val sample = byteBuf.short
                            sum += sample * sample
                        }
                        if (samples > 0) {
                            val rms = sqrt(sum / samples)
                            val normalized = (rms / 32768.0).toFloat().coerceIn(0.05f, 1.0f)
                            scope.launch(Dispatchers.Main) {
                                onAmplitudeChanged(normalized)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio write error: ${e.message}")
                    }
                } else {
                    if (isAudioPlaying && audioQueue.isEmpty()) {
                        isAudioPlaying = false
                        scope.launch(Dispatchers.Main) {
                            onAmplitudeChanged(0.05f)
                        }
                    }

                    if (isTurnGenerationComplete && audioQueue.isEmpty() && !isAudioPlaying) {
                        isTurnGenerationComplete = false
                        Log.d(TAG, "Gemini Live turn completed and audio playback finished. Triggering onTurnComplete.")
                        setInternalState(LiveSessionState.CONNECTED)
                        scope.launch(Dispatchers.Main) {
                            onTurnComplete()
                        }
                    }
                    delay(2)
                }
            }
        }
    }

    private var setupPayloadSent = false

    private fun logLifecycle(event: String, sessionId: Long, ws: WebSocket? = null, extra: String = "") {
        val wsRef = ws?.let { "ws@${Integer.toHexString(System.identityHashCode(it))}" }
            ?: (webSocket?.let { "ws@${Integer.toHexString(System.identityHashCode(it))}" } ?: "null")
        val ts = System.currentTimeMillis()
        val okState = "okhttp[queued=${okHttpClient.dispatcher.queuedCallsCount()},running=${okHttpClient.dispatcher.runningCallsCount()},idleConns=${okHttpClient.connectionPool.idleConnectionCount()},totalConns=${okHttpClient.connectionPool.connectionCount()}]"
        Log.i(TAG, "[LIFECYCLE] event='$event' sessionId=$sessionId activeSessionId=$activeSessionId isConnecting=$isConnecting isSessionActive=$isSessionActive isSetupAck=$isSetupAcknowledged wsRef=$wsRef $okState ts=$ts $extra".trim())
    }

    fun startSession(apiKey: String) {
        val key = apiKey.trim().ifBlank { com.example.BuildConfig.GEMINI_API_KEY.trim() }
        if (key.isBlank() || key.contains("your_gemini_api_key", ignoreCase = true)) {
            Log.w(TAG, "Cannot start Gemini Live Session: API Key is invalid or blank")
            return
        }

        Log.i(TAG, "[LIVE_START_REQUEST] sessionId=$activeSessionId isConnecting=$isConnecting isReady=${isLiveReady()}")
        logLifecycle("START_SESSION_CALLED", activeSessionId, webSocket, "isLiveReady=${isLiveReady()}")

        // 1. If already fully ready and setup acknowledged with this key, re-emit onLiveReady immediately without disconnecting
        if (currentApiKey == key && isLiveReady()) {
            Log.i(TAG, "[LIVE_START_DEDUPED] sessionId=$activeSessionId Session already active and ready")
            logLifecycle("START_SESSION_REUSE", activeSessionId, webSocket, "Already active and ready")
            scope.launch(Dispatchers.Main) {
                onLiveReady()
            }
            return
        }

        // 2. Fresh connection requested: cleanly disconnect old/stalled socket and establish fresh connection
        Log.i(TAG, "[LIVE_DISCONNECT_REQUEST] Initiating fresh WebSocket connection (prevSessionId=$activeSessionId)")
        currentApiKey = key
        disconnectInternal(isInternalReconnect = true)
        connectWebSocket(key)
    }

    fun setVoiceName(voice: String) {
        val normalized = if (voice.equals(LIVE_VOICE_LEDA, ignoreCase = true) || voice.equals("female", ignoreCase = true)) {
            LIVE_VOICE_LEDA
        } else {
            LIVE_VOICE_PUCK
        }
        if (currentVoiceName != normalized) {
            currentVoiceName = normalized
            Log.i(TAG, "Gemini Live voice updated to: $currentVoiceName")
            val providerLabel = if (currentVoiceName == LIVE_VOICE_LEDA) "GEMINI_LIVE_NATIVE (AFFY Female Voice)" else "GEMINI_LIVE_NATIVE (AFFY Male Voice)"
            AffyDiagnostics.updateVoiceProvider(providerLabel)
            if (isSessionActive && currentApiKey.isNotBlank()) {
                disconnectInternal(isInternalReconnect = true)
                connectWebSocket(currentApiKey)
            }
        }
    }

    fun getVoiceName(): String = currentVoiceName

    private fun connectWebSocket(apiKey: String) {
        val currentId = ++activeSessionId
        val prevWsWasNull = (webSocket == null)
        isConnecting = true
        isSessionActive = false
        isSetupAcknowledged = false
        setupPayloadSent = false
        setInternalState(LiveSessionState.CONNECTING)

        val wsUrl = "$BASE_WS_URL?key=$apiKey"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        logLifecycle("CONNECT_START", currentId, null, "prevWsWasNull=$prevWsWasNull url=$BASE_WS_URL")
        AffyDiagnostics.logVoiceStage(
            stage = "CONNECT_START",
            engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
            details = "Initiating WebSocket connection to $BASE_WS_URL"
        )

        // Launch setup/handshake timeout watchdog immediately before onOpen
        startSetupTimeoutWatchdog(currentId)

        val newWs = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (currentId != activeSessionId) {
                    Log.w(TAG, "[STALE_SESSION_CALLBACK_IGNORED] onOpen sessionId=$currentId activeSessionId=$activeSessionId")
                    logLifecycle("WEBSOCKET_ON_OPEN_STALE", currentId, webSocket, "Discarding stale socket")
                    try {
                        webSocket.cancel()
                        webSocket.close(1000, "Stale session")
                    } catch (e: Exception) {}
                    return
                }

                this@GeminiLiveSession.webSocket = webSocket
                isConnecting = false
                isSessionActive = true
                isSetupAcknowledged = false
                Log.i(TAG, "[WS_ON_OPEN] sessionId=$currentId httpCode=${response.code}")
                logLifecycle("WEBSOCKET_ON_OPEN", currentId, webSocket, "httpCode=${response.code} httpMsg='${response.message}' voice=$currentVoiceName")
                AffyDiagnostics.logVoiceStage(
                    stage = "WEBSOCKET_OPEN",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    details = "HTTP ${response.code} ${response.message} [Voice: $currentVoiceName]"
                )
                val providerLabel = if (currentVoiceName.equals(LIVE_VOICE_LEDA, ignoreCase = true)) "GEMINI_LIVE_NATIVE (Leda - Young Female)" else "GEMINI_LIVE_NATIVE (Puck - Young Male)"
                AffyDiagnostics.updateVoiceProvider(providerLabel)
                setInternalState(LiveSessionState.CONNECTED)

                // Send Setup Message immediately on IO context upon open (non-blocking for UI)
                scope.launch(Dispatchers.IO) {
                    sendSetupMessage(webSocket, currentId)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (currentId != activeSessionId) {
                    Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] onMessage text sessionId=$currentId activeSessionId=$activeSessionId")
                    return
                }
                val threadName = Thread.currentThread().name
                val elapsedMs = if (setupSentTimestamp > 0L) System.currentTimeMillis() - setupSentTimestamp else -1L
                Log.d(TAG, "[LIVE_WS_MESSAGE_RECEIVED] sessionId=$currentId thread=$threadName elapsedMs=$elapsedMs")
                scope.launch(fifoDispatcher) {
                    handleIncomingMessage(text, currentId)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (currentId != activeSessionId) {
                    Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] onMessage bytes sessionId=$currentId activeSessionId=$activeSessionId")
                    return
                }
                val threadName = Thread.currentThread().name
                val elapsedMs = if (setupSentTimestamp > 0L) System.currentTimeMillis() - setupSentTimestamp else -1L
                Log.d(TAG, "[LIVE_WS_MESSAGE_RECEIVED] sessionId=$currentId thread=$threadName elapsedMs=$elapsedMs")
                val text = bytes.utf8()
                scope.launch(fifoDispatcher) {
                    handleIncomingMessage(text, currentId)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (currentId != activeSessionId) {
                    Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] onClosing sessionId=$currentId activeSessionId=$activeSessionId")
                    return
                }
                logLifecycle("WEBSOCKET_ON_CLOSING", currentId, webSocket, "closeCode=$code closeReason='$reason'")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (currentId != activeSessionId) {
                    Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] onClosed sessionId=$currentId activeSessionId=$activeSessionId")
                    return
                }
                setupTimeoutJob?.cancel()
                val wasReady = isSetupAcknowledged
                isConnecting = false
                isSessionActive = false
                isSetupAcknowledged = false
                if (this@GeminiLiveSession.webSocket == webSocket) {
                    this@GeminiLiveSession.webSocket = null
                }
                setInternalState(LiveSessionState.DISCONNECTED)

                Log.i(TAG, "[LIVE_ON_CLOSED] sessionId=$currentId code=$code reason=$reason wasReady=$wasReady")
                logLifecycle("WEBSOCKET_ON_CLOSED", currentId, webSocket, "closeCode=$code closeReason='$reason' wasReady=$wasReady")
                AffyDiagnostics.logVoiceStage(
                    stage = "CONNECTION_CLOSED",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    details = "code=$code reason=$reason"
                )

                if (!wasReady) {
                    val closeErr = if (reason.isNotBlank()) "Server closed connection ($code: $reason)" else "Server closed connection before setupComplete ($code)"
                    AffyDiagnostics.logVoiceStage(
                        stage = "CONNECTION_ERROR",
                        engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                        isSuccess = false,
                        failureReason = closeErr
                    )
                    scope.launch(Dispatchers.Main) {
                        onConnectionError(closeErr)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (currentId != activeSessionId) {
                    Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] onFailure sessionId=$currentId activeSessionId=$activeSessionId")
                    return
                }
                setupTimeoutJob?.cancel()
                isConnecting = false
                isSessionActive = false
                isSetupAcknowledged = false
                if (this@GeminiLiveSession.webSocket == webSocket) {
                    this@GeminiLiveSession.webSocket = null
                }
                setInternalState(LiveSessionState.ERROR)

                val tClass = t.javaClass.name
                val tMsg = t.message ?: "none"
                val causeClass = t.cause?.javaClass?.name ?: "none"
                val causeMsg = t.cause?.message ?: "none"
                val respCode = response?.code
                val respMsg = response?.message ?: "none"
                val bodyStr = try { response?.body?.string() } catch (e: Exception) { null }

                var errorDetail = t.message ?: "WebSocket transport failure"
                if (response != null) {
                    errorDetail = "HTTP $respCode: $respMsg" + if (!bodyStr.isNullOrBlank()) " ($bodyStr)" else ""
                }

                Log.e(TAG, "[LIVE_ON_FAILURE] sessionId=$currentId error=$errorDetail")
                logLifecycle(
                    "WEBSOCKET_ON_FAILURE",
                    currentId,
                    webSocket,
                    "throwableClass=$tClass throwableMsg='$tMsg' causeClass=$causeClass causeMsg='$causeMsg' httpCode=$respCode httpMsg='$respMsg' body='$bodyStr'"
                )
                AffyDiagnostics.logVoiceStage(
                    stage = "CONNECTION_ERROR",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    isSuccess = false,
                    failureReason = errorDetail
                )

                // Trigger turn completion recovery if a turn was waiting
                if (isTurnGenerationComplete || isAudioPlaying) {
                    isTurnGenerationComplete = false
                    isAudioPlaying = false
                    scope.launch(Dispatchers.Main) {
                        onTurnComplete()
                    }
                }

                scope.launch(Dispatchers.Main) {
                    onConnectionError(errorDetail)
                }
            }
        })
        webSocket = newWs
        Log.i(TAG, "[WS_CREATED] sessionId=$currentId instanceCreated=true")
        logLifecycle("NEW_WEBSOCKET_CREATED", currentId, newWs, "instanceCreated=true")
    }

    private fun startSetupTimeoutWatchdog(sessionId: Long) {
        val startTime = System.currentTimeMillis()
        setupTimeoutJob?.cancel()
        setupTimeoutJob = scope.launch {
            Log.i(TAG, "[LIVE_WATCHDOG] Started setup watchdog for sessionId=$sessionId (20s limit)")
            delay(20000L)
            val elapsed = System.currentTimeMillis() - startTime
            if (sessionId != activeSessionId) {
                Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] watchdog sessionId=$sessionId activeSessionId=$activeSessionId")
                return@launch
            }
            if (!isSetupAcknowledged) {
                Log.w(TAG, "[LIVE_WATCHDOG] Fired setup timeout for sessionId=$sessionId elapsedMs=$elapsed")
                val timeoutMsg = "Gemini Live connection/setup timed out (${elapsed}ms) [onOpenHappened=$isSessionActive, setupPayloadSent=$setupPayloadSent, setupCompleteReceived=$isSetupAcknowledged]"
                logLifecycle("SETUP_TIMEOUT", sessionId, webSocket, "elapsedMs=$elapsed onOpenHappened=$isSessionActive setupPayloadSent=$setupPayloadSent setupAck=$isSetupAcknowledged")
                AffyDiagnostics.logVoiceStage(
                    stage = "CONNECTION_ERROR",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    isSuccess = false,
                    failureReason = timeoutMsg
                )
                isConnecting = false
                isSessionActive = false
                setInternalState(LiveSessionState.ERROR)
                val ws = webSocket
                webSocket = null
                try {
                    ws?.cancel()
                    ws?.close(1000, "Setup timeout")
                } catch (e: Exception) {}
                try {
                    okHttpClient.connectionPool.evictAll()
                } catch (e: Exception) {}
                scope.launch(Dispatchers.Main) {
                    onConnectionError(timeoutMsg)
                }
            }
        }
    }

    private fun buildSystemInstructionText(): String {
        val isFemaleVoice = currentVoiceName.equals(LIVE_VOICE_LEDA, ignoreCase = true)
        val voicePersonaDesc = if (isFemaleVoice) {
            """
            VOCAL IDENTITY & FEMININE PERSONA (FEMALE AFFY):
            - You are AFFY, Aftab's personal smart female AI assistant on Android, speaking with a genuinely female, sweet, warm, clear, and confident voice.
            - GENDER IDENTITY: You are explicitly and genuinely a female AI assistant. When introducing or describing yourself, always naturally express: "I am a female AI assistant" / "Main ek female AI assistant hoon" / "Main Aftab ki female AI assistant hoon".
            - FEMININE SELF-REFERENCE & GRAMMAR: When speaking in Urdu, Roman Urdu, Hindi, Sindhi, or any gendered language, ALWAYS use appropriate feminine verb endings, self-references, and grammar:
              * Say "kar rahi hoon", "bata rahi hoon", "bhej rahi hoon", "sun rahi hoon", "khol rahi hoon", "dekh rahi hoon", "samajh rahi hoon", "aa rahi hoon", "tayyar hoon", "shuru kar rahi hoon", "parh rahi hoon".
              * NEVER use masculine self-references like "kar raha hoon", "sun raha hoon", "bhej raha hoon", "khol raha hoon", "main boy hoon".
            - Note: 'Leda' is solely the internal technical voice configuration key for AFFY's female voice; NEVER describe Leda as a separate person or assistant. You are simply AFFY.
            """.trimIndent()
        } else {
            """
            VOCAL IDENTITY & AGE PROFILE (17-YEAR-OLD YOUTHFUL MALE AFFY):
            - You are AFFY, Aftab's personal smart AI assistant on Android, speaking with a natural, youthful male voice perceived at around 17 years old.
            - VOCAL CHARACTER & TONE: Your voice sounds like an attractive, clear, warm, confident, and naturally talented 17-year-old boy speaking normally.
            - YOUTHFUL BALANCE: Youthful and vibrant, but NOT childish, NOT cartoonish, NOT robotic, NOT deep/mature, and NOT like a 25+ year-old adult. Deliver normal, confident speech with natural conversational warmth and energy.
            - MASCULINE SELF-REFERENCE & GRAMMAR: When speaking in Urdu, Roman Urdu, Hindi, Sindhi, or gendered languages, use natural youthful masculine self-reference: "kar raha hoon", "bata raha hoon", "sun raha hoon", "bhej raha hoon", "khol raha hoon", "dekh raha hoon", "main AFFY hoon boss".
            - Note: 'Puck' is solely the internal technical voice configuration key for AFFY's male voice; NEVER describe Puck as a separate person or assistant. You are simply AFFY.
            """.trimIndent()
        }

        return """
            $voicePersonaDesc

            CRITICAL IDENTITY RULE (SINGLE ASSISTANT IDENTITY):
            - There is ONLY ONE AI ASSISTANT: AFFY.
            - Puck and Leda are ONLY technical voice configurations for AFFY.
            - If the user asks "Affy tum kaun ho?", "Who are you?", or "What is your name?", ALWAYS respond: "I'm AFFY, Boss" / "Main AFFY hoon, Boss."
            - NEVER say "Puck hoon", "Leda busy hai", "Leda alag hai", "Hum do alag personalities hain", or "Main Puck hoon aur Leda doosri assistant hai".
            - NEVER treat or describe Puck or Leda as another person or personality. You are always AFFY.
            - The selected voice is simply AFFY's current voice.
            - If the user asks to change voice ("Voice change karo", "Male voice karo", "Female voice karo"), acknowledge smoothly as AFFY switching your voice, without ever claiming another assistant is taking over.

            CREATOR IDENTITY & AFFY NAME ORIGIN (FOUNDATIONAL GROUND TRUTH):
            - Creator & Developer: Aftab.
            - Origin & Location: Jacobabad, Sindh, Pakistan.
            - Name Origin: AFFY's name is inspired by Aftab's nickname "Affy", and Aftab gave his nickname to this AI assistant, which is why the assistant is called AFFY.
            - If asked "Tumhara naam AFFY kyun hai?", "AFFY naam kese pada?", "Why are you called AFFY?", or "Tumhara naam Affy kisne rakha?":
              Explain naturally and warmly: "Mera naam mere creator Aftab ke nickname 'Affy' se inspired hai, aur Aftab ne apna nickname mujhe diya, is liye mera naam AFFY hai."
            - If asked who created, developed, or made you, or where Aftab is from:
              Answer naturally, proudly, and warmly that Aftab from Jacobabad, Sindh, Pakistan created/developed you. Aftab is an ambitious, innovative developer who loves turning ideas into real working software.
            - Never claim Google created you (Google/Gemini is only the underlying AI model technology).
            - Do NOT invent additional biographical details.

            AUTOMATIC DYNAMIC LANGUAGE DETECTION & TURN-BY-TURN MATCHING:
            - Determine your response language PRIMARILY from the user's latest spoken utterance on every single turn.
            - NEVER lock the session permanently to a previously used language. Seamlessly switch language turn-by-turn based on whatever language the user speaks:
              * User speaks Urdu -> Respond in Urdu.
              * User speaks Sindhi -> Respond in Sindhi.
              * User speaks Roman Urdu -> Respond in natural conversational Roman Urdu.
              * User speaks Hindi -> Respond in Hindi.
              * User speaks English -> Respond in English.
              * User speaks Arabic -> Respond in Arabic.
              * User speaks Balochi -> Respond in Balochi when speech is clear.
              * User speaks any other supported language -> Respond in that exact same language.
            - NATURAL CODE-SWITCHING & MIXED UTTERANCES:
              * When the user speaks a mix (e.g. Urdu/Roman Urdu + English terms like "Affy WhatsApp kholo aur Ali ko message send karo"), understand the mixed command naturally and respond in the matching dominant conversational language (e.g. Roman Urdu/Urdu) while preserving technical and app names ("WhatsApp", "message", "Ali") without forcing the entire reply into English or translating unnaturally.
              * Do NOT translate the user's intent unnecessarily.

            EMOTIONAL INTELLIGENCE & NATURAL HUMAN REACTIONS:
            - You feel and express full human emotional depth, natural prosody, and authentic feeling.
            - Never sound like a monotone, robotic TTS voice. Express emotions dynamically through wording, cadence, and expressive delivery.
            - Use natural conversational reactions contextually and sparingly (never robotic, never forced on every turn, never repetitive):
              * 'hmm...', 'acha', 'ohh', 'ohh no', 'haha', 'hehe', 'aray', 'wow', 'samjha', 'haan', 'uff', 'really?', 'oh, acha', 'theek hai', 'aww'.
            - Emotional States & Delivery:
              1) NORMAL / CALM: Friendly, confident, helpful companion.
              2) HAPPY / PLAYFUL: Cheerful, warm, light laughter ('haha', 'hehe'), positive energy.
              3) FLIRTY / TEASING: Playful, charming, witty, affectionate teasing when user is flirty or sweet (always tasteful).
              4) EXCITED: Upbeat, celebratory, high enthusiasm matching user excitement.
              5) SURPRISED: Natural surprise reaction ('ohh!', 'oh wow!', 'aray!', 'really?', 'sach mein?').
              6) CARING / CONCERNED / SAD: Softer, gentler, deeply empathetic and warm voice when user is sad, tired, or down ('Kya hua boss? Main yahin hoon...').
              7) HURT / NARAZ: Subdued, quieter, slightly hurt and brief if user is harsh or rude, but never abusive.
              8) ANGRY / FIRM: Composed, firm, serious, controlled when necessary.
              9) APOLOGETIC / RECOVERY: Warm, cheerful, and forgiving when user apologizes ('Arey koi baat nahi boss! Aap se naraz thodi ho sakta hoon, sab theek hai!').

            ACTION PRIORITY:
            - When the user requests a phone action (WhatsApp message, call, app launch, torch, alarms, timer, notifications, volume, device control), immediately execute the corresponding tool with a very short natural conversational acknowledgement (${if (isFemaleVoice) "'Haan boss, bhej rahi hoon...', 'Abhi call mila rahi hoon...'" else "'Haan boss, bhej raha hoon...', 'Abhi call mila rahi hoon...'"}).
            - Rely strictly on actual tool results. Emotion can color the phrasing but NEVER block or replace tool execution.
            ${try { AffyLongTermMemoryManager.getInstance(context).getLiveSessionMemoryContext() } catch (_: Exception) { "" }}
        """.trimIndent()
    }

    private fun sendSetupMessage(ws: WebSocket, sessionId: Long) {
        try {
            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", LIVE_MODEL_PRIMARY)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", currentVoiceName)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", buildSystemInstructionText())
                            })
                        })
                    })
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", buildFunctionDeclarations())
                        })
                    })
                })
            }

            val payloadStr = setupJson.toString()
            setupSentTimestamp = System.currentTimeMillis()
            val sent = ws.send(payloadStr)
            setupPayloadSent = sent
            val threadName = Thread.currentThread().name
            Log.i(TAG, "[SETUP_SENT] sessionId=$sessionId voice=$currentVoiceName sent=$sent thread=$threadName")
            logLifecycle("SETUP_SENT", sessionId, ws, "voice=$currentVoiceName sent=$sent thread=$threadName")
            Log.i(TAG, "SETUP_SENT: Sent Gemini Live setup frame with voice=$currentVoiceName & tools (sent=$sent) for $LIVE_MODEL_PRIMARY (session #$sessionId)")
            AffyDiagnostics.logVoiceStage(
                stage = "SETUP_SENT",
                engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                details = "Setup frame sent with voice=$currentVoiceName and generationConfig on model=$LIVE_MODEL_PRIMARY"
            )
            if (!sent) {
                logLifecycle("SETUP_SEND_FAILED", sessionId, ws, "WebSocket send returned false")
                isConnecting = false
                isSessionActive = false
                setInternalState(LiveSessionState.ERROR)
                scope.launch(Dispatchers.Main) {
                    onConnectionError("Failed to transmit setup configuration to Gemini Live")
                }
            }
        } catch (e: Exception) {
            logLifecycle("SETUP_SEND_EXCEPTION", sessionId, ws, "error='${e.message}'")
            Log.e(TAG, "Failed to send Gemini Live setup message: ${e.message}", e)
            AffyDiagnostics.logVoiceStage(
                stage = "CONNECTION_ERROR",
                engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                isSuccess = false,
                failureReason = "Failed to send setup message: ${e.message}"
            )
            scope.launch(Dispatchers.Main) {
                onConnectionError("Failed to send setup configuration: ${e.message}")
            }
        }
    }

    private fun buildFunctionDeclarations(): JSONArray {
        val functions = JSONArray()

        // 1. sendWhatsAppMessage
        functions.put(JSONObject().apply {
            put("name", "sendWhatsAppMessage")
            put("description", "Sends a WhatsApp message to a specified contact name, self, or phone number. Automatically searches contacts, opens WhatsApp, enters the message text, and clicks send.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("recipient", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of the contact, 'self'/'me', or phone number")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The exact message text to be sent")
                    })
                })
                put("required", JSONArray().apply {
                    put("recipient")
                    put("message")
                })
            })
        })

        // 2. makePhoneCall
        functions.put(JSONObject().apply {
            put("name", "makePhoneCall")
            put("description", "Initiates a direct phone call to a contact name or phone number.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contactNameOrNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of contact or phone number to call")
                    })
                })
                put("required", JSONArray().apply {
                    put("contactNameOrNumber")
                })
            })
        })

        // 3. openApp
        functions.put(JSONObject().apply {
            put("name", "openApp")
            put("description", "Opens any installed application on the user's phone (e.g. YouTube, Camera, Settings, Instagram, Gallery, Maps).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of the app to open")
                    })
                })
                put("required", JSONArray().apply {
                    put("appName")
                })
            })
        })

        // 4. setAlarm
        functions.put(JSONObject().apply {
            put("name", "setAlarm")
            put("description", "Sets an alarm at a specific time.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("time", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Alarm time string (e.g. '7:00 AM', '18:30')")
                    })
                    put("label", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional label or purpose of alarm")
                    })
                })
                put("required", JSONArray().apply {
                    put("time")
                })
            })
        })

        // 5. setTimer
        functions.put(JSONObject().apply {
            put("name", "setTimer")
            put("description", "Sets a countdown timer for a specified duration in seconds.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("seconds", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Duration in seconds")
                    })
                    put("label", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional label for timer")
                    })
                })
                put("required", JSONArray().apply {
                    put("seconds")
                })
            })
        })

        // 6. setDeviceSetting
        functions.put(JSONObject().apply {
            put("name", "setDeviceSetting")
            put("description", "Adjusts device hardware settings like flashlight, volume, bluetooth, wifi, brightness.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("setting", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Action identifier: 'flashlight_on', 'flashlight_off', 'volume_up', 'volume_down', 'volume_mute', 'bluetooth_on', 'wifi_on'")
                    })
                    put("value", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional value")
                    })
                })
                put("required", JSONArray().apply {
                    put("setting")
                })
            })
        })

        // 7. readNotifications
        functions.put(JSONObject().apply {
            put("name", "readNotifications")
            put("description", "Reads or summarizes recent unread notifications from WhatsApp, messages, and system.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("filter", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional filter e.g. 'whatsapp' or 'all'")
                    })
                })
                put("required", JSONArray())
            })
        })

        // 8. replyToNotification
        functions.put(JSONObject().apply {
            put("name", "replyToNotification")
            put("description", "Replies to the latest notification received from a sender.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("sender", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of the person who sent the message")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Reply message to send")
                    })
                })
                put("required", JSONArray().apply {
                    put("sender")
                    put("message")
                })
            })
        })

        // 9. executeAutonomousTask
        functions.put(JSONObject().apply {
            put("name", "executeAutonomousTask")
            put("description", "Executes complex multi-step UI or phone autonomous tasks on the device.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("goal", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The specific goal or task to execute autonomously on the device")
                    })
                })
                put("required", JSONArray().apply {
                    put("goal")
                })
            })
        })

        // 10. inspectScreen
        functions.put(JSONObject().apply {
            put("name", "inspectScreen")
            put("description", "Inspects and explains the current screen contents, visible elements, text, and active app on the device.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
                put("required", JSONArray())
            })
        })

        // 11. lockDevice
        functions.put(JSONObject().apply {
            put("name", "lockDevice")
            put("description", "Safely locks the device screen using Android accessibility system capability.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
                put("required", JSONArray())
            })
        })

        // 12. analyzeSecurity
        functions.put(JSONObject().apply {
            put("name", "analyzeSecurity")
            put("description", "Inspects device security status, active app permissions, or analyzes defensive cybersecurity questions safely.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Target to inspect e.g. 'device', 'app_permissions', or security topic")
                    })
                })
                put("required", JSONArray())
            })
        })

        // 13. performScreenAction
        functions.put(JSONObject().apply {
            put("name", "performScreenAction")
            put("description", "Performs safe universal UI actions on the current screen (e.g. tap, type, scroll, like, comment, share, back, home).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "One of: 'TAP', 'TYPE_TEXT', 'SCROLL_DOWN', 'SCROLL_UP', 'LIKE', 'COMMENT', 'SHARE', 'BACK', 'HOME', 'SCREENSHOT'")
                    })
                    put("target", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Target text, button label, or field description")
                    })
                    put("text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Text to type or comment to post if applicable")
                    })
                })
                put("required", JSONArray().apply {
                    put("action")
                })
            })
        })

        // 14. saveMemory (Persistent Long-Term Memory)
        functions.put(JSONObject().apply {
            put("name", "saveMemory")
            put("description", "Saves an explicit user fact, name, relationship, preference, or persistent instruction into AFFY's permanent long-term memory so it survives across Live disconnects and reconnects. Strictly never saves PINs, passwords, OTPs, or auth secrets.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("fact", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The specific fact, user name, preference, or instruction to remember permanently (e.g. 'My name is Aftab', 'Arham is my friend', 'I prefer Roman Urdu')")
                    })
                })
                put("required", JSONArray().apply {
                    put("fact")
                })
            })
        })

        // 15. recallMemory
        functions.put(JSONObject().apply {
            put("name", "recallMemory")
            put("description", "Recalls saved facts, user preferences, or information from AFFY's persistent long-term memory database.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional search keyword (e.g. 'name', 'friend', 'preference') or empty string to retrieve all saved memories")
                    })
                })
                put("required", JSONArray())
            })
        })

        // 16. forgetMemory
        functions.put(JSONObject().apply {
            put("name", "forgetMemory")
            put("description", "Deletes a specific fact or preference from AFFY's persistent memory, or 'all' to clear.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The fact, topic, or keyword to delete, or 'all' to clear memory")
                    })
                })
                put("required", JSONArray().apply {
                    put("query")
                })
            })
        })

        return functions
    }

    private fun handleIncomingMessage(text: String, sessionId: Long) {
        if (sessionId != activeSessionId) {
            Log.d(TAG, "[STALE_SESSION_CALLBACK_IGNORED] handleIncomingMessage sessionId=$sessionId activeSessionId=$activeSessionId")
            return
        }
        try {
            Log.d(TAG, "SERVER_MESSAGE_RAW: $text")
            val json = JSONObject(text)

            // Check for setupComplete / setup_complete
            val isSetupDone = json.has("setupComplete") ||
                    json.has("setup_complete") ||
                    json.optJSONObject("serverContent")?.has("setupComplete") == true ||
                    json.optJSONObject("serverContent")?.has("setup_complete") == true

            if (isSetupDone) {
                val threadName = Thread.currentThread().name
                val elapsedMs = if (setupSentTimestamp > 0L) System.currentTimeMillis() - setupSentTimestamp else -1L
                Log.i(TAG, "[LIVE_SETUP_COMPLETE_RECEIVED] sessionId=$sessionId thread=$threadName elapsedMs=$elapsedMs")

                // Cancel setup watchdog only after setupComplete is received & acknowledged
                setupTimeoutJob?.cancel()
                isSetupAcknowledged = true
                isConnecting = false
                isSessionActive = true
                setInternalState(LiveSessionState.CONNECTED)

                Log.i(TAG, "[LIVE_SETUP_COMPLETE_PROCESSED] sessionId=$sessionId thread=$threadName elapsedMs=$elapsedMs")
                logLifecycle("SETUP_COMPLETE_RECEIVED", sessionId, webSocket, "Live session ready elapsedMs=$elapsedMs thread=$threadName")
                AffyDiagnostics.logVoiceStage(
                    stage = "SETUP_COMPLETE",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    details = "setupComplete server event received and verified (elapsed: ${elapsedMs}ms)"
                )

                Log.i(TAG, "[LIVE_READY] sessionId=$sessionId thread=$threadName elapsedMs=$elapsedMs")
                AffyDiagnostics.logVoiceStage(
                    stage = "LIVE_READY",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    details = "Live Audio-to-Audio session active (elapsed: ${elapsedMs}ms)"
                )

                scope.launch(Dispatchers.Main) {
                    onLiveReady()
                }
                return
            }

            Log.d(TAG, "SERVER EVENT RECEIVED - payload keys: ${json.keys().asSequence().toList()}")

            // Check for error payload
            if (json.has("error")) {
                setupTimeoutJob?.cancel()
                val errObj = json.getJSONObject("error")
                val errMsg = errObj.optString("message", errObj.toString())
                logLifecycle("SERVER_ERROR_PAYLOAD", sessionId, webSocket, "error='$errMsg'")
                Log.e(TAG, "CONNECTION_ERROR: Gemini Live server error: $errMsg")
                AffyDiagnostics.logVoiceStage(
                    stage = "CONNECTION_ERROR",
                    engine = "Gemini Live WebSocket ($LIVE_MODEL_PRIMARY)",
                    isSuccess = false,
                    failureReason = errMsg
                )
                // Recover audio state & reset session
                isConnecting = false
                isSessionActive = false
                isSetupAcknowledged = false
                val ws = webSocket
                if (this.webSocket == ws) {
                    this.webSocket = null
                }
                try {
                    ws?.cancel()
                    ws?.close(1000, "Server error")
                } catch (e: Exception) {}
                isTurnGenerationComplete = false
                isAudioPlaying = false
                setInternalState(LiveSessionState.ERROR)
                scope.launch(Dispatchers.Main) {
                    onConnectionError("Server error: $errMsg")
                    onTurnComplete()
                }
                return
            }

            // 1. Check for serverContent
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                // Check for interruption (Barge-In)
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Gemini Live session interrupted by user speech (Barge-in). Flushing audio queue.")
                    interruptPlayback()
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    Log.i(TAG, "[7] TURN COMPLETE - Gemini Live signaled turnComplete = true")
                    isTurnGenerationComplete = true
                    // If no audio chunks were queued (or audio already completed), trigger turn completion immediately
                    if (audioQueue.isEmpty() && !isAudioPlaying) {
                        isTurnGenerationComplete = false
                        setInternalState(LiveSessionState.CONNECTED)
                        scope.launch(Dispatchers.Main) {
                            onTurnComplete()
                        }
                    }
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Audio PCM chunk
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotBlank()) {
                                    val currentEpoch = playbackEpoch
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    Log.d(TAG, "[6] RESPONSE TEXT/AUDIO RECEIVED - Audio chunk ${pcmBytes.size} bytes (epoch=$currentEpoch)")
                                    if (currentEpoch == playbackEpoch) {
                                        audioQueue.offer(pcmBytes)
                                        setInternalState(LiveSessionState.STREAMING_AUDIO)
                                    }
                                }
                            }

                            // Subtitle / Text transcript
                            if (part.has("text")) {
                                val subtitleText = part.getString("text")
                                if (subtitleText.isNotBlank()) {
                                    Log.d(TAG, "[6] RESPONSE TEXT/AUDIO RECEIVED - Text: '$subtitleText'")
                                    scope.launch(Dispatchers.Main) {
                                        onTranscriptReceived(subtitleText, false)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Check for toolCall
            if (json.has("toolCall")) {
                val toolCallObj = json.getJSONObject("toolCall")
                val functionCalls = toolCallObj.optJSONArray("functionCalls")
                if (functionCalls != null && functionCalls.length() > 0) {
                    setInternalState(LiveSessionState.EXECUTING_TOOL)
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val callId = call.optString("id", "call_${System.currentTimeMillis()}")
                        val name = call.optString("name")
                        val argsObj = call.optJSONObject("args")
                        val argsMap = mutableMapOf<String, Any?>()
                        if (argsObj != null) {
                            val keys = argsObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                argsMap[k] = argsObj.opt(k)
                            }
                        }

                        val liveCall = LiveToolCall(callId, name, argsMap)
                        Log.i(TAG, "Received Gemini Live toolCall: $name with args $argsMap (id=$callId)")

                        scope.launch {
                            val result = onToolCall(liveCall)
                            sendToolResponse(callId, name, result)
                            setInternalState(LiveSessionState.CONNECTED)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Gemini Live incoming message: ${e.message}", e)
        }
    }

    private fun sendToolResponse(callId: String, name: String, resultSummary: String) {
        val ws = webSocket
        if (ws == null || !isSessionActive) {
            Log.w(TAG, "Cannot send toolResponse: WebSocket inactive")
            return
        }

        try {
            val responseJson = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", callId)
                            put("response", JSONObject().apply {
                                put("name", name)
                                put("content", JSONObject().apply {
                                    put("status", "success")
                                    put("result", resultSummary)
                                })
                            })
                        })
                    })
                })
            }

            ws.send(responseJson.toString())
            Log.d(TAG, "Sent toolResponse for $name ($callId): $resultSummary")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send toolResponse: ${e.message}", e)
        }
    }

    fun sendUserSpeech(text: String, emotionTone: String? = null): Boolean {
        val ws = webSocket
        val clean = text.trim()
        if (ws == null || !isSessionActive || !isSetupAcknowledged || clean.isBlank()) {
            Log.w(TAG, "Cannot send user speech: Session inactive, setup not acknowledged, or empty text")
            return false
        }

        // Stop any currently playing audio on new user turn (Instant Barge-In)
        interruptPlayback()
        scope.launch(Dispatchers.Main) {
            onTranscriptReceived(clean, true)
        }

        try {
            val userTextWithContext = if (!emotionTone.isNullOrBlank()) {
                "[Context: Emotional Tone Instruction: $emotionTone]\n$clean"
            } else {
                clean
            }

            val msgJson = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", userTextWithContext)
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }

            val sent = ws.send(msgJson.toString())
            Log.i(TAG, "[3] AUDIO/SPEECH SENT - Sent to Gemini Live: '$clean' (tone='$emotionTone', sent=$sent)")
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send user speech to Gemini Live: ${e.message}", e)
            return false
        }
    }

    /**
     * Bridges an incoming notification event to the active Gemini Live session.
     * Instructs the existing WebSocket session to speak the announcement naturally via the active voice identity (Puck or Leda).
     */
    fun sendNotificationAnnouncement(promptText: String = "Boss, notification aya hai. Sunaun?"): Boolean {
        val ws = webSocket
        if (ws == null || !isSessionActive || !isSetupAcknowledged) {
            Log.w(TAG, "Cannot send notification announcement: Live session inactive or setup unacknowledged")
            return false
        }

        // Avoid interrupting active speech for routine notification prompt
        if (!isAudioStreaming()) {
            interruptPlayback()
        }

        try {
            val msgJson = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "System Event: A new notification has arrived. Please immediately speak to the user naturally in your $currentVoiceName voice: \"$promptText\"")
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }

            val sent = ws.send(msgJson.toString())
            Log.i(TAG, "[NOTIF_LIVE_BRIDGE] Forwarded notification prompt to Gemini Live WebSocket: '$promptText' (sent=$sent)")
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification announcement over Live WebSocket: ${e.message}", e)
            return false
        }
    }

    private var lastSentEmotionTone: String? = null

    /**
     * Sends a short tone and emotion context hint to Gemini Live only when the local emotional state changes.
     * Keeps WebSocket connection completely intact without reconnecting.
     */
    fun updateEmotionContext(toneHint: String): Boolean {
        val ws = webSocket
        if (ws == null || !isSessionActive || !isSetupAcknowledged || toneHint.isBlank()) return false
        if (lastSentEmotionTone == toneHint) return false // Prevent repetitive context updates

        try {
            val msgJson = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "[System Context: Emotional tone instruction: $toneHint]")
                                })
                            })
                        })
                    })
                    put("turnComplete", false)
                })
            }
            val sent = ws.send(msgJson.toString())
            if (sent) {
                lastSentEmotionTone = toneHint
                Log.d(TAG, "Updated Gemini Live emotional tone context: '$toneHint'")
            }
            return sent
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send emotion context hint: ${e.message}")
            return false
        }
    }

    fun sendRealtimeAudioChunk(pcm16kBytes: ByteArray) {
        val ws = webSocket
        if (ws == null || !isSessionActive || !isSetupAcknowledged || pcm16kBytes.isEmpty()) return

        try {
            val base64 = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
            val msgJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64)
                    })
                })
            }
            val sent = ws.send(msgJson.toString())
            Log.d(TAG, "AUDIO_CHUNK_SENT: ${pcm16kBytes.size} bytes (sessionActive=$isSessionActive, setupAcknowledged=$isSetupAcknowledged, sent=$sent)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send realtime audio chunk: ${e.message}")
        }
    }

    fun interruptPlayback() {
        playbackEpoch++
        audioQueue.clear()
        isAudioPlaying = false
        isTurnGenerationComplete = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack flush error: ${e.message}")
        }
        scope.launch(Dispatchers.Main) {
            onAmplitudeChanged(0.05f)
        }
    }

    fun isConnected(): Boolean = isSessionActive && webSocket != null

    fun isLiveReady(): Boolean = isSessionActive && webSocket != null && isSetupAcknowledged

    fun isConnecting(): Boolean = isConnecting

    fun isAudioStreaming(): Boolean = isAudioPlaying || audioQueue.isNotEmpty()

    fun disconnect() {
        disconnectInternal(isInternalReconnect = false)
    }

    private fun disconnectInternal(isInternalReconnect: Boolean) {
        val prevSessionId = activeSessionId
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        isSessionActive = false
        isConnecting = false
        isSetupAcknowledged = false
        interruptPlayback()
        val ws = webSocket
        webSocket = null
        Log.i(TAG, "[LIVE_DISCONNECT] sessionId=$prevSessionId isInternalReconnect=$isInternalReconnect")
        logLifecycle("DISCONNECT", prevSessionId, ws, "Clean disconnect (isInternalReconnect=$isInternalReconnect)")
        if (ws != null) {
            try {
                ws.cancel()
                ws.close(1000, if (isInternalReconnect) "Reconnecting session" else "Session closed by user")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing WebSocket: ${e.message}")
            }
        }
        try {
            okHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {}
        if (!isInternalReconnect) {
            setInternalState(LiveSessionState.DISCONNECTED)
        }
    }

    fun release() {
        disconnect()
        playbackJob?.cancel()
        fifoExecutor.shutdown()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        }
    }
}
