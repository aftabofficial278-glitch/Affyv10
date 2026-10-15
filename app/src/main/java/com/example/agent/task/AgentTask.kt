package com.example.agent.task

enum class AgentTaskLifecycle {
    UNDERSTAND,
    PLAN,
    EXECUTE,
    OBSERVE,
    VERIFY,
    COMPLETE,
    PAUSE,
    CANCEL,
    FAILED,
    RECOVERING
}

enum class StepExecutionStatus {
    PENDING,
    EXECUTING,
    OBSERVING,
    VERIFIED,
    NEEDS_CONFIRMATION,
    FAILED,
    UNSUPPORTED,
    SKIPPED
}

data class AgentTaskStep(
    val stepIndex: Int,
    val description: String,
    val actionType: String,
    val target: String? = null,
    val text: String? = null,
    val value: String? = null,
    val expectedOutcome: String? = null,
    val status: StepExecutionStatus = StepExecutionStatus.PENDING,
    val resultSummary: String? = null,
    val verificationObservation: String? = null,
    val retryCount: Int = 0,
    val failureReason: String? = null
)

data class AgentTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val goal: String,
    val rawPrompt: String,
    val lifecycle: AgentTaskLifecycle = AgentTaskLifecycle.UNDERSTAND,
    val currentStepIndex: Int = 0,
    val steps: List<AgentTaskStep> = emptyList(),
    val currentAction: String? = null,
    val currentResult: String? = null,
    val verificationStatus: String? = null,
    val errorState: String? = null,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val finalReport: String? = null,
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val currentStep: AgentTaskStep?
        get() = steps.getOrNull(currentStepIndex)

    val totalSteps: Int
        get() = steps.size

    val isTerminal: Boolean
        get() = lifecycle == AgentTaskLifecycle.COMPLETE ||
                lifecycle == AgentTaskLifecycle.FAILED ||
                lifecycle == AgentTaskLifecycle.CANCEL
}
