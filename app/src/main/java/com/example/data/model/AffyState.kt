package com.example.data.model

enum class AffyState {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    EXECUTING,
    VERIFYING,
    SPEAKING,
    ERROR
}

data class AffyUiState(
    val state: AffyState = AffyState.IDLE,
    val spokenText: String = "",
    val partialTranscription: String = "",
    val assistantResponse: String = "",
    val currentActionDescription: String = "",
    val isMicActive: Boolean = false,
    val audioAmplitude: Float = 0f,
    val isAccessibilityEnabled: Boolean = false,
    val isVoiceServiceRunning: Boolean = false,
    val isConfigured: Boolean = true,
    val showSettings: Boolean = false,
    val showPermissions: Boolean = false,
    val showOnboarding: Boolean = false,
    val pendingConfirmationPlan: AffyPlan? = null,
    val activeAgentTask: com.example.agent.task.AgentTask? = null,
    val activeMission: com.example.agent.mission.AffyMission? = null,
    val isContinuousActive: Boolean = false,
    val errorMessage: String? = null
)

