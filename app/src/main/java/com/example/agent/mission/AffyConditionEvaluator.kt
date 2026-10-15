package com.example.agent.mission

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.agent.vision.ScreenContext
import com.example.agent.vision.ScreenStateType
import com.example.agent.vision.UiElementRole
import com.example.service.AffyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Runtime Condition Evaluator (Phase 11 Core).
 * Evaluates goal conditions, preconditions, and postconditions against fresh live screen state,
 * foreground packages, visible texts, clipboard status, and transient mission context.
 */
class AffyConditionEvaluator(
    private val context: Context,
    private val visionEngine: AffyScreenVisionEngine = AffyScreenVisionEngine.getInstance(context)
) {
    companion object {
        private const val TAG = "AffyConditionEvaluator"
    }

    suspend fun evaluateCondition(
        condition: ConditionExpression,
        missionContext: MissionContext? = null
    ): ConditionEvaluationResult = withContext(Dispatchers.IO) {
        val screen = visionEngine.captureScreenContext()
        val rawResult = evaluateInternal(condition, screen, missionContext)
        val finalSatisfied = if (condition.negated) !rawResult.isSatisfied else rawResult.isSatisfied

        val observation = if (condition.negated) {
            "Negated evaluation [${condition.type}]: ${rawResult.observation} -> Result=${finalSatisfied}"
        } else {
            "Evaluation [${condition.type}]: ${rawResult.observation} -> Result=${finalSatisfied}"
        }

        AffyDiagnostics.logScreenStage(
            stage = "SCREEN_VERIFICATION",
            details = "Condition: ${condition.rawDescription.ifBlank { condition.type.name }} -> isSatisfied=$finalSatisfied",
            isSuccess = finalSatisfied
        )

        ConditionEvaluationResult(
            isSatisfied = finalSatisfied,
            observation = observation,
            confidence = rawResult.confidence,
            observedScreen = screen
        )
    }

    private fun evaluateInternal(
        condition: ConditionExpression,
        screen: ScreenContext,
        missionContext: MissionContext?
    ): ConditionEvaluationResult {
        val target = condition.target?.lowercase(Locale.ROOT)?.trim() ?: ""
        val expected = condition.expectedValue?.lowercase(Locale.ROOT)?.trim() ?: ""

        return when (condition.type) {
            ConditionType.APP_IN_FOREGROUND -> {
                val currentPkg = screen.foregroundPackage.lowercase(Locale.ROOT)
                val currentLabel = screen.appDisplayName.lowercase(Locale.ROOT)
                val matches = currentPkg.contains(target) || currentLabel.contains(target) || target.contains(currentLabel)
                ConditionEvaluationResult(
                    isSatisfied = matches,
                    observation = "Foreground package is '$currentPkg' (Label: '$currentLabel'), target was '$target'",
                    confidence = if (matches) 1.0f else 0.0f
                )
            }

            ConditionType.TEXT_VISIBLE_ON_SCREEN -> {
                val query = if (target.isNotBlank()) target else expected
                val found = screen.visibleTexts.any { it.lowercase(Locale.ROOT).contains(query) }
                ConditionEvaluationResult(
                    isSatisfied = found,
                    observation = if (found) "Text '$query' is visible on screen." else "Text '$query' is not visible among ${screen.visibleTexts.size} screen strings.",
                    confidence = if (found) 0.95f else 0.1f
                )
            }

            ConditionType.ELEMENT_EXISTS -> {
                val query = if (target.isNotBlank()) target else expected
                val hasElement = screen.allInteractiveElements.any { it.text.lowercase(Locale.ROOT).contains(query) || it.contentDescription.lowercase(Locale.ROOT).contains(query) }
                ConditionEvaluationResult(
                    isSatisfied = hasElement,
                    observation = if (hasElement) "Interactive element '$query' exists on screen." else "Interactive element '$query' not found.",
                    confidence = if (hasElement) 0.9f else 0.1f
                )
            }

            ConditionType.CHAT_IS_OPEN -> {
                val isChatState = screen.screenState == ScreenStateType.CHAT_CONVERSATION
                val headerName = screen.visibleTexts.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
                val matches = isChatState && (target.isBlank() || screen.visibleTexts.any { it.lowercase(Locale.ROOT).contains(target) })
                ConditionEvaluationResult(
                    isSatisfied = matches,
                    observation = if (matches) "Chat with '$target' is open." else "Chat with '$target' is not open (Header: '$headerName').",
                    confidence = if (matches) 0.95f else 0.1f
                )
            }

            ConditionType.MESSAGE_PRESENT -> {
                val hasVisibleMessages = screen.screenState == ScreenStateType.CHAT_CONVERSATION && screen.visibleTexts.size >= 2
                val specificSender = target.isNotBlank() && screen.visibleTexts.any { it.lowercase(Locale.ROOT).contains(target) }
                val satisfied = hasVisibleMessages && (target.isBlank() || specificSender)
                ConditionEvaluationResult(
                    isSatisfied = satisfied,
                    observation = if (satisfied) "Messages are visible on screen${if (target.isNotBlank()) " from '$target'" else ""}." else "No new messages found for '$target'.",
                    confidence = if (satisfied) 0.9f else 0.2f
                )
            }

            ConditionType.SEARCH_RESULTS_EXIST -> {
                val hasResults = screen.screenState == ScreenStateType.RESULTS ||
                        screen.allInteractiveElements.any { it.role == UiElementRole.VIDEO_CARD } ||
                        screen.visibleTexts.size >= 4
                ConditionEvaluationResult(
                    isSatisfied = hasResults,
                    observation = if (hasResults) "Search results are populated on screen." else "No search results detected.",
                    confidence = if (hasResults) 0.9f else 0.1f
                )
            }

            ConditionType.CLIPBOARD_HAS_CONTENT -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                val hasClip = (clip != null && clip.itemCount > 0 && !clip.getItemAt(0).text.isNullOrBlank()) ||
                        !missionContext?.clipboardContent.isNullOrBlank()
                ConditionEvaluationResult(
                    isSatisfied = hasClip,
                    observation = if (hasClip) "Clipboard contains active data." else "Clipboard is empty.",
                    confidence = if (hasClip) 1.0f else 0.0f
                )
            }

            ConditionType.LOGIN_REQUIRED -> {
                val isLogin = screen.screenState == ScreenStateType.LOGIN_AUTH ||
                        screen.visibleTexts.any { it.contains("sign in", ignoreCase = true) || it.contains("log in", ignoreCase = true) || it.contains("login", ignoreCase = true) }
                ConditionEvaluationResult(
                    isSatisfied = isLogin,
                    observation = if (isLogin) "Login screen detected." else "No login requirement on screen.",
                    confidence = if (isLogin) 0.95f else 0.05f
                )
            }

            ConditionType.PERMISSION_DIALOG_VISIBLE -> {
                val isPerm = screen.screenState == ScreenStateType.PERMISSION_DIALOG
                ConditionEvaluationResult(
                    isSatisfied = isPerm,
                    observation = if (isPerm) "Permission dialog is visible." else "No permission dialog.",
                    confidence = if (isPerm) 1.0f else 0.0f
                )
            }

            ConditionType.PREVIOUS_GOAL_SUCCEEDED -> {
                val ok = !missionContext?.previousGoalResult.isNullOrBlank()
                ConditionEvaluationResult(
                    isSatisfied = ok,
                    observation = "Previous goal result: ${missionContext?.previousGoalResult}",
                    confidence = if (ok) 1.0f else 0.0f
                )
            }

            ConditionType.CUSTOM_EXPRESSION -> {
                val desc = condition.rawDescription.lowercase(Locale.ROOT)
                val matches = screen.visibleTexts.any { it.lowercase(Locale.ROOT).contains(desc) } ||
                        screen.foregroundPackage.lowercase(Locale.ROOT).contains(desc)
                ConditionEvaluationResult(
                    isSatisfied = matches,
                    observation = "Custom condition '$desc' matched on screen: $matches",
                    confidence = if (matches) 0.8f else 0.2f
                )
            }
        }
    }
}

data class ConditionEvaluationResult(
    val isSatisfied: Boolean,
    val observation: String,
    val confidence: Float = 1.0f,
    val observedScreen: ScreenContext? = null
)
