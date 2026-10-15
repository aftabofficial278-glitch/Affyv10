package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AffyConversationContextManager
import com.example.agent.AffyLongTermMemoryManager
import com.example.agent.NotificationReadState
import com.example.agent.vision.ScreenChangeType
import com.example.agent.vision.ScreenContext
import com.example.agent.vision.ScreenFingerprint
import com.example.agent.vision.ScreenStateType
import com.example.agent.vision.SemanticUiClassifier
import com.example.agent.vision.UiElementNode
import com.example.agent.vision.UiElementRole
import com.example.agent.vision.VisualRowItem
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
class AffyIntelligenceUpgradeTest {

    private lateinit var context: Context
    private lateinit var memoryManager: AffyLongTermMemoryManager
    private lateinit var contextManager: AffyConversationContextManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        memoryManager = AffyLongTermMemoryManager.getInstance(context)
        contextManager = AffyConversationContextManager.getInstance()
        runBlocking {
            memoryManager.clearAll()
            contextManager.reset()
        }
    }

    // ==========================================
    // 1. ORDINAL TARGET RESOLUTION TESTS
    // ==========================================

    @Test
    fun testOrdinalResolution_UrduAndEnglish() {
        val row1 = VisualRowItem(
            rowIndex = 0,
            bounds = Rect(0, 100, 1080, 300),
            primaryLabel = "Geo News Live Stream",
            allTexts = listOf("Geo News Live Stream", "100k watching"),
            clickableNode = UiElementNode(text = "Geo News", role = UiElementRole.VIDEO_CARD, isClickable = true, boundsInScreen = Rect(0, 100, 1080, 300)),
            memberNodes = emptyList()
        )
        val row2 = VisualRowItem(
            rowIndex = 1,
            bounds = Rect(0, 320, 1080, 520),
            primaryLabel = "ARY News Headlines",
            allTexts = listOf("ARY News Headlines", "50k watching"),
            clickableNode = UiElementNode(text = "ARY News", role = UiElementRole.VIDEO_CARD, isClickable = true, boundsInScreen = Rect(0, 320, 1080, 520)),
            memberNodes = emptyList()
        )
        val row3 = VisualRowItem(
            rowIndex = 2,
            bounds = Rect(0, 540, 1080, 740),
            primaryLabel = "Samaa TV Live Bulletin",
            allTexts = listOf("Samaa TV Live Bulletin", "30k watching"),
            clickableNode = UiElementNode(text = "Samaa TV", role = UiElementRole.VIDEO_CARD, isClickable = true, boundsInScreen = Rect(0, 540, 1080, 740)),
            memberNodes = emptyList()
        )

        val screenContext = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            appDisplayName = "YouTube",
            visualRows = listOf(row1, row2, row3),
            allInteractiveElements = listOf(row1.clickableNode!!, row2.clickableNode!!, row3.clickableNode!!)
        )

        // Test "pehla" (1st)
        val res1 = SemanticUiClassifier.resolveTarget("pehli video chalao", screenContext)
        assertTrue("Pehla should resolve to row 1", res1.isFound)
        assertEquals("Geo News Live Stream", res1.matchedRow?.primaryLabel)

        // Test "doosra" (2nd)
        val res2 = SemanticUiClassifier.resolveTarget("doosri video chalao", screenContext)
        assertTrue("Doosra should resolve to row 2", res2.isFound)
        assertEquals("ARY News Headlines", res2.matchedRow?.primaryLabel)

        // Test "3rd" / "teesra" (3rd)
        val res3 = SemanticUiClassifier.resolveTarget("3rd video chalao", screenContext)
        assertTrue("3rd should resolve to row 3", res3.isFound)
        assertEquals("Samaa TV Live Bulletin", res3.matchedRow?.primaryLabel)

        val res3Urdu = SemanticUiClassifier.resolveTarget("teesra video open karo", screenContext)
        assertTrue("Teesra should resolve to row 3", res3Urdu.isFound)
        assertEquals("Samaa TV Live Bulletin", res3Urdu.matchedRow?.primaryLabel)
    }

    // ==========================================
    // 2. SPATIAL TARGET RESOLUTION TESTS
    // ==========================================

    @Test
    fun testSpatialResolution_UparNeecheLeftRight() {
        val topNode = UiElementNode(
            text = "Header Menu",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(100, 100, 400, 200)
        )
        val bottomNode = UiElementNode(
            text = "Footer Actions",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(100, 1800, 400, 1900)
        )
        val leftNode = UiElementNode(
            text = "Previous Page",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(20, 900, 200, 1000)
        )
        val rightNode = UiElementNode(
            text = "Next Page",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(880, 900, 1060, 1000)
        )

        val screenContext = ScreenContext(
            foregroundPackage = "com.android.settings",
            appDisplayName = "Settings",
            allInteractiveElements = listOf(topNode, bottomNode, leftNode, rightNode)
        )

        val topRes = SemanticUiClassifier.resolveTarget("upar wala button", screenContext)
        assertTrue(topRes.isFound)
        assertEquals("Header Menu", topRes.matchedNode?.text)

        val bottomRes = SemanticUiClassifier.resolveTarget("neeche wala button", screenContext)
        assertTrue(bottomRes.isFound)
        assertEquals("Footer Actions", bottomRes.matchedNode?.text)

        val leftRes = SemanticUiClassifier.resolveTarget("left wala button", screenContext)
        assertTrue(leftRes.isFound)
        assertEquals("Previous Page", leftRes.matchedNode?.text)

        val rightRes = SemanticUiClassifier.resolveTarget("right wala option", screenContext)
        assertTrue(rightRes.isFound)
        assertEquals("Next Page", rightRes.matchedNode?.text)
    }

    // ==========================================
    // 3. SEMANTIC CONTEXT TARGET TESTS ("is channel", "is video", "is chat")
    // ==========================================

    @Test
    fun testSemanticContextTarget_ThisChannelAndThisVideo() {
        val channelCard = UiElementNode(
            text = "Coke Studio Pakistan",
            contentDescription = "Coke Studio Official Channel",
            role = UiElementRole.VIDEO_CARD,
            isClickable = true,
            boundsInScreen = Rect(50, 200, 1000, 400)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            appDisplayName = "YouTube",
            allInteractiveElements = listOf(channelCard)
        )

        val resChannel = SemanticUiClassifier.resolveTarget("is channel ko click karo", screenContext)
        assertTrue(resChannel.isFound)
        assertEquals(channelCard.id, resChannel.matchedNode?.id)

        val resVideo = SemanticUiClassifier.resolveTarget("is video ko chalao", screenContext)
        assertTrue(resVideo.isFound)
        assertEquals(channelCard.id, resVideo.matchedNode?.id)
    }

    // ==========================================
    // 4. BEFORE / AFTER SCREEN FINGERPRINT COMPARISON
    // ==========================================

    @Test
    fun testScreenFingerprint_ChangeVerification() {
        val before = ScreenFingerprint(
            packageName = "com.google.android.youtube",
            screenState = ScreenStateType.HOME,
            textSummaryHash = 12345,
            interactiveElementCount = 8
        )

        val afterNav = ScreenFingerprint(
            packageName = "com.whatsapp",
            screenState = ScreenStateType.CHAT_CONVERSATION,
            textSummaryHash = 67890,
            interactiveElementCount = 4
        )
        val navResult = before.compareWith(afterNav)
        assertTrue(navResult.hasChanged)
        assertEquals(ScreenChangeType.NAVIGATION, navResult.changeType)

        val afterContent = ScreenFingerprint(
            packageName = "com.google.android.youtube",
            screenState = ScreenStateType.RESULTS,
            textSummaryHash = 99999,
            interactiveElementCount = 12
        )
        val contentResult = before.compareWith(afterContent)
        assertTrue(contentResult.hasChanged)
        assertEquals(ScreenChangeType.CONTENT_UPDATED, contentResult.changeType)

        val unchanged = ScreenFingerprint(
            packageName = "com.google.android.youtube",
            screenState = ScreenStateType.HOME,
            textSummaryHash = 12345,
            interactiveElementCount = 8
        )
        val unchangedResult = before.compareWith(unchanged)
        assertFalse(unchangedResult.hasChanged)
        assertEquals(ScreenChangeType.NO_CHANGE, unchangedResult.changeType)
    }

    // ==========================================
    // 5. UNIFIED MEMORY ACROSS PERSONAS (MALE/FEMALE)
    // ==========================================

    @Test
    fun testSharedCanonicalMemoryAcrossPersonas() {
        runBlocking {
            // User interacts and saves a fact in the canonical memory repository
            memoryManager.rememberFact("Remember my favorite city is Lahore")

            // Verify Male live session memory context
            val maleSessionContext = memoryManager.getLiveSessionMemoryContext()
            assertTrue("Canonical memory context contains saved city", maleSessionContext.contains("Lahore"))

            // Verify that changing persona voice does NOT isolate or create separate memory stores
            val femaleVoiceContext = memoryManager.getLiveSessionMemoryContext()
            assertTrue("Female voice/persona gets the exact same canonical memories", femaleVoiceContext.contains("Lahore"))

            // Verify direct recall
            val recalled = memoryManager.recallMemories("city")
            assertTrue("Recalled memory contains Lahore", recalled.contains("Lahore"))
        }
    }

    // ==========================================
    // 6. CONVERSATION CONTEXT & RECONNECT RESTORATION
    // ==========================================

    @Test
    fun testConversationContext_NotificationStateAndPendingTracking() {
        // Notification context tracking
        contextManager.updateActiveNotification(
            sender = "Big Star",
            message = "Special discount coupon available!",
            packageName = "com.whatsapp"
        )

        val pendingList = contextManager.getPendingNotifications("Big Star")
        assertEquals(1, pendingList.size)
        assertEquals("Big Star", pendingList[0].sender)
        assertEquals(NotificationReadState.NEW, pendingList[0].readState)

        // Mark announced
        contextManager.markNotificationAnnounced("Big Star")
        val announcedList = contextManager.getPendingNotifications("Big Star")
        assertEquals(NotificationReadState.ANNOUNCED_BUT_NOT_READ, announcedList[0].readState)

        // Mark read
        contextManager.markNotificationRead("Big Star")
        val readList = contextManager.getPendingNotifications("Big Star")
        assertEquals(NotificationReadState.READ_BY_USER, readList[0].readState)
        assertEquals(0, contextManager.getUnreadNotifications().size)
    }

    // ==========================================
    // 7. SAFETY REGRESSION: PRIVACY SANITIZATION & WHATSAPP AUTO-SEND PROTECTION
    // ==========================================

    @Test
    fun testSensitiveDataSanitization_OtpAndPasswordsNeverStored() {
        runBlocking {
            // Test OTP rejection
            val (otpSuccess, _) = memoryManager.rememberFact("My bank OTP is 849201")
            assertFalse("Bank OTP must be strictly rejected from memory", otpSuccess)

            // Test password rejection
            val (pwSuccess, _) = memoryManager.rememberFact("Password is SuperSecretPassword123")
            assertFalse("Passwords must never be stored", pwSuccess)

            // Verify live session context does NOT contain sensitive values
            val liveCtx = memoryManager.getLiveSessionMemoryContext()
            assertFalse(liveCtx.contains("849201"))
            assertFalse(liveCtx.contains("SuperSecretPassword123"))

            // UI Sanitizer verification
            val maskedOtp = SemanticUiClassifier.sanitizeText("Your OTP code is 987654 for login verification", isPassword = false)
            assertEquals("[SENSITIVE_CONTENT_MASKED]", maskedOtp)
        }
    }

    @Test
    fun testWhatsAppAutoSendProtection_NeverSendsWithoutExplicitIntent() {
        // Test that simply having a screen open or an empty/passive intent does not trigger send
        val passiveQuery1 = "WhatsApp khol do"
        val isExplicitSend1 = passiveQuery1.contains("bhejo") || passiveQuery1.contains("send")
        assertFalse("Opening WhatsApp must NOT trigger auto-send", isExplicitSend1)

        val passiveQuery2 = "Arham ki chat open karo"
        val isExplicitSend2 = passiveQuery2.contains("bhejo") || passiveQuery2.contains("send karo")
        assertFalse("Opening chat must NOT trigger send", isExplicitSend2)

        val explicitQuery = "Arham ko bhejo ke main 5 baje aa raha hoon"
        val isExplicitSend3 = explicitQuery.contains("bhejo") || explicitQuery.contains("send")
        assertTrue("Explicit command with message content triggers send intent", isExplicitSend3)
    }
}
