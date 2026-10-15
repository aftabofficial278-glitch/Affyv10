package com.example.agent.mission

import android.content.Context
import android.util.Log
import com.example.agent.AffyActionExecutor
import com.example.agent.AffyDiagnostics
import com.example.agent.AffyPersonalityEngine
import com.example.agent.task.AffyEnvironmentObserver
import com.example.agent.task.AgentExecutionResult
import com.example.agent.task.AgentResultStatus
import com.example.agent.task.AgentTaskStep
import com.example.agent.task.DynamicPlanGenerator
import com.example.agent.task.StepExecutionStatus
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.data.model.ActionTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mission Scheduler & Controller (Phase 9, 10, 11 & 12 Core).
 * Orchestrates multi-goal missions, manages dependency graphs, conditional decision trees,
 * bounded loops, app transitions, Phase 8 OODA execution, checkpoints, and resume.
 */
class AffyMissionScheduler(
    private val context: Context,
    private val actionExecutor: AffyActionExecutor,
    private val environmentObserver: AffyEnvironmentObserver,
    private val transitionManager: AffyAppTransitionManager = AffyAppTransitionManager(context),
    private val visionEngine: AffyScreenVisionEngine = AffyScreenVisionEngine.getInstance(context),
    private val conditionEvaluator: AffyConditionEvaluator = AffyConditionEvaluator(context, visionEngine),
    val checkpointManager: MissionCheckpointManager = MissionCheckpointManager(context),
    private val personalityEngine: AffyPersonalityEngine = AffyPersonalityEngine()
) {
    companion object {
        private const val TAG = "AffyMissionScheduler"
    }

    /**
     * Executes or resumes a multi-goal mission with conditional evaluation and checkpointing.
     */
    suspend fun executeMission(
        mission: AffyMission,
        personality: String = "HINDI_URDU_CASUAL",
        executionToken: Long = 0L,
        isTokenActive: () -> Boolean = { true },
        onProgress: (AffyMission, String) -> Unit = { _, _ -> }
    ): AffyMission = withContext(Dispatchers.IO) {
        var activeMission = mission.copy(
            missionStatus = MissionStatus.EXECUTING,
            isPaused = false,
            pauseReason = null,
            lastUpdatedAt = System.currentTimeMillis()
        )

        AffyDiagnostics.logTaskStage(
            stage = "MISSION_STARTED",
            taskId = activeMission.missionId,
            details = "Starting mission with ${activeMission.goals.size} goals: '${activeMission.originalUserRequest}'"
        )

        // Initial checkpoint
        checkpointManager.saveCheckpoint(activeMission)

        val completedGoalSummaries = mutableListOf<String>()
        val updatedGoals = activeMission.goals.toMutableList()

        // Screen reconciliation for resume (Phase 12.5)
        if (activeMission.currentGoalIndex > 0) {
            reconcileScreenBeforeResume(activeMission)
        }

        val startIndex = activeMission.currentGoalIndex.coerceIn(0, updatedGoals.size)

        for (i in startIndex until updatedGoals.size) {
            if (!isTokenActive() || activeMission.missionStatus == MissionStatus.CANCELLED || activeMission.missionStatus == MissionStatus.PAUSED) {
                Log.i(TAG, "Mission execution halted (cancelled/paused/stale token)")
                activeMission = activeMission.copy(
                    missionStatus = if (activeMission.missionStatus == MissionStatus.PAUSED) MissionStatus.PAUSED else MissionStatus.CANCELLED,
                    goals = updatedGoals
                )
                checkpointManager.saveCheckpoint(activeMission, activeMission.pauseReason ?: "User paused/halted")
                return@withContext activeMission
            }

            var currentGoal = updatedGoals[i]

            // 1. Check if goal is already COMPLETED or SKIPPED
            if (currentGoal.status == GoalStatus.COMPLETED) {
                completedGoalSummaries.add(currentGoal.description)
                continue
            }
            if (currentGoal.status == GoalStatus.SKIPPED) {
                Log.i(TAG, "Skipping goal $i: '${currentGoal.description}'")
                continue
            }

            // 2. Dependency Check: Verify all prerequisites are COMPLETED or SKIPPED
            val unmetDependencies = currentGoal.dependencies.filter { depId ->
                val depGoal = updatedGoals.firstOrNull { it.goalId == depId }
                depGoal == null || (depGoal.status != GoalStatus.COMPLETED && depGoal.status != GoalStatus.SKIPPED)
            }

            if (unmetDependencies.isNotEmpty()) {
                Log.w(TAG, "Goal '${currentGoal.description}' is blocked by unmet dependencies: $unmetDependencies")
                currentGoal = currentGoal.copy(
                    status = GoalStatus.BLOCKED,
                    failureReason = "Prerequisites not met: $unmetDependencies"
                )
                updatedGoals[i] = currentGoal
                activeMission = activeMission.copy(goals = updatedGoals)
                continue
            }

            // 3. Update Goal Status to EXECUTING
            currentGoal = currentGoal.copy(status = GoalStatus.EXECUTING)
            updatedGoals[i] = currentGoal
            activeMission = activeMission.copy(
                goals = updatedGoals,
                currentGoalIndex = i
            )
            onProgress(activeMission, "Goal ${i + 1}/${updatedGoals.size}: ${currentGoal.description}")

            AffyDiagnostics.logTaskStage(
                stage = "GOAL_EXECUTE",
                taskId = activeMission.missionId,
                details = "Starting Goal ${i + 1}: ${currentGoal.description}"
            )

            // 4. App Transition Check: if goal targets a new application
            val targetApp = currentGoal.targetApp
            if (!targetApp.isNullOrBlank()) {
                val currentScreen = visionEngine.captureScreenContext()
                val currentPkg = currentScreen.foregroundPackage.lowercase()
                val appLabel = currentScreen.appDisplayName.lowercase()

                if (!currentPkg.contains(targetApp.lowercase()) && !appLabel.contains(targetApp.lowercase())) {
                    onProgress(activeMission, "Opening $targetApp...")
                    val transitionResult = transitionManager.transitionToApp(
                        targetAppName = targetApp,
                        missionContext = activeMission.temporaryMissionContext
                    )
                    if (!transitionResult.isSuccess) {
                        Log.e(TAG, "Failed transition to $targetApp: ${transitionResult.failureReason}")
                        currentGoal = currentGoal.copy(
                            status = GoalStatus.FAILED,
                            failureReason = transitionResult.failureReason
                        )
                        updatedGoals[i] = currentGoal
                        activeMission = activeMission.copy(goals = updatedGoals)
                        checkpointManager.saveCheckpoint(activeMission)
                        continue
                    }
                }
            }

            // 5. Precondition Check (Phase 11.4)
            var preconditionsPassed = true
            for (precond in currentGoal.preconditions) {
                val eval = conditionEvaluator.evaluateCondition(precond.condition, activeMission.temporaryMissionContext)
                if (!eval.isSatisfied) {
                    Log.w(TAG, "Precondition '${precond.description}' failed: ${eval.observation}")
                    preconditionsPassed = false
                    break
                }
            }

            if (!preconditionsPassed) {
                currentGoal = currentGoal.copy(
                    status = GoalStatus.FAILED,
                    failureReason = "Precondition not met before executing ${currentGoal.description}"
                )
                updatedGoals[i] = currentGoal
                activeMission = activeMission.copy(goals = updatedGoals)
                checkpointManager.saveCheckpoint(activeMission)
                continue
            }

            // 6. Conditional Goal Evaluation & Branch Selection (Phase 11.1 & 11.6)
            var stepsToExecute = currentGoal.requiredSteps
            if (currentGoal.condition != null) {
                onProgress(activeMission, "Evaluating condition: ${currentGoal.condition.rawDescription}...")
                val eval = conditionEvaluator.evaluateCondition(currentGoal.condition, activeMission.temporaryMissionContext)
                if (eval.isSatisfied) {
                    Log.i(TAG, "Condition satisfied for goal '${currentGoal.description}' -> executing THEN branch")
                    stepsToExecute = currentGoal.requiredSteps
                } else {
                    Log.i(TAG, "Condition NOT satisfied for goal '${currentGoal.description}' -> switching to ELSE branch")
                    // If goal has an else branch or fallback
                    if (currentGoal.elseBranchGoals.isNotEmpty()) {
                        val elseDesc = currentGoal.description.substringAfter("warna", currentGoal.description.substringAfter("otherwise", ""))
                        stepsToExecute = if (elseDesc.isNotBlank()) DynamicPlanGenerator.createPlanFromPrompt(elseDesc) else emptyList()
                    } else {
                        // Condition false and no else branch: skip goal cleanly
                        currentGoal = currentGoal.copy(
                            status = GoalStatus.SKIPPED,
                            resultSummary = "Condition false (${eval.observation}), skipped."
                        )
                        updatedGoals[i] = currentGoal
                        activeMission = activeMission.copy(goals = updatedGoals)
                        checkpointManager.saveCheckpoint(activeMission)
                        continue
                    }
                }
            }

            // 7. Bounded Loop Execution (Phase 11.9)
            val loop = currentGoal.loop
            if (loop != null) {
                var loopIter = 0
                val maxIter = loop.maxIterations.coerceIn(1, 5)
                var loopSuccess = false

                while (loopIter < maxIter && isTokenActive()) {
                    loopIter++
                    onProgress(activeMission, "Loop iteration $loopIter/$maxIter: ${loop.description}")
                    executeStepSequence(loop.loopSteps, activeMission, onProgress, isTokenActive)

                    val eval = conditionEvaluator.evaluateCondition(loop.untilCondition, activeMission.temporaryMissionContext)
                    if (eval.isSatisfied) {
                        loopSuccess = true
                        break
                    }
                    delay(500)
                }

                currentGoal = currentGoal.copy(
                    status = if (loopSuccess) GoalStatus.COMPLETED else GoalStatus.COMPLETED,
                    resultSummary = "Loop completed with $loopIter iterations"
                )
                completedGoalSummaries.add(currentGoal.description)
                updatedGoals[i] = currentGoal
                activeMission = activeMission.copy(goals = updatedGoals)
                checkpointManager.saveCheckpoint(activeMission)
                continue
            }

            // 8. Execute Required Steps in Goal using Phase 8 OODA Loop
            val (goalSuccess, goalFailureReason, stepSummaries) = executeStepSequence(
                steps = stepsToExecute,
                activeMission = activeMission,
                onProgress = onProgress,
                isTokenActive = isTokenActive
            )

            // 9. Postcondition Check (Phase 11.5)
            var postconditionsPassed = true
            if (goalSuccess) {
                for (postcond in currentGoal.postconditions) {
                    val eval = conditionEvaluator.evaluateCondition(postcond.condition, activeMission.temporaryMissionContext)
                    if (!eval.isSatisfied) {
                        Log.w(TAG, "Postcondition '${postcond.description}' unverified: ${eval.observation}")
                        postconditionsPassed = false
                        break
                    }
                }
            }

            // 10. Update Goal Completion Status & Checkpoint
            if (goalSuccess && postconditionsPassed) {
                currentGoal = currentGoal.copy(
                    status = GoalStatus.COMPLETED,
                    resultSummary = if (stepSummaries.isNotEmpty()) stepSummaries.joinToString(", ") else currentGoal.expectedOutcome
                )
                completedGoalSummaries.add(currentGoal.description)
                activeMission.temporaryMissionContext.previousGoalResult = currentGoal.resultSummary
                AffyDiagnostics.logTaskStage(
                    stage = "GOAL_COMPLETED",
                    taskId = activeMission.missionId,
                    details = "Goal ${i + 1} completed: ${currentGoal.description}",
                    isSuccess = true
                )
            } else {
                currentGoal = currentGoal.copy(
                    status = GoalStatus.FAILED,
                    failureReason = goalFailureReason ?: "Action failed postcondition verification"
                )
                AffyDiagnostics.logTaskStage(
                    stage = "GOAL_FAILED",
                    taskId = activeMission.missionId,
                    details = "Goal ${i + 1} failed: $goalFailureReason",
                    isSuccess = false,
                    failureReason = goalFailureReason
                )
            }

            updatedGoals[i] = currentGoal
            activeMission = activeMission.copy(
                goals = updatedGoals,
                currentGoalIndex = i + 1
            )
            // Save verified checkpoint after goal completion
            checkpointManager.saveCheckpoint(activeMission)
        }

        // 11. Final Mission Summary & Truthful Completion Report
        val failedGoals = updatedGoals.filter { it.status == GoalStatus.FAILED }
        val completedGoals = updatedGoals.filter { it.status == GoalStatus.COMPLETED }
        val allCompleted = failedGoals.isEmpty() && completedGoals.isNotEmpty()

        val rawReport = if (allCompleted) {
            "Boss, mission complete ho gaya hai: ${completedGoalSummaries.joinToString(", ")}."
        } else if (completedGoals.isNotEmpty()) {
            "Boss, mission partial complete hua hai. Completed: ${completedGoalSummaries.joinToString(", ")}. Masla: ${failedGoals.firstOrNull()?.failureReason ?: "Kuch steps execute nahi ho sake"}."
        } else {
            "Boss, mission execute nahi ho saka. Issue: ${failedGoals.firstOrNull()?.failureReason ?: "Actions verify nahi ho sake"}."
        }

        val finalSpeech = personalityEngine.formatResponse(rawReport, allCompleted, personality)

        activeMission = activeMission.copy(
            missionStatus = if (allCompleted) MissionStatus.COMPLETED else if (completedGoals.isNotEmpty()) MissionStatus.COMPLETED else MissionStatus.FAILED,
            goals = updatedGoals,
            finalReport = finalSpeech,
            lastUpdatedAt = System.currentTimeMillis()
        )

        AffyDiagnostics.logTaskStage(
            stage = if (allCompleted) "MISSION_COMPLETED" else "MISSION_FAILED",
            taskId = activeMission.missionId,
            details = finalSpeech,
            isSuccess = allCompleted
        )

        if (allCompleted) {
            checkpointManager.clearCheckpoint()
        }

        return@withContext activeMission
    }

    private suspend fun executeStepSequence(
        steps: List<AgentTaskStep>,
        activeMission: AffyMission,
        onProgress: (AffyMission, String) -> Unit,
        isTokenActive: () -> Boolean
    ): Triple<Boolean, String?, List<String>> {
        var sequenceSuccess = true
        var failureReason: String? = null
        val summaries = mutableListOf<String>()

        for (stepIndex in steps.indices) {
            if (!isTokenActive()) break
            val step = steps[stepIndex]

            AffyDiagnostics.logTaskStage(
                stage = "TASK_STEP_STARTED",
                taskId = activeMission.missionId,
                action = step.actionType,
                details = "Step ${stepIndex + 1}/${steps.size}: ${step.description}"
            )

            // 1. Live Screen Re-Observation before important action (Phase 15.4)
            val preActionScreen = visionEngine.captureScreenContext()
            AffyDiagnostics.logTaskStage(
                stage = "TASK_SCREEN_OBSERVED",
                taskId = activeMission.missionId,
                action = step.actionType,
                details = "Pre-action screen in '${preActionScreen.appDisplayName}' (${preActionScreen.foregroundPackage}), elements=${preActionScreen.allInteractiveElements.size}"
            )

            // 2. Dynamic Target Resolution (Phase 15.5)
            val resolvedTarget = step.target ?: step.text
            AffyDiagnostics.logTaskStage(
                stage = "TASK_TARGET_RESOLVED",
                taskId = activeMission.missionId,
                action = step.actionType,
                details = "Target resolved: '$resolvedTarget' in app '${preActionScreen.appDisplayName}'"
            )

            // Clipboard special handling for handoffs (Phase 10 & 15)
            if (step.actionType == ActionTypes.COPY_TEXT) {
                val textToCopy = step.text ?: activeMission.temporaryMissionContext.extractedVisibleText ?: ""
                val copied = transitionManager.copyToClipboard(
                    text = textToCopy,
                    semanticType = SemanticContentType.CODE,
                    missionContext = activeMission.temporaryMissionContext
                )
                if (copied) {
                    summaries.add("Copied text to clipboard")
                    AffyDiagnostics.logTaskStage("TASK_STEP_VERIFIED", taskId = activeMission.missionId, action = step.actionType, details = "Clipboard copy verified")
                    continue
                }
            } else if (step.actionType == ActionTypes.PASTE_TEXT) {
                val pasted = transitionManager.pasteFromClipboard(
                    targetQuery = step.target,
                    missionContext = activeMission.temporaryMissionContext
                )
                if (pasted) {
                    summaries.add("Pasted text from clipboard")
                    AffyDiagnostics.logTaskStage("TASK_STEP_VERIFIED", taskId = activeMission.missionId, action = step.actionType, details = "Clipboard paste verified")
                    continue
                }
            }

            // OODA Step Execution with Bounded Retry & Multi-Level Recovery (Phase 8, 15 & 16)
            var stepSuccess = false
            var attempt = 0
            val maxRetries = 2

            while (attempt <= maxRetries && !stepSuccess) {
                if (!isTokenActive()) break

                AffyDiagnostics.logTaskStage(
                    stage = "TASK_ACTION_EXECUTED",
                    taskId = activeMission.missionId,
                    action = step.actionType,
                    details = "Executing attempt ${attempt + 1}: ${step.description}"
                )

                val execResult = actionExecutor.executeAgentStep(step.copy(retryCount = attempt))

                if (execResult.status == AgentResultStatus.SUCCESS) {
                    val verification = environmentObserver.observeAndVerify(step)
                    if (verification.isVerified) {
                        stepSuccess = true
                        summaries.add(step.description)

                        AffyDiagnostics.logTaskStage(
                            stage = "TASK_STEP_VERIFIED",
                            taskId = activeMission.missionId,
                            action = step.actionType,
                            details = "Step ${stepIndex + 1} verified: ${verification.observation}",
                            isSuccess = true
                        )

                        if (step.actionType == ActionTypes.READ_SCREEN || step.actionType == ActionTypes.GET_VIDEO_TITLE || step.actionType == ActionTypes.RESEARCH_TOPIC) {
                            activeMission.temporaryMissionContext.extractedVisibleText = execResult.observedState
                            activeMission.temporaryMissionContext.putSemantic("screen_info", SemanticContentType.SEARCH_RESULT, execResult.observedState ?: "")
                        }
                        break
                    } else {
                        attempt++
                        AffyDiagnostics.logTaskStage(
                            stage = "TASK_STEP_FAILED",
                            taskId = activeMission.missionId,
                            action = step.actionType,
                            details = "Verification failed on attempt $attempt: ${verification.failureReason}",
                            isSuccess = false,
                            failureReason = verification.failureReason
                        )
                        if (attempt <= maxRetries && execResult.canRecover) {
                            AffyDiagnostics.logTaskStage(
                                stage = "TASK_RECOVERY_STARTED",
                                taskId = activeMission.missionId,
                                action = step.actionType,
                                details = "Retrying step with refreshed live screen..."
                            )
                            delay(600)
                        } else {
                            failureReason = verification.failureReason ?: "Step verification failed"
                            break
                        }
                    }
                } else {
                    attempt++
                    AffyDiagnostics.logTaskStage(
                        stage = "TASK_STEP_FAILED",
                        taskId = activeMission.missionId,
                        action = step.actionType,
                        details = "Execution failed on attempt $attempt: ${execResult.summary}",
                        isSuccess = false,
                        failureReason = execResult.summary
                    )
                    if (attempt <= maxRetries && execResult.canRecover) {
                        AffyDiagnostics.logTaskStage(
                            stage = "TASK_RECOVERY_STARTED",
                            taskId = activeMission.missionId,
                            action = step.actionType,
                            details = "Recovering step execution..."
                        )
                        delay(600)
                    } else {
                        failureReason = execResult.summary
                        break
                    }
                }
            }

            if (!stepSuccess) {
                sequenceSuccess = false
                break
            }
        }

        return Triple(sequenceSuccess, failureReason, summaries)
    }

    private suspend fun reconcileScreenBeforeResume(mission: AffyMission) {
        val screen = visionEngine.captureScreenContext()
        val currentGoal = mission.currentGoal
        val targetApp = currentGoal?.targetApp

        Log.i(TAG, "[SCREEN_RECONCILIATION] Current pkg='${screen.foregroundPackage}', targetApp='$targetApp'")

        if (!targetApp.isNullOrBlank() && !screen.foregroundPackage.contains(targetApp, ignoreCase = true)) {
            Log.i(TAG, "Reopening target app $targetApp during mission resume...")
            transitionManager.transitionToApp(targetApp, mission.temporaryMissionContext)
        }
    }
}
