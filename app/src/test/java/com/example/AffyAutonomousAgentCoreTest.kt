package com.example

import com.example.agent.AffyActionExecutor
import com.example.agent.task.AffyEnvironmentObserver
import com.example.agent.task.AgentExecutionResult
import com.example.agent.task.AgentResultStatus
import com.example.agent.task.AgentTask
import com.example.agent.task.AgentTaskLifecycle
import com.example.agent.task.AgentTaskStep
import com.example.agent.task.DynamicPlanGenerator
import com.example.agent.task.StepExecutionStatus
import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.AffyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffyAutonomousAgentCoreTest {

    @Test
    fun testDynamicPlanGeneration_ChromeSearch() {
        val prompt = "Chrome kholo, Google pe latest AI news search karo aur result batao"
        val steps = DynamicPlanGenerator.createPlanFromPrompt(prompt)

        assertTrue("Should generate at least 2 steps for multi-step task", steps.size >= 2)
        assertEquals(ActionTypes.OPEN_APP, steps[0].actionType)
        assertTrue(steps[0].target.equals("Chrome", ignoreCase = true))

        val searchStep = steps.find { it.actionType == ActionTypes.SEARCH_WEB || it.actionType == ActionTypes.TYPE_TEXT }
        assertNotNull("Should contain search or type step", searchStep)
    }

    @Test
    fun testDynamicPlanGeneration_YouTubePlay() {
        val prompt = "YouTube kholo aur Imran Khan speech search karke play karo"
        val steps = DynamicPlanGenerator.createPlanFromPrompt(prompt)

        assertTrue(steps.isNotEmpty())
        assertEquals(ActionTypes.OPEN_APP, steps[0].actionType)
        assertTrue(steps[0].target.equals("YouTube", ignoreCase = true))
    }

    @Test
    fun testDynamicPlanGeneration_IsMultiStepTaskPrompt() {
        assertTrue(DynamicPlanGenerator.isMultiStepTaskPrompt("Chrome kholo aur Google search karo"))
        assertTrue(DynamicPlanGenerator.isMultiStepTaskPrompt("YouTube open karo then play music"))
        assertTrue(DynamicPlanGenerator.isMultiStepTaskPrompt("WhatsApp kholo karke message bhejo"))
        assertFalse(DynamicPlanGenerator.isMultiStepTaskPrompt("Hello"))
        assertFalse(DynamicPlanGenerator.isMultiStepTaskPrompt("Battery kitni hai?"))
    }

    @Test
    fun testFromAffyPlanConversion() {
        val plan = AffyPlan(
            intent = ActionTypes.MULTI_STEP_APP_COMMAND,
            speechResponse = "Task executing",
            actions = listOf(
                AffyActionItem(type = ActionTypes.OPEN_APP, target = "Chrome"),
                AffyActionItem(type = ActionTypes.SEARCH_WEB, text = "Android Kotlin")
            )
        )

        val steps = DynamicPlanGenerator.fromAffyPlan(plan)
        assertEquals(2, steps.size)
        assertEquals(0, steps[0].stepIndex)
        assertEquals(ActionTypes.OPEN_APP, steps[0].actionType)
        assertEquals("Chrome", steps[0].target)
        assertEquals(1, steps[1].stepIndex)
        assertEquals(ActionTypes.SEARCH_WEB, steps[1].actionType)
        assertEquals("Android Kotlin", steps[1].text)
    }

    @Test
    fun testAgentTaskLifecycle_TerminalStates() {
        val completeTask = AgentTask(
            goal = "Test Goal",
            rawPrompt = "Test Raw",
            lifecycle = AgentTaskLifecycle.COMPLETE
        )
        assertTrue(completeTask.isTerminal)

        val cancelTask = AgentTask(
            goal = "Test Goal",
            rawPrompt = "Test Raw",
            lifecycle = AgentTaskLifecycle.CANCEL
        )
        assertTrue(cancelTask.isTerminal)

        val failedTask = AgentTask(
            goal = "Test Goal",
            rawPrompt = "Test Raw",
            lifecycle = AgentTaskLifecycle.FAILED
        )
        assertTrue(failedTask.isTerminal)

        val executingTask = AgentTask(
            goal = "Test Goal",
            rawPrompt = "Test Raw",
            lifecycle = AgentTaskLifecycle.EXECUTE
        )
        assertFalse(executingTask.isTerminal)
    }

    @Test
    fun testSensitiveActionSafety() {
        assertTrue(AffyActionExecutor.isSensitiveAction(ActionTypes.CLEAR_MEMORY))
        assertTrue(AffyActionExecutor.isSensitiveAction("FACTORY_RESET"))
        assertTrue(AffyActionExecutor.isSensitiveAction("DELETE_ALL_DATA"))
        assertFalse(AffyActionExecutor.isSensitiveAction(ActionTypes.OPEN_APP))
        assertFalse(AffyActionExecutor.isSensitiveAction(ActionTypes.SEARCH_WEB))
        assertFalse(AffyActionExecutor.isSensitiveAction(ActionTypes.VOLUME_UP))
    }
}
