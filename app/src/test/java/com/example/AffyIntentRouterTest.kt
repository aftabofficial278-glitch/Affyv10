package com.example

import com.example.agent.AffyIntentCategory
import com.example.agent.AffyIntentRouter
import com.example.agent.AffySttNormalizer
import com.example.agent.LatencyBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AffyIntentRouterTest {

    @Test
    fun testGreetingClassification() {
        val raw = "Hello AFFY"
        val normalized = AffySttNormalizer.normalize(raw)
        val classification = AffyIntentRouter.classify(raw, normalized)

        assertEquals(AffyIntentCategory.GREETING, classification.category)
        assertFalse(classification.needsGemini)
        assertFalse(classification.requiresThinkingState)
        assertNotNull(classification.matchedPlan)
        assertTrue(classification.matchedPlan!!.actions.isEmpty())
        assertTrue(classification.immediateSpeech!!.contains("Boss"))
    }

    @Test
    fun testCasualConversationClassification() {
        val queries = listOf("Kya haal hai?", "Aaj kya kar rahe ho?", "Mujhse baat karo", "Kaise ho?")
        for (query in queries) {
            val normalized = AffySttNormalizer.normalize(query)
            val classification = AffyIntentRouter.classify(query, normalized)

            assertEquals("Failed for query: $query", AffyIntentCategory.CASUAL_CONVERSATION, classification.category)
            assertFalse("Casual talk should not require Gemini", classification.needsGemini)
            assertFalse("Casual talk should not trigger THINKING", classification.requiresThinkingState)
            assertNotNull(classification.matchedPlan)
            assertTrue(classification.matchedPlan!!.actions.isEmpty())
        }
    }

    @Test
    fun testEmotionalConversationClassification() {
        val emotionalQueries = listOf(
            "Yaar aaj mood ajeeb hai",
            "Aaj bohat ajeeb din tha",
            "Main thoda pareshan hoon",
            "Aaj bohat thak gaya hoon",
            "Tumhe pata hai kya hua?"
        )
        for (query in emotionalQueries) {
            val normalized = AffySttNormalizer.normalize(query)
            val classification = AffyIntentRouter.classify(query, normalized)

            assertEquals("Failed for emotional query: $query", AffyIntentCategory.EMOTIONAL_CONVERSATION, classification.category)
            assertFalse("Emotional chat should not require Gemini", classification.needsGemini)
            assertFalse("Emotional chat should not trigger THINKING", classification.requiresThinkingState)
            assertNotNull(classification.matchedPlan)
            assertTrue(classification.matchedPlan!!.actions.isEmpty())
        }
    }

    @Test
    fun testAcknowledgementClassification() {
        val acks = listOf("Acha", "Theek hai", "Haan ye sahi hai", "Hmm", "Bilkul")
        for (ack in acks) {
            val normalized = AffySttNormalizer.normalize(ack)
            val classification = AffyIntentRouter.classify(ack, normalized)

            assertEquals("Failed for ack: $ack", AffyIntentCategory.ACKNOWLEDGEMENT, classification.category)
            assertFalse(classification.needsGemini)
            assertFalse(classification.requiresThinkingState)
        }
    }

    @Test
    fun testContextualFollowUpClassification() {
        val raw = "Atif Aslam ka koi song"
        val normalized = AffySttNormalizer.normalize(raw)
        val classification = AffyIntentRouter.classify(
            rawSpeech = raw,
            normalizedSpeech = normalized,
            lastActiveApp = "YouTube",
            isContinuousActive = true
        )

        assertEquals(AffyIntentCategory.FOLLOW_UP, classification.category)
        assertFalse(classification.needsGemini)
        assertFalse(classification.requiresThinkingState)
        assertNotNull(classification.matchedPlan)
        assertEquals("MULTI_STEP_APP_COMMAND", classification.matchedPlan!!.intent)
    }

    @Test
    fun testFollowUpTapFirstResult() {
        val raw = "Pehla wala chalao"
        val normalized = AffySttNormalizer.normalize(raw)
        val classification = AffyIntentRouter.classify(
            rawSpeech = raw,
            normalizedSpeech = normalized,
            lastActiveApp = "YouTube",
            isContinuousActive = true
        )

        assertEquals(AffyIntentCategory.FOLLOW_UP, classification.category)
        assertFalse(classification.needsGemini)
        assertNotNull(classification.matchedPlan)
        assertEquals("TAP_LIKE", classification.matchedPlan!!.intent)
    }

    @Test
    fun testSimpleDeviceCommand() {
        val raw = "Volume badhao"
        val normalized = AffySttNormalizer.normalize(raw)
        val classification = AffyIntentRouter.classify(raw, normalized)

        assertEquals(AffyIntentCategory.SIMPLE_DEVICE_COMMAND, classification.category)
        assertFalse(classification.needsGemini)
        assertFalse(classification.requiresThinkingState)
        assertNotNull(classification.matchedPlan)
    }

    @Test
    fun testKnowledgeQuestionRequiresGemini() {
        val raw = "Duniya ka sabse bada mulk kaunsa hai?"
        val normalized = AffySttNormalizer.normalize(raw)
        val classification = AffyIntentRouter.classify(raw, normalized)

        assertEquals(AffyIntentCategory.QUESTION, classification.category)
        assertTrue(classification.needsGemini)
        assertTrue(classification.requiresThinkingState)
    }

    @Test
    fun testCreatorContextQueriesRouteToGeminiWithoutThinkingState() {
        val creatorQueries = listOf(
            "Who created you?",
            "Tumhe kisne banaya?",
            "Aftab kaun hai?",
            "Aftab kya karta hai?",
            "Tumhe Aftab ne kyun banaya?",
            "Tell me something about your creator",
            "Aftab kaisa developer hai?",
            "Bro who created you?",
            "Tumhara naam AFFY kyun hai?",
            "AFFY naam kese pada?",
            "Why are you called AFFY?",
            "Tumhara naam Affy kisne rakha?"
        )
        for (query in creatorQueries) {
            val normalized = AffySttNormalizer.normalize(query)
            val classification = AffyIntentRouter.classify(query, normalized)

            assertEquals("Failed for query: $query", AffyIntentCategory.CREATOR_CONTEXT, classification.category)
            assertTrue("Creator query must route to Gemini for contextual response: $query", classification.needsGemini)
            assertFalse("Creator query should not show heavy THINKING state: $query", classification.requiresThinkingState)
        }
    }

    @Test
    fun testDeviceCommandsDoNotTriggerCreatorContext() {
        val commands = listOf(
            "YouTube kholo",
            "Volume badhao",
            "Flash on karo",
            "Torch band karo",
            "Wifi on karo",
            "Data off karo",
            "Bluetooth on karo",
            "Hotspot chalao",
            "Airplane mode on karo",
            "Auto rotate on karo",
            "Phone silent karo"
        )
        for (cmd in commands) {
            val normalized = AffySttNormalizer.normalize(cmd)
            val classification = AffyIntentRouter.classify(cmd, normalized)

            assertEquals("Device command should stay SIMPLE_DEVICE_COMMAND: $cmd", AffyIntentCategory.SIMPLE_DEVICE_COMMAND, classification.category)
            assertFalse("Device command should not need Gemini: $cmd", classification.needsGemini)
            assertFalse("Device command must not be Creator Context: $cmd", classification.category == AffyIntentCategory.CREATOR_CONTEXT)
        }
    }

    @Test
    fun testSttNormalizerPreservesAftabSubjectQueries() {
        val aftabQ1 = "Aftab kaun hai?"
        val norm1 = AffySttNormalizer.normalize(aftabQ1)
        assertTrue("Aftab subject must be preserved: $norm1", norm1.contains("Aftab", ignoreCase = true))

        val aftabQ2 = "Aftab kya karta hai?"
        val norm2 = AffySttNormalizer.normalize(aftabQ2)
        assertTrue("Aftab subject must be preserved: $norm2", norm2.contains("Aftab", ignoreCase = true))

        val aftabCmd = "Hey Aftab, YouTube kholo"
        val normCmd = AffySttNormalizer.normalize(aftabCmd)
        assertEquals("YouTube kholo", normCmd)
    }

    @Test
    fun testLatencyBreakdownFormatting() {
        val breakdown = LatencyBreakdown(
            sttMs = 450,
            routerMs = 15,
            geminiMs = 0,
            actionMs = 35,
            ttsMs = 320,
            totalMs = 820
        )
        val formatted = breakdown.toFormattedString()
        assertTrue(formatted.contains("STT: 450 ms"))
        assertTrue(formatted.contains("Router: 15 ms"))
        assertTrue(formatted.contains("Action: 35 ms"))
        assertTrue(formatted.contains("TTS: 320 ms"))
        assertTrue(formatted.contains("Total: 820 ms"))
    }
}
