package com.example.agent.mission

import com.example.agent.task.AgentTaskStep

/**
 * Phase 9, 10, 11 & 12: Multi-Goal Mission Engine, Cross-App Context,
 * Conditional Decision Intelligence, and Mission Checkpoint & Resume.
 */

enum class MissionStatus {
    PENDING,
    PLANNING,
    EXECUTING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    FAILED
}

enum class GoalStatus {
    PENDING,
    READY,
    BLOCKED,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    SKIPPED,
    PAUSED
}

enum class GoalPriority {
    HIGH,
    NORMAL,
    LOW
}

enum class SemanticContentType {
    CODE,
    AI_RESPONSE,
    PERSON,
    MESSAGE,
    URL,
    SEARCH_RESULT,
    TASK_RESULT,
    CLIPBOARD,
    TEXT
}

enum class ConditionType {
    APP_IN_FOREGROUND,
    TEXT_VISIBLE_ON_SCREEN,
    ELEMENT_EXISTS,
    MESSAGE_PRESENT,
    SEARCH_RESULTS_EXIST,
    CLIPBOARD_HAS_CONTENT,
    CHAT_IS_OPEN,
    LOGIN_REQUIRED,
    PERMISSION_DIALOG_VISIBLE,
    PREVIOUS_GOAL_SUCCEEDED,
    CUSTOM_EXPRESSION
}

data class ConditionExpression(
    val type: ConditionType,
    val target: String? = null,
    val expectedValue: String? = null,
    val negated: Boolean = false,
    val rawDescription: String = ""
)

data class GoalPrecondition(
    val description: String,
    val condition: ConditionExpression,
    val recoveryHint: String? = null
)

data class GoalPostcondition(
    val description: String,
    val condition: ConditionExpression,
    val failureMessage: String? = null
)

data class ConditionalLoop(
    val description: String,
    val untilCondition: ConditionExpression,
    val maxIterations: Int = 5, // Strictly bounded safety limit
    var currentIteration: Int = 0,
    val loopSteps: List<AgentTaskStep> = emptyList()
)

data class SemanticItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: SemanticContentType,
    val value: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Temporary Mission Context for cross-app handoffs (Phase 10).
 * Holds transient task data across app transitions without persisting to long-term memory.
 */
data class MissionContext(
    var currentGoalId: String? = null,
    var previousGoalResult: String? = null,
    var selectedApp: String? = null,
    var selectedPerson: String? = null,
    var selectedItem: String? = null,
    var extractedVisibleText: String? = null,
    var clipboardContent: String? = null,
    var generatedText: String? = null,
    var currentScreenSummary: String? = null,
    var externalAiResponse: String? = null,
    var lastActionResult: String? = null,
    var lastVerificationResult: String? = null,
    val semanticData: MutableMap<String, SemanticItem> = mutableMapOf()
) {
    fun putSemantic(key: String, type: SemanticContentType, value: String, metadata: Map<String, String> = emptyMap()) {
        semanticData[key] = SemanticItem(type = type, value = value, metadata = metadata)
    }

    fun getSemantic(key: String): SemanticItem? = semanticData[key]

    fun getSemanticValue(key: String): String? = semanticData[key]?.value

    fun getFirstSemanticOfType(type: SemanticContentType): SemanticItem? {
        return semanticData.values.firstOrNull { it.type == type }
    }

    fun clear() {
        currentGoalId = null
        previousGoalResult = null
        selectedApp = null
        selectedPerson = null
        selectedItem = null
        extractedVisibleText = null
        clipboardContent = null
        generatedText = null
        currentScreenSummary = null
        externalAiResponse = null
        lastActionResult = null
        lastVerificationResult = null
        semanticData.clear()
    }
}

/**
 * An individual goal within a Multi-Goal Mission (Extended with Phase 11 Decision Intelligence).
 */
data class AffyGoal(
    val goalId: String = java.util.UUID.randomUUID().toString(),
    val description: String,
    val targetApp: String? = null,
    val requiredSteps: List<AgentTaskStep> = emptyList(),
    val dependencies: List<String> = emptyList(), // IDs of goals that must be COMPLETED before this goal can start
    val expectedOutcome: String = "",
    val status: GoalStatus = GoalStatus.PENDING,
    val priority: GoalPriority = GoalPriority.NORMAL,
    val retryCount: Int = 0,
    val temporaryContext: Map<String, String> = emptyMap(),
    val failureReason: String? = null,
    val resultSummary: String? = null,
    val canSkipOnFailure: Boolean = false,
    
    // Phase 11: Conditional Decision Intelligence
    val condition: ConditionExpression? = null,
    val thenBranchGoals: List<String> = emptyList(),
    val elseBranchGoals: List<String> = emptyList(),
    val preconditions: List<GoalPrecondition> = emptyList(),
    val postconditions: List<GoalPostcondition> = emptyList(),
    val loop: ConditionalLoop? = null
) {
    val isTerminal: Boolean
        get() = status == GoalStatus.COMPLETED || status == GoalStatus.FAILED || status == GoalStatus.SKIPPED
}

/**
 * Safe Mission Checkpoint (Phase 12).
 * Persists non-sensitive milestone states across Activity recreation or user pause.
 */
data class MissionCheckpoint(
    val missionId: String,
    val originalUserRequest: String,
    val currentGoalIndex: Int,
    val goalStatuses: Map<String, GoalStatus>,
    val completedGoalDescriptions: List<String>,
    val lastVerifiedApp: String? = null,
    val lastVerifiedAction: String? = null,
    val safeContextSummary: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val pauseReason: String? = null
)

/**
 * Multi-Goal Mission (Phase 9, 10, 11 & 12 Core).
 */
data class AffyMission(
    val missionId: String = java.util.UUID.randomUUID().toString(),
    val originalUserRequest: String,
    val goals: List<AffyGoal> = emptyList(),
    val currentGoalIndex: Int = 0,
    val missionStatus: MissionStatus = MissionStatus.PENDING,
    val temporaryMissionContext: MissionContext = MissionContext(),
    val checkpoint: MissionCheckpoint? = null,
    val isPaused: Boolean = false,
    val pauseReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val finalReport: String? = null
) {
    val currentGoal: AffyGoal?
        get() = goals.getOrNull(currentGoalIndex)

    val completedGoals: List<AffyGoal>
        get() = goals.filter { it.status == GoalStatus.COMPLETED }

    val failedGoals: List<AffyGoal>
        get() = goals.filter { it.status == GoalStatus.FAILED }

    val isComplete: Boolean
        get() = missionStatus == MissionStatus.COMPLETED || missionStatus == MissionStatus.FAILED || missionStatus == MissionStatus.CANCELLED

    val progressPercent: Float
        get() = if (goals.isEmpty()) 0f else (completedGoals.size.toFloat() / goals.size.toFloat())
}
