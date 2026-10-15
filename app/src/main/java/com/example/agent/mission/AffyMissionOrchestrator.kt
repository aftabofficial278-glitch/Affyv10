package com.example.agent.mission

import android.content.Context
import android.util.Log
import com.example.agent.AffyActionExecutor
import com.example.agent.AffyDiagnostics
import com.example.agent.AffyPersonalityEngine
import com.example.agent.task.AffyEnvironmentObserver
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.data.model.ActionTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Adaptive Mission Orchestrator (Phase 13 & 14 Core).
 * High-level coordinator that manages the lifecycle of multi-goal missions, dynamic goal graphs,
 * risk-aware action policies, screen-reconciled resumes, mid-mission modifications, and
 * 6-level action adaptation across arbitrary Android apps.
 */
class AffyMissionOrchestrator(
    private val context: Context,
    private val actionExecutor: AffyActionExecutor,
    private val environmentObserver: AffyEnvironmentObserver,
    private val personalityEngine: AffyPersonalityEngine = AffyPersonalityEngine(),
    private val visionEngine: AffyScreenVisionEngine = AffyScreenVisionEngine.getInstance(context),
    private val transitionManager: AffyAppTransitionManager = AffyAppTransitionManager(context, visionEngine),
    private val conditionEvaluator: AffyConditionEvaluator = AffyConditionEvaluator(context, visionEngine),
    val checkpointManager: MissionCheckpointManager = MissionCheckpointManager(context),
    val missionScheduler: AffyMissionScheduler = AffyMissionScheduler(
        context = context,
        actionExecutor = actionExecutor,
        environmentObserver = environmentObserver,
        transitionManager = transitionManager,
        visionEngine = visionEngine,
        conditionEvaluator = conditionEvaluator,
        checkpointManager = checkpointManager,
        personalityEngine = personalityEngine
    )
) {
    companion object {
        private const val TAG = "AffyOrchestrator"

        @Volatile
        private var INSTANCE: AffyMissionOrchestrator? = null

        fun getInstance(
            context: Context,
            actionExecutor: AffyActionExecutor,
            environmentObserver: AffyEnvironmentObserver
        ): AffyMissionOrchestrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AffyMissionOrchestrator(
                    context = context.applicationContext,
                    actionExecutor = actionExecutor,
                    environmentObserver = environmentObserver
                ).also { INSTANCE = it }
            }
        }
    }

    private val _activeMissionFlow = MutableStateFlow<AffyMission?>(null)
    val activeMissionFlow: StateFlow<AffyMission?> = _activeMissionFlow.asStateFlow()

    /**
     * Deconstructs and orchestrates a new multi-goal mission.
     */
    suspend fun orchestrateMission(
        prompt: String,
        personality: String = "HINDI_URDU_CASUAL",
        executionToken: Long = 0L,
        isTokenActive: () -> Boolean = { true },
        onProgress: (AffyMission, String) -> Unit = { _, _ -> }
    ): AffyMission = withContext(Dispatchers.IO) {
        val parsedMission = AffyMissionParser.parseMission(prompt)

        // Phase 13.9: Smart Goal Skipping for already-satisfied goals
        val optimizedGoals = parsedMission.goals.map { goal ->
            if (isGoalAlreadyCompletedOnLiveScreen(goal)) {
                Log.i(TAG, "[SMART_GOAL_SKIPPING] Goal '${goal.description}' is already satisfied on live screen.")
                goal.copy(status = GoalStatus.COMPLETED, resultSummary = "Already satisfied on screen")
            } else {
                goal
            }
        }

        val mission = parsedMission.copy(goals = optimizedGoals)
        _activeMissionFlow.value = mission

        val finalMission = missionScheduler.executeMission(
            mission = mission,
            personality = personality,
            executionToken = executionToken,
            isTokenActive = isTokenActive,
            onProgress = { updated, statusText ->
                _activeMissionFlow.value = updated
                onProgress(updated, statusText)
            }
        )

        _activeMissionFlow.value = finalMission
        finalMission
    }

    /**
     * Modifies active mission mid-flight (Phase 14.10 & 14.11: "TikTok wala part chhor do", "Ab WhatsApp kholo").
     */
    fun modifyActiveMission(modificationPrompt: String): AffyMission? {
        val current = _activeMissionFlow.value ?: return null
        val updated = AffyMissionParser.modifyMission(current, modificationPrompt)
        _activeMissionFlow.value = updated
        checkpointManager.saveCheckpoint(updated, "User modified: $modificationPrompt")
        return updated
    }

    /**
     * Pauses the active mission safely (Phase 12.3).
     */
    fun pauseActiveMission(reason: String = "User paused"): AffyMission? {
        val current = _activeMissionFlow.value ?: return null
        val paused = current.copy(
            isPaused = true,
            missionStatus = MissionStatus.PAUSED,
            pauseReason = reason
        )
        _activeMissionFlow.value = paused
        checkpointManager.saveCheckpoint(paused, reason)
        AffyDiagnostics.logTaskStage("MISSION_PAUSED", taskId = current.missionId, details = reason)
        return paused
    }

    /**
     * Resumes the paused active mission or loads the latest saved checkpoint (Phase 12.4).
     */
    suspend fun resumeMission(
        personality: String = "HINDI_URDU_CASUAL",
        executionToken: Long = 0L,
        isTokenActive: () -> Boolean = { true },
        onProgress: (AffyMission, String) -> Unit = { _, _ -> }
    ): AffyMission? = withContext(Dispatchers.IO) {
        val missionToResume = _activeMissionFlow.value?.copy(isPaused = false) ?: run {
            val cp = checkpointManager.getLatestCheckpoint()
            if (cp != null) {
                AffyMissionParser.parseMission(cp.originalUserRequest).copy(currentGoalIndex = cp.currentGoalIndex)
            } else null
        } ?: return@withContext null

        _activeMissionFlow.value = missionToResume

        val completed = missionScheduler.executeMission(
            mission = missionToResume,
            personality = personality,
            executionToken = executionToken,
            isTokenActive = isTokenActive,
            onProgress = { updated, statusText ->
                _activeMissionFlow.value = updated
                onProgress(updated, statusText)
            }
        )

        _activeMissionFlow.value = completed
        completed
    }

    /**
     * Generates a concise factual progress summary (Phase 14.12: "Kitna kaam hua?").
     */
    fun getProgressSummary(): String {
        val current = _activeMissionFlow.value ?: run {
            val cp = checkpointManager.getLatestCheckpoint()
            if (cp != null) {
                return "Boss, previous mission mein ${cp.completedGoalDescriptions.size} goals complete ho chuke thay."
            }
            return "Boss, abhi koi active mission nahi chal raha."
        }

        val completed = current.completedGoals.size
        val total = current.goals.size
        val currentGoalDesc = current.currentGoal?.description ?: "Task complete"

        return if (completed == 0) {
            "Boss, mission shuru ho chuka hai ($total total goals). Current goal: $currentGoalDesc."
        } else if (current.isComplete) {
            "Boss, mission ke saare $completed goals successfully complete ho gaye hain."
        } else {
            "Boss, $total mein se $completed goals complete hain. Abhi step chal raha hai: $currentGoalDesc."
        }
    }

    /**
     * Smart goal skipping: checks if a goal is already satisfied (e.g. app already open).
     */
    private suspend fun isGoalAlreadyCompletedOnLiveScreen(goal: AffyGoal): Boolean {
        val targetApp = goal.targetApp ?: return false
        val screen = visionEngine.captureScreenContext()
        val currentPkg = screen.foregroundPackage.lowercase()
        val appName = screen.appDisplayName.lowercase()
        val targetLower = targetApp.lowercase()

        val isAppAlreadyForeground = currentPkg.contains(targetLower) || appName.contains(targetLower) || targetLower.contains(appName)
        val isPureOpenAction = goal.requiredSteps.size == 1 && goal.requiredSteps.first().actionType == ActionTypes.OPEN_APP

        return isAppAlreadyForeground && isPureOpenAction
    }
}
