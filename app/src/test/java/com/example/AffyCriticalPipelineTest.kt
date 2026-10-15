package com.example

import com.example.agent.AffyDiagnostics
import com.example.agent.DiagnosticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AffyCriticalPipelineTest {

    @Before
    fun setUp() {
        AffyDiagnostics.clear()
    }

    @Test
    fun testVoicePipelineDiagnostics() {
        // Step 1: Voice Request
        AffyDiagnostics.logVoiceStage(
            stage = "VOICE_REQUEST",
            engine = "Gemini Puck AI Voice (Young Male)",
            details = "Hello boss"
        )
        // Step 2: Primary Engine Selected
        AffyDiagnostics.logVoiceStage(
            stage = "PRIMARY_ENGINE_SELECTED",
            engine = "GEMINI_TTS (Puck - Young Male)",
            details = "API key configured"
        )
        // Step 3: Request Started
        AffyDiagnostics.logVoiceStage(
            stage = "PRIMARY_REQUEST_START",
            engine = "GEMINI_TTS (Puck - Young Male)",
            details = "Hello boss"
        )
        // Step 4: Primary Started
        AffyDiagnostics.logVoiceStage(
            stage = "AUDIO_STARTED",
            engine = "GEMINI_TTS (Puck - Young Male)",
            details = "Playback active"
        )
        // Step 5: Completed
        AffyDiagnostics.logVoiceStage(
            stage = "AUDIO_COMPLETED",
            engine = "GEMINI_TTS (Puck - Young Male)"
        )

        val voiceLogs = AffyDiagnostics.getTtsLogs()
        assertEquals(5, voiceLogs.size)
        assertEquals("VOICE_REQUEST", voiceLogs.last().action)
        assertEquals("AUDIO_COMPLETED", voiceLogs.first().action)
    }

    @Test
    fun testVoicePipelineFallbackDiagnostics() {
        // Step 1: Voice Request
        AffyDiagnostics.logVoiceStage(
            stage = "VOICE_REQUEST",
            engine = "Gemini Puck AI Voice (Young Male)",
            details = "Kya haal hai"
        )
        // Step 2: Primary fails
        AffyDiagnostics.logVoiceStage(
            stage = "PRIMARY_REQUEST_RESULT",
            engine = "GEMINI_TTS (Puck - Young Male)",
            details = "Gemini synthesis failed",
            isSuccess = false,
            failureReason = "SYNTHESIS_FAILED_OR_TIMEOUT"
        )
        // Step 3: Failure logged, NO fallback to robotic device TTS
        AffyDiagnostics.logVoiceStage(
            stage = "TTS_FAILED_NO_FALLBACK",
            engine = "GEMINI_TTS (Puck - Young Male)",
            details = "Gemini TTS synthesis failed. Fallback disabled.",
            failureReason = "GEMINI_TTS_FAILED",
            isSuccess = false
        )

        val voiceLogs = AffyDiagnostics.getTtsLogs()
        assertTrue(voiceLogs.any { it.action == "TTS_FAILED_NO_FALLBACK" })
        assertTrue(voiceLogs.all { it.voiceProvider != "DEVICE_MALE_TTS" })
    }

    @Test
    fun testNotificationAnnouncementStages() {
        val pkg = "com.whatsapp"
        val sender = "Ali"
        val message = "Kal kab milna hai?"

        // 1. Notification received
        AffyDiagnostics.logNotificationStage(
            stage = "NOTIFICATION_RECEIVED",
            packageName = pkg,
            sender = sender,
            details = message
        )
        // 2. Message extracted
        AffyDiagnostics.logNotificationStage(
            stage = "MESSAGE_EXTRACTED",
            packageName = pkg,
            sender = sender,
            details = message
        )
        // 3. Announcement created
        val prompt = "Boss, ek message aya hai. Parh ke sunaun?"
        AffyDiagnostics.logNotificationStage(
            stage = "ANNOUNCEMENT_CREATED",
            packageName = pkg,
            sender = sender,
            details = prompt
        )
        // 4. TTS requested
        AffyDiagnostics.logNotificationStage(
            stage = "TTS_REQUESTED",
            packageName = pkg,
            details = "Text: '$prompt'"
        )
        // 5. Audio started
        AffyDiagnostics.logNotificationStage(
            stage = "AUDIO_STARTED",
            packageName = pkg,
            details = "Speaking prompt"
        )

        val notifLogs = AffyDiagnostics.getNotificationLogs()
        assertEquals(5, notifLogs.size)
        assertEquals("AUDIO_STARTED", notifLogs.first().action)
        assertTrue(notifLogs.find { it.action == "ANNOUNCEMENT_CREATED" }?.input?.contains(prompt) == true)
    }

    @Test
    fun testWhatsAppContextualStages() {
        val pkg = "com.whatsapp.w4b"
        val sender = "Zain"

        AffyDiagnostics.logWhatsAppStage(
            stage = "NOTIFICATION_RECEIVED",
            packageName = pkg,
            sender = sender,
            details = "Bhai kahan ho?"
        )
        AffyDiagnostics.logWhatsAppStage(
            stage = "MESSAGE_EXTRACTED",
            packageName = pkg,
            sender = sender,
            details = "Bhai kahan ho?"
        )
        AffyDiagnostics.logWhatsAppStage(
            stage = "REPLY_GENERATED",
            packageName = pkg,
            sender = sender,
            details = "Yahin hoon yaar! Tum batao?"
        )
        AffyDiagnostics.logWhatsAppStage(
            stage = "SEND_SUCCESS",
            packageName = pkg,
            sender = sender,
            details = "Reply sent: 'Yahin hoon yaar! Tum batao?'",
            isSuccess = true
        )

        val stages = AffyDiagnostics.getWhatsAppStages()
        assertEquals(4, stages.size)
        val sendSuccess = stages.find { it.action == "SEND_SUCCESS" }
        assertNotNull(sendSuccess)
        assertTrue(sendSuccess!!.isSuccess)
        assertEquals("com.whatsapp.w4b", sendSuccess.selectedPackage)
    }
}
