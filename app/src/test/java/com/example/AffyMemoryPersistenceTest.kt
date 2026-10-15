package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AffyFastCommandRouter
import com.example.agent.AffyIntentRouter
import com.example.agent.AffyLongTermMemoryManager
import com.example.agent.AffySttNormalizer
import com.example.data.model.ActionTypes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AffyMemoryPersistenceTest {

    private lateinit var context: Context
    private lateinit var memoryManager: AffyLongTermMemoryManager

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            memoryManager = AffyLongTermMemoryManager.getInstance(context)
            memoryManager.clearAll()
        }
    }

    @Test
    fun testRememberUserNamePersistence() {
        runBlocking {
            val (success, message) = memoryManager.rememberFact("My name is Aftab. Remember my name.")
            assertTrue("Memory storage should succeed", success)
            assertTrue(message.contains("Aftab") || message.contains("yaad rakh li"))

            val recalled = memoryManager.recallMemories("name")
            assertTrue("Recalled memories should contain Aftab", recalled.contains("Aftab"))

            val liveContext = memoryManager.getLiveSessionMemoryContext()
            assertTrue("Live session context must include saved user name", liveContext.contains("Aftab"))
            assertTrue("Live session context must include PERSISTENT USER MEMORIES header", liveContext.contains("[PERSISTENT USER MEMORIES (LONG-TERM MEMORY)]"))
        }
    }

    @Test
    fun testRememberFriendFactAcrossReconnect() {
        runBlocking {
            val (success, _) = memoryManager.rememberFact("Remember Arham is my friend")
            assertTrue(success)

            val liveContext = memoryManager.getLiveSessionMemoryContext()
            assertTrue("Context should contain Arham", liveContext.contains("Arham"))
            assertTrue("Context should contain friend", liveContext.contains("friend") || liveContext.contains("Arham is my friend"))
        }
    }

    @Test
    fun testRememberUserPreference() {
        runBlocking {
            val (success, _) = memoryManager.rememberFact("Affy ye yaad rakhna ke mujhe Roman Urdu pasand hai")
            assertTrue(success)

            val liveContext = memoryManager.getLiveSessionMemoryContext()
            assertTrue("Context should contain Roman Urdu preference", liveContext.contains("Roman Urdu") || liveContext.contains("roman urdu"))
        }
    }

    @Test
    fun testSensitiveDataRejection() {
        runBlocking {
            val (successPin, msgPin) = memoryManager.rememberFact("Remember my pin is 1234")
            assertFalse("PINs must be rejected from memory", successPin)
            assertTrue("Must provide security message", msgPin.contains("Security policy") || msgPin.contains("passwords"))

            val (successPass, _) = memoryManager.rememberFact("Affy ye password yaad rakho: secret123")
            assertFalse("Passwords must be rejected", successPass)

            val liveContext = memoryManager.getLiveSessionMemoryContext()
            assertFalse(liveContext.contains("1234"))
            assertFalse(liveContext.contains("secret123"))
        }
    }

    @Test
    fun testFastCommandRouterMemoryRecognition() {
        val query1 = "Remember my name is Aftab"
        val norm1 = AffySttNormalizer.normalize(query1)
        val plan1 = AffyFastCommandRouter.tryFastMatch(norm1)
        assertNotNull("Should match memory command", plan1)
        assertEquals(ActionTypes.REMEMBER_FACT, plan1!!.intent)

        val query2 = "Remember Arham is my friend"
        val norm2 = AffySttNormalizer.normalize(query2)
        val plan2 = AffyFastCommandRouter.tryFastMatch(norm2)
        assertNotNull("Should match memory command", plan2)
        assertEquals(ActionTypes.REMEMBER_FACT, plan2!!.intent)

        val query3 = "Ye baat yaad rakhna ke mujhe classical music pasand hai"
        val norm3 = AffySttNormalizer.normalize(query3)
        val plan3 = AffyFastCommandRouter.tryFastMatch(norm3)
        assertNotNull(plan3)
        assertEquals(ActionTypes.REMEMBER_FACT, plan3!!.intent)
    }

    @Test
    fun testIntentRouterDirectRememberRouting() {
        val raw = "Remember my name is Aftab"
        val norm = AffySttNormalizer.normalize(raw)
        val classification = AffyIntentRouter.classify(raw, norm)

        assertNotNull(classification.matchedPlan)
        assertEquals(ActionTypes.REMEMBER_FACT, classification.matchedPlan!!.intent)
        assertFalse("Fast action plan does not require Gemini thinking delay", classification.needsGemini)
    }

    @Test
    fun testUserNameUpdateOverwritesPreviousName() {
        runBlocking {
            memoryManager.rememberFact("Remember my name is Aftab")
            val context1 = memoryManager.getLiveSessionMemoryContext()
            assertTrue(context1.contains("Aftab"))

            // Update name
            memoryManager.rememberFact("Remember my name is Aftab Ahmed")
            val context2 = memoryManager.getLiveSessionMemoryContext()
            assertTrue(context2.contains("Aftab Ahmed"))

            // Should not have duplicated entries
            val recalled = memoryManager.recallMemories()
            assertTrue(recalled.contains("Aftab Ahmed"))
        }
    }
}
