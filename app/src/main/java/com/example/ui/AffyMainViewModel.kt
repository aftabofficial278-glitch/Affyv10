package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.AffyAgentCoordinator
import com.example.data.datastore.AffyPreferences
import com.example.data.model.AffyState
import com.example.data.model.AffyUiState
import com.example.data.remote.GeminiRepository
import com.example.service.AffyAccessibilityService
import com.example.service.AffyVoiceService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AffyMainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AffyPreferences(application)
    private val geminiRepository = GeminiRepository()
    val agentCoordinator = AffyAgentCoordinator.getInstance(application)

    val coordinatorUiState = agentCoordinator.uiState

    val customApiKey = preferences.userCustomApiKeyFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )

    val isApiKeyConfigured = preferences.isApiKeyConfiguredFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val maskedApiKey = preferences.maskedApiKeyFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ""
    )

    val notificationAssistantEnabled = preferences.notificationAssistantEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val quietModeEnabled = preferences.quietModeEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val selectedModel = preferences.modelFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AffyPreferences.DEFAULT_MODEL
    )

    val personality = preferences.personalityFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AffyPreferences.DEFAULT_PERSONALITY
    )

    val voiceSpeed = preferences.voiceSpeedFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1.05f
    )

    val voicePitch = preferences.voicePitchFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1.10f
    )

    val voiceLanguage = preferences.voiceLanguageFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "auto"
    )

    val liveVoice = preferences.liveVoiceFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AffyPreferences.DEFAULT_LIVE_VOICE
    )

    val wakeWordEnabled = preferences.wakeWordEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val backgroundListening = preferences.backgroundListeningFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    val onboardingCompleted = preferences.onboardingCompletedFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val batteryAwarenessEnabled = preferences.batteryAwarenessEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val batteryAnnounceCharging = preferences.batteryAnnounceChargingFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val batteryAnnounceLow = preferences.batteryAnnounceLowFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val batteryAnnounceFull = preferences.batteryAnnounceFullFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    val batteryLowThreshold = preferences.batteryLowThresholdFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 20
    )

    val isAccessibilityActive = AffyAccessibilityService.isServiceConnected.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<String?>(null)
    val connectionTestResult: StateFlow<String?> = _connectionTestResult.asStateFlow()

    private val _isTestingTts = MutableStateFlow(false)
    val isTestingTts: StateFlow<Boolean> = _isTestingTts.asStateFlow()

    private val _ttsTestResult = MutableStateFlow<String?>(null)
    val ttsTestResult: StateFlow<String?> = _ttsTestResult.asStateFlow()

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

    private val _showPermissionsSheet = MutableStateFlow(false)
    val showPermissionsSheet: StateFlow<Boolean> = _showPermissionsSheet.asStateFlow()

    private val _showOnboardingDialog = MutableStateFlow(false)
    val showOnboardingDialog: StateFlow<Boolean> = _showOnboardingDialog.asStateFlow()

    private val _showAboutAffySheet = MutableStateFlow(false)
    val showAboutAffySheet: StateFlow<Boolean> = _showAboutAffySheet.asStateFlow()

    private val _showAboutDeveloperSheet = MutableStateFlow(false)
    val showAboutDeveloperSheet: StateFlow<Boolean> = _showAboutDeveloperSheet.asStateFlow()

    init {
        android.util.Log.i("AffyLifecycle", "[VIEWMODEL_CREATED] viewModel=${this.hashCode()} coordinator=${agentCoordinator.hashCode()}")
        android.util.Log.i("AffyLifecycle", "[COORDINATOR_INSTANCE_REUSED] coordinator=${agentCoordinator.hashCode()}")
        agentCoordinator.ensureLiveReadyForReopen()
        viewModelScope.launch {
            preferences.onboardingCompletedFlow.collect { completed ->
                if (!completed) {
                    _showOnboardingDialog.value = true
                }
            }
        }
    }

    fun startListening() {
        agentCoordinator.startListening()
    }

    fun stopListening() {
        agentCoordinator.stopListening()
    }

    fun onOrbTapped() {
        if (coordinatorUiState.value.state == AffyState.LISTENING) {
            agentCoordinator.stopListening()
        } else {
            agentCoordinator.startListening()
        }
    }

    fun openSettings() {
        _showSettingsSheet.value = true
    }

    fun closeSettings() {
        _showSettingsSheet.value = false
        _connectionTestResult.value = null
        _ttsTestResult.value = null
    }

    fun openPermissions() {
        _showPermissionsSheet.value = true
    }

    fun closePermissions() {
        _showPermissionsSheet.value = false
    }

    fun openAboutAffy() {
        _showAboutAffySheet.value = true
    }

    fun closeAboutAffy() {
        _showAboutAffySheet.value = false
    }

    fun openAboutDeveloper() {
        _showAboutDeveloperSheet.value = true
    }

    fun closeAboutDeveloper() {
        _showAboutDeveloperSheet.value = false
    }

    fun openWelcomeScreen() {
        _showOnboardingDialog.value = true
    }

    fun completeOnboarding() {
        _showOnboardingDialog.value = false
        viewModelScope.launch {
            preferences.saveOnboardingCompleted(true)
        }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            val trimmed = key.trim()
            if (trimmed.isBlank()) {
                _connectionTestResult.value = "Please enter an API key."
                return@launch
            }
            if (trimmed.length < 10 || trimmed.equals("your_gemini_api_key_here", ignoreCase = true)) {
                _connectionTestResult.value = "Invalid API key format."
                return@launch
            }

            val savedAndVerified = preferences.saveApiKey(trimmed)
            if (savedAndVerified) {
                _connectionTestResult.value = "✓ API key saved and verified locally."
            } else {
                _connectionTestResult.value = "Error: Failed to verify saved API key in local storage."
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            preferences.clearApiKey()
            _connectionTestResult.value = "Custom API key removed."
        }
    }

    private var testConnectionJob: Job? = null

    fun testConnection(key: String, model: String) {
        if (_isTestingConnection.value) return // Prevent concurrent/duplicate taps
        testConnectionJob?.cancel()
        testConnectionJob = viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTestResult.value = null

            try {
                val effectiveKey = if (key.trim().isNotEmpty()) key.trim() else preferences.getEffectiveApiKey()
                if (effectiveKey.isBlank()) {
                    _connectionTestResult.value = "Please enter or save a Gemini API key first."
                    return@launch
                }

                val result = geminiRepository.testConnection(effectiveKey, model)
                _connectionTestResult.value = if (result.isSuccess) {
                    result.getOrNull() ?: "✓ Gemini connection verified successfully!"
                } else {
                    "Connection failed: ${result.exceptionOrNull()?.message ?: "Check network or key"}"
                }
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun testVoiceOutput() {
        _isTestingTts.value = true
        _ttsTestResult.value = "Voice test in progress..."
        val tts = agentCoordinator.ttsEngine
        if (tts == null) {
            _isTestingTts.value = false
            _ttsTestResult.value = "TTS engine is not ready on this device."
            return
        }

        tts.testSpeech("Assalam o Alaikum boss! Main AFFY hoon, aapka young male AI assistant.") { success ->
            _isTestingTts.value = false
            val provider = tts.getCurrentVoiceProvider()
            _ttsTestResult.value = if (success) "✓ $provider: Active & verified!" else "Voice test failed to play."
        }
    }

    fun toggleNotificationAssistant(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveNotificationAssistantEnabled(enabled)
        }
    }

    fun toggleQuietMode(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveQuietModeEnabled(enabled)
        }
    }

    fun selectModel(model: String) {
        viewModelScope.launch {
            preferences.saveModel(model)
        }
    }

    fun selectPersonality(p: String) {
        viewModelScope.launch {
            preferences.savePersonality(p)
        }
    }

    fun saveVoiceSpeed(speed: Float) {
        viewModelScope.launch {
            preferences.saveVoiceSpeed(speed)
        }
    }

    fun saveVoicePitch(pitch: Float) {
        viewModelScope.launch {
            preferences.saveVoicePitch(pitch)
        }
    }

    fun saveVoiceLanguage(lang: String) {
        viewModelScope.launch {
            preferences.saveVoiceLanguage(lang)
        }
    }

    fun selectLiveVoice(voice: String) {
        viewModelScope.launch {
            preferences.saveLiveVoice(voice)
        }
    }

    fun toggleWakeWord(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveWakeWordEnabled(enabled)
        }
    }

    fun toggleBatteryAwareness(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveBatteryAwarenessEnabled(enabled)
        }
    }

    fun toggleBatteryAnnounceCharging(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveBatteryAnnounceCharging(enabled)
        }
    }

    fun toggleBatteryAnnounceLow(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveBatteryAnnounceLow(enabled)
        }
    }

    fun toggleBatteryAnnounceFull(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveBatteryAnnounceFull(enabled)
        }
    }

    fun saveBatteryLowThreshold(threshold: Int) {
        viewModelScope.launch {
            preferences.saveBatteryLowThreshold(threshold)
        }
    }

    fun toggleBackgroundListening(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            preferences.saveBackgroundListening(enabled)
            val intent = Intent(context, AffyVoiceService::class.java).apply {
                action = if (enabled) AffyVoiceService.ACTION_START else AffyVoiceService.ACTION_STOP
            }
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Activity/ViewModel destruction must NOT destroy the application-level coordinator's reusable engines
        if (!AffyVoiceService.isRunning.value && !com.example.ui.overlay.AffyFloatingOrbManager.isShowing.value) {
            agentCoordinator.stopListening()
        }
    }
}
