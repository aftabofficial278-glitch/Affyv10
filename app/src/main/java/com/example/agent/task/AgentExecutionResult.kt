package com.example.agent.task

enum class FailureType {
    NONE,
    WRONG_TARGET,
    STALE_SCREEN,
    APP_TRANSITION_FAILED,
    DIALOG_INTERRUPTION,
    KEYBOARD_CLOSED,
    ACCESSIBILITY_NODE_UNAVAILABLE,
    APP_CRASH_OR_RELOAD,
    NETWORK_ERROR,
    UNVERIFIED_RESULT,
    USER_INTERVENTION,
    CONFIRMATION_REQUIRED,
    UNSUPPORTED_SECURE_UI
}

data class AgentExecutionResult(
    val status: AgentResultStatus,
    val summary: String,
    val observedState: String? = null,
    val speechOverride: String? = null,
    val canRecover: Boolean = false,
    val recoveryHint: String? = null,
    val failureType: FailureType = FailureType.NONE
)

enum class AgentResultStatus {
    SUCCESS,
    FAILED,
    NEEDS_CONFIRMATION,
    UNSUPPORTED
}

data class VerificationResult(
    val isVerified: Boolean,
    val observation: String,
    val failureReason: String? = null,
    val failureType: FailureType = FailureType.NONE
)

