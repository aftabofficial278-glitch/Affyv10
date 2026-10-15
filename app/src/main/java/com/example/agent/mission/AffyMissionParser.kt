package com.example.agent.mission

import com.example.agent.AffyDiagnostics
import com.example.agent.task.AgentTaskStep
import com.example.agent.task.DynamicPlanGenerator
import com.example.data.model.ActionTypes
import java.util.Locale
import java.util.UUID

/**
 * Mission Parser & Goal Decomposition Engine (Phase 9, 10, 11 & 12).
 * Deconstructs complex, multi-app, conditional requests into structured
 * Missions with topological goal dependencies, conditional branching, and bounded loops.
 */
object AffyMissionParser {

    private const val TAG = "AffyMissionParser"

    /**
     * Determines if a user prompt represents a complex Multi-Goal Mission (across apps, sequential, or conditional).
     */
    fun isMissionPrompt(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT).trim()

        // 1. Explicit multi-goal sequence markers
        val hasSequenceMarker = lower.contains(" phir ") || lower.contains(" then ") ||
                lower.contains(" uske baad ") || lower.contains(" ke baad ") ||
                lower.contains(" after that ") || lower.contains(" next ")

        // 2. Conditional reasoning triggers (Phase 11)
        val hasConditional = lower.contains("agar ") || lower.contains("if ") ||
                lower.contains("warna ") || lower.contains("otherwise ") ||
                lower.contains("jab tak ") || lower.contains("until ")

        // 3. Count distinct app mentions
        val knownApps = listOf(
            "notepad", "notes", "editor", "chatgpt", "gemini", "whatsapp",
            "tiktok", "youtube", "chrome", "browser", "settings", "gallery",
            "telegram", "camera", "calculator"
        )
        val appCount = knownApps.count { lower.contains(it) }

        // 4. Count distinct action verbs
        val actionVerbs = listOf(
            "kholo", "open", "type", "likho", "copy", "paste", "save", "search",
            "bhejo", "send", "call", "lock", "data off", "close", "band karo", "read karo", "batao"
        )
        val verbCount = actionVerbs.count { lower.contains(it) }

        return (hasConditional && verbCount >= 1) || (hasSequenceMarker && verbCount >= 2) || (appCount >= 2 && verbCount >= 2) || verbCount >= 4
    }

    /**
     * Deconstructs a complex prompt into an AffyMission containing ordered, dependent, and conditional AffyGoals.
     */
    fun parseMission(prompt: String): AffyMission {
        val clean = prompt.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val missionId = UUID.randomUUID().toString()

        // Check if there is an IF-ELSE structure
        if (lower.contains("agar ") || lower.contains("if ")) {
            val conditionalGoals = parseConditionalMission(clean, lower, missionId)
            if (conditionalGoals.isNotEmpty()) {
                val mission = AffyMission(
                    missionId = missionId,
                    originalUserRequest = clean,
                    goals = conditionalGoals,
                    missionStatus = MissionStatus.PLANNING,
                    temporaryMissionContext = MissionContext()
                )
                AffyDiagnostics.logTaskStage(
                    stage = "MISSION_PARSED",
                    taskId = missionId,
                    details = "Parsed conditional mission with ${conditionalGoals.size} goals."
                )
                return mission
            }
        }

        // Split into sequential high-level goal clauses
        val goalClauses = splitIntoGoalClauses(clean)
        val goals = mutableListOf<AffyGoal>()

        var previousGoalId: String? = null

        for ((index, clause) in goalClauses.withIndex()) {
            val goalId = "goal_${index + 1}_${UUID.randomUUID().toString().take(4)}"
            val targetApp = detectTargetApp(clause.lowercase(Locale.ROOT))
            val steps = DynamicPlanGenerator.createPlanFromPrompt(clause)

            // Bounded loop check (Phase 11.9: "jab tak result na mile search karo")
            val loop = parseBoundedLoop(clause)

            // Calculate dependencies: sequential goals depend on previous goal
            val dependencies = if (previousGoalId != null) listOf(previousGoalId) else emptyList()
            val expectedOutcome = generateExpectedOutcome(clause, targetApp, steps)

            val goal = AffyGoal(
                goalId = goalId,
                description = clause,
                targetApp = targetApp,
                requiredSteps = steps,
                dependencies = dependencies,
                expectedOutcome = expectedOutcome,
                status = if (dependencies.isEmpty()) GoalStatus.READY else GoalStatus.BLOCKED,
                priority = GoalPriority.NORMAL,
                loop = loop
            )

            goals.add(goal)
            previousGoalId = goalId
        }

        val mission = AffyMission(
            missionId = missionId,
            originalUserRequest = clean,
            goals = goals,
            missionStatus = MissionStatus.PLANNING,
            temporaryMissionContext = MissionContext()
        )

        AffyDiagnostics.logTaskStage(
            stage = "MISSION_PARSED",
            taskId = missionId,
            details = "Decomposed into ${goals.size} goals: ${goals.map { it.description }}"
        )

        return mission
    }

    private fun parseConditionalMission(clean: String, lower: String, missionId: String): List<AffyGoal> {
        val goals = mutableListOf<AffyGoal>()

        // E.g. "WhatsApp kholo. Agar Ali ki chat open hai to message bhejo, warna Ali ki chat search karke open karo aur message bhejo."
        // Or "Affy WhatsApp kholo. Agar Arham ka message aya hai to read karo aur mujhe batao. Agar message nahi aya to Arham ko hello bhejo."
        val sentences = clean.split(Regex("(?i)[.;\n]|(?<=[a-z])\\s+phir\\s+")).map { it.trim() }.filter { it.isNotBlank() }

        var prevGoalId: String? = null

        for ((idx, sentence) in sentences.withIndex()) {
            val sLower = sentence.lowercase(Locale.ROOT)
            val targetApp = detectTargetApp(sLower)

            if (sLower.startsWith("agar ") || sLower.startsWith("if ") || sLower.contains(" agar ") || sLower.contains(" if ")) {
                val condition = parseConditionExpression(sentence)
                val (thenClause, elseClause) = extractThenElseClauses(sentence)

                val thenSteps = DynamicPlanGenerator.createPlanFromPrompt(thenClause)
                val elseSteps = if (elseClause.isNotBlank()) DynamicPlanGenerator.createPlanFromPrompt(elseClause) else emptyList()

                val goalId = "cond_goal_${idx + 1}"
                val goal = AffyGoal(
                    goalId = goalId,
                    description = sentence,
                    targetApp = targetApp,
                    requiredSteps = thenSteps,
                    dependencies = if (prevGoalId != null) listOf(prevGoalId) else emptyList(),
                    expectedOutcome = "Conditional decision evaluated and executed: $sentence",
                    status = if (prevGoalId == null) GoalStatus.READY else GoalStatus.BLOCKED,
                    condition = condition,
                    thenBranchGoals = listOf("then_$goalId"),
                    elseBranchGoals = if (elseSteps.isNotEmpty()) listOf("else_$goalId") else emptyList()
                )
                goals.add(goal)
                prevGoalId = goalId
            } else {
                val goalId = "goal_${idx + 1}_${UUID.randomUUID().toString().take(4)}"
                val steps = DynamicPlanGenerator.createPlanFromPrompt(sentence)
                val goal = AffyGoal(
                    goalId = goalId,
                    description = sentence,
                    targetApp = targetApp,
                    requiredSteps = steps,
                    dependencies = if (prevGoalId != null) listOf(prevGoalId) else emptyList(),
                    expectedOutcome = generateExpectedOutcome(sentence, targetApp, steps),
                    status = if (prevGoalId == null) GoalStatus.READY else GoalStatus.BLOCKED
                )
                goals.add(goal)
                prevGoalId = goalId
            }
        }

        return goals
    }

    private fun parseConditionExpression(text: String): ConditionExpression {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("message aya") || lower.contains("unread message") -> {
                val sender = extractTargetName(lower, "message")
                ConditionExpression(
                    type = ConditionType.MESSAGE_PRESENT,
                    target = sender,
                    rawDescription = "Message present from $sender"
                )
            }

            lower.contains("chat open") || lower.contains("chat already open") || lower.contains("samne wali chat") -> {
                val contact = extractTargetName(lower, "chat")
                ConditionExpression(
                    type = ConditionType.CHAT_IS_OPEN,
                    target = contact,
                    rawDescription = "Chat open with $contact"
                )
            }

            lower.contains("search results") || lower.contains("results hain") -> {
                ConditionExpression(
                    type = ConditionType.SEARCH_RESULTS_EXIST,
                    rawDescription = "Search results exist on screen"
                )
            }

            lower.contains("login hai") || lower.contains("signed in") || lower.contains("logged in") -> {
                ConditionExpression(
                    type = ConditionType.LOGIN_REQUIRED,
                    negated = true,
                    rawDescription = "User is logged in"
                )
            }

            lower.contains("code already pasted") || lower.contains("pasted hai") -> {
                ConditionExpression(
                    type = ConditionType.TEXT_VISIBLE_ON_SCREEN,
                    target = "code",
                    rawDescription = "Code text already visible"
                )
            }

            else -> {
                ConditionExpression(
                    type = ConditionType.CUSTOM_EXPRESSION,
                    rawDescription = text
                )
            }
        }
    }

    private fun extractThenElseClauses(text: String): Pair<String, String> {
        val lower = text.lowercase(Locale.ROOT)
        val thenIndex = when {
            lower.contains(" to ") -> text.indexOf(" to ", ignoreCase = true) + 4
            lower.contains(" then ") -> text.indexOf(" then ", ignoreCase = true) + 6
            else -> 0
        }

        val elseIndex = when {
            lower.contains(" warna ") -> text.indexOf(" warna ", ignoreCase = true)
            lower.contains(" otherwise ") -> text.indexOf(" otherwise ", ignoreCase = true)
            lower.contains(" else ") -> text.indexOf(" else ", ignoreCase = true)
            else -> -1
        }

        return if (elseIndex > thenIndex) {
            val thenPart = text.substring(thenIndex, elseIndex).trim()
            val elsePart = text.substring(elseIndex + 7).trim()
            Pair(thenPart, elsePart)
        } else if (thenIndex > 0) {
            val thenPart = text.substring(thenIndex).trim()
            Pair(thenPart, "")
        } else {
            Pair(text, "")
        }
    }

    private fun parseBoundedLoop(clause: String): ConditionalLoop? {
        val lower = clause.lowercase(Locale.ROOT)
        if (lower.contains("jab tak") || lower.contains("until")) {
            val isSearch = lower.contains("search") || lower.contains("result")
            val isScroll = lower.contains("scroll") || lower.contains("next video")
            val untilCond = if (isSearch) {
                ConditionExpression(ConditionType.SEARCH_RESULTS_EXIST, rawDescription = "Search results found")
            } else {
                ConditionExpression(ConditionType.ELEMENT_EXISTS, target = "video", rawDescription = "Target video item exists")
            }
            val steps = DynamicPlanGenerator.createPlanFromPrompt(clause)
            return ConditionalLoop(
                description = clause,
                untilCondition = untilCond,
                maxIterations = 5,
                loopSteps = steps
            )
        }
        return null
    }

    private fun extractTargetName(lower: String, anchor: String): String {
        val words = lower.split(" ")
        val idx = words.indexOfFirst { it.contains(anchor) }
        return if (idx > 0) words[idx - 1].replace(Regex("[^a-zA-Z0-9]"), "") else ""
    }

    /**
     * Handles dynamic user modification of a running mission.
     */
    fun modifyMission(currentMission: AffyMission, modificationSpeech: String): AffyMission {
        val lower = modificationSpeech.lowercase(Locale.ROOT).trim()
        val updatedGoals = currentMission.goals.map { goal ->
            val descLower = goal.description.lowercase(Locale.ROOT)
            val appLower = goal.targetApp?.lowercase(Locale.ROOT) ?: ""

            val shouldSkip = (appLower.isNotBlank() && lower.contains(appLower) && (lower.contains("chhor do") || lower.contains("skip") || lower.contains("mat karo") || lower.contains("hata do"))) ||
                    (descLower.contains("tiktok") && lower.contains("tiktok") && (lower.contains("chhor") || lower.contains("skip"))) ||
                    (descLower.contains("whatsapp") && lower.contains("whatsapp") && (lower.contains("chhor") || lower.contains("skip")))

            if (shouldSkip) {
                goal.copy(status = GoalStatus.SKIPPED, failureReason = "Skipped by user modification")
            } else {
                goal
            }
        }

        // Recompute readiness: unblock goals whose dependencies are either COMPLETED or SKIPPED
        val recomputedGoals = updatedGoals.map { goal ->
            if (goal.status == GoalStatus.BLOCKED) {
                val allPrereqsSatisfied = goal.dependencies.all { depId ->
                    val depGoal = updatedGoals.firstOrNull { it.goalId == depId }
                    depGoal?.status == GoalStatus.COMPLETED || depGoal?.status == GoalStatus.SKIPPED
                }
                if (allPrereqsSatisfied) goal.copy(status = GoalStatus.READY) else goal
            } else {
                goal
            }
        }

        AffyDiagnostics.logTaskStage(
            stage = "MISSION_MODIFIED",
            taskId = currentMission.missionId,
            details = "Mission modified by user: '$modificationSpeech'"
        )

        return currentMission.copy(
            goals = recomputedGoals,
            lastUpdatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Checks if user speech is a mid-mission modification request.
     */
    fun isMissionModification(speech: String): Boolean {
        val lower = speech.lowercase(Locale.ROOT)
        return (lower.contains("chhor do") || lower.contains("skip karo") || lower.contains("mat karo") ||
                lower.contains("hata do") || lower.contains("baqi continue") || lower.contains("baqi kaam") ||
                lower.contains("sirf whatsapp") || lower.contains("tiktok wala kaam cancel")) &&
                (lower.contains("part") || lower.contains("wala") || lower.contains("step") || lower.contains("kaam") || lower.contains("cancel"))
    }

    private fun splitIntoGoalClauses(text: String): List<String> {
        val delimiters = Regex("(?i)\\s*(?:\\s+phir\\s+|\\s+then\\s+|\\s+uske baad\\s+|\\s+ke baad\\s+|\\s+after that\\s+|\\s+next\\s+)\\s*")
        val rawClauses = text.split(delimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawClauses.size > 1) {
            return rawClauses
        }

        val secondaryDelimiters = Regex("(?i)\\s*(?:;|,\\s*(?=[A-Z]|[a-z]+ kholo|[a-z]+ open)|\\s+aur\\s+(?=[a-z]+ kholo|[a-z]+ open|mobile lock|phone lock|data off))\\s*")
        val splitList = text.split(secondaryDelimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 3 }

        return if (splitList.isNotEmpty()) splitList else listOf(text)
    }

    private fun detectTargetApp(clauseLower: String): String? {
        val knownApps = listOf(
            "notepad", "notes", "editor", "code editor", "chatgpt", "gemini",
            "whatsapp", "tiktok", "youtube", "chrome", "browser", "settings",
            "gallery", "telegram", "camera", "calculator", "instagram"
        )
        for (app in knownApps) {
            if (clauseLower.contains(app)) {
                return app.replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    private fun generateExpectedOutcome(clause: String, targetApp: String?, steps: List<AgentTaskStep>): String {
        return if (!targetApp.isNullOrBlank()) {
            "Goal completed on $targetApp: $clause"
        } else if (steps.isNotEmpty()) {
            steps.last().expectedOutcome ?: "Completed: $clause"
        } else {
            "Completed: $clause"
        }
    }
}
