package com.example.data.model

enum class WorkflowStage {
    IDLE,
    GENERATING,
    READY_FOR_DOWNLOAD,
    DOWNLOAD_VERIFIED,
    WALLPAPER_APPLIED,
    FAILED
}

data class ActiveTaskWorkflowContext(
    val activeApp: String = "com.openai.chatgpt",
    val promptText: String = "",
    val stage: WorkflowStage = WorkflowStage.IDLE,
    val lastObservedStatus: String = "",
    val verifiedDownloadedUri: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun isExpired(maxAgeMs: Long = 15 * 60 * 1000L): Boolean {
        return (System.currentTimeMillis() - updatedAtMs) > maxAgeMs
    }
}

data class AgentTaskStep(
    val stepId: String,
    val description: String,
    val action: AffyActionItem,
    var completed: Boolean = false,
    var error: String? = null
)

data class AgentTask(
    val taskId: String,
    val goal: String,
    val steps: List<AgentTaskStep>,
    var currentStepIndex: Int = 0,
    var completed: Boolean = false
)
