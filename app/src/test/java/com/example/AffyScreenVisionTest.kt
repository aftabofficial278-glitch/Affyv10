package com.example

import android.graphics.Rect
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.agent.vision.ScreenContext
import com.example.agent.vision.ScreenStateType
import com.example.agent.vision.SemanticUiClassifier
import com.example.agent.vision.UiElementNode
import com.example.agent.vision.UiElementRole
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
class AffyScreenVisionTest {

    @Test
    fun testSemanticRoleClassification() {
        val searchNode = UiElementNode(
            text = "Search or type URL",
            role = UiElementRole.SEARCH_INPUT,
            isEditable = true
        )
        assertEquals(UiElementRole.SEARCH_INPUT, searchNode.role)

        val playNode = UiElementNode(
            contentDescription = "Play video",
            role = UiElementRole.PLAY_CONTROL,
            isClickable = true
        )
        assertEquals(UiElementRole.PLAY_CONTROL, playNode.role)
    }

    @Test
    fun testScreenStateDetection_ErrorScreen() {
        val nodes = listOf(
            UiElementNode(text = "Something went wrong", role = UiElementRole.STATIC_LABEL),
            UiElementNode(text = "Try Again", role = UiElementRole.BUTTON, isClickable = true)
        )
        val texts = listOf("Something went wrong", "Please check your network connection and try again.")

        val (state, errorMsg) = SemanticUiClassifier.classifyScreenState("com.google.android.youtube", nodes, texts)
        assertEquals(ScreenStateType.ERROR_DIALOG, state)
        assertNotNull(errorMsg)
    }

    @Test
    fun testScreenStateDetection_SearchResults() {
        val nodes = listOf(
            UiElementNode(text = "Search", role = UiElementRole.SEARCH_INPUT, isEditable = true),
            UiElementNode(text = "Result 1: Imran Khan live speech", role = UiElementRole.BUTTON, isClickable = true),
            UiElementNode(text = "Result 2: News update", role = UiElementRole.BUTTON, isClickable = true)
        )
        val texts = listOf("Showing results for Imran Khan", "Result 1", "Result 2")

        val (state, _) = SemanticUiClassifier.classifyScreenState("com.google.android.youtube", nodes, texts)
        assertEquals(ScreenStateType.RESULTS, state)
    }

    @Test
    fun testSemanticTargetFinding_SearchBox() {
        val searchField = UiElementNode(
            text = "",
            contentDescription = "Search YouTube",
            role = UiElementRole.SEARCH_INPUT,
            isEditable = true,
            boundsInScreen = Rect(50, 100, 500, 180)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            appDisplayName = "YouTube",
            allInteractiveElements = listOf(searchField)
        )

        val result = SemanticUiClassifier.resolveTarget("search box", screenContext)
        assertTrue("Target resolution should succeed", result.isFound)
        assertEquals(searchField.id, result.matchedNode?.id)
        assertTrue("Confidence should be high for semantic role match", result.confidence >= 0.8f)
    }

    @Test
    fun testSemanticTargetFinding_PositionalNeecheWala() {
        val topBtn = UiElementNode(
            text = "Cancel",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(50, 100, 300, 180)
        )
        val bottomBtn = UiElementNode(
            text = "Confirm & Proceed",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(50, 800, 300, 880)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.android.settings",
            appDisplayName = "Settings",
            allInteractiveElements = listOf(topBtn, bottomBtn)
        )

        val result = SemanticUiClassifier.resolveTarget("neeche wala option", screenContext)
        assertTrue("Positional resolution should succeed", result.isFound)
        assertEquals("Confirm & Proceed", result.matchedNode?.displayLabel)
    }

    @Test
    fun testLowConfidenceGuardrail_AsksInsteadOfGuessing() {
        val btn1 = UiElementNode(
            text = "Account Settings",
            role = UiElementRole.BUTTON,
            isClickable = true,
            boundsInScreen = Rect(50, 100, 300, 180)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.example.app",
            appDisplayName = "Test App",
            allInteractiveElements = listOf(btn1)
        )

        val result = SemanticUiClassifier.resolveTarget("rocket ship launch", screenContext)
        assertFalse("Low confidence target should not blindly execute", result.isFound)
        assertTrue("Should request clarification when target is ambiguous or not present", result.needsClarification)
        assertNotNull(result.clarificationPrompt)
    }

    @Test
    fun testPrivacySanitization_MasksPasswordsAndOtps() {
        val cleanPassword = SemanticUiClassifier.sanitizeText("mySecretPassword123!", isPassword = true)
        assertEquals("••••••", cleanPassword)

        val cleanLabel = SemanticUiClassifier.sanitizeText("Click here to proceed", isPassword = false)
        assertEquals("Click here to proceed", cleanLabel)
    }

    @Test
    fun testScreenContextConversationalSummary() {
        val screenContext = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            appDisplayName = "YouTube",
            screenState = ScreenStateType.HOME,
            visibleTexts = listOf("Trending Videos", "Recommended for you"),
            clickableElements = listOf(
                UiElementNode(text = "Search", role = UiElementRole.SEARCH_INPUT, isClickable = true),
                UiElementNode(text = "Subscriptions", role = UiElementRole.BUTTON, isClickable = true)
            ),
            allInteractiveElements = listOf(
                UiElementNode(text = "Search", role = UiElementRole.SEARCH_INPUT, isClickable = true),
                UiElementNode(text = "Subscriptions", role = UiElementRole.BUTTON, isClickable = true)
            )
        )

        val summary = screenContext.toConversationalSummary()
        assertTrue(summary.contains("YouTube"))
        assertTrue(summary.contains("Search") || summary.contains("Subscriptions"))
    }

    @Test
    fun testSemanticTargetFinding_SendButton() {
        val sendBtn = UiElementNode(
            text = "",
            contentDescription = "Send",
            role = UiElementRole.SEND_BUTTON,
            isClickable = true,
            boundsInScreen = Rect(900, 1800, 1050, 1950)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.whatsapp",
            appDisplayName = "WhatsApp",
            allInteractiveElements = listOf(sendBtn)
        )

        val result = SemanticUiClassifier.resolveTarget("send karo", screenContext)
        assertTrue("Send button resolution should succeed for 'send karo'", result.isFound)
        assertEquals(sendBtn.id, result.matchedNode?.id)
    }

    @Test
    fun testSemanticTargetFinding_CommentPostButton() {
        val postBtn = UiElementNode(
            text = "Post",
            role = UiElementRole.COMMENT_BUTTON,
            isClickable = true,
            boundsInScreen = Rect(850, 1200, 1020, 1300)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.zhiliaoapp.musically",
            appDisplayName = "TikTok",
            allInteractiveElements = listOf(postBtn)
        )

        val result = SemanticUiClassifier.resolveTarget("comment post karo", screenContext)
        assertTrue("Post button resolution should succeed for 'comment post karo'", result.isFound)
        assertEquals(postBtn.id, result.matchedNode?.id)
    }

    @Test
    fun testSemanticTargetFinding_LikeButton() {
        val likeBtn = UiElementNode(
            contentDescription = "Like video",
            role = UiElementRole.LIKE_BUTTON,
            isClickable = true,
            boundsInScreen = Rect(950, 900, 1050, 1000)
        )
        val screenContext = ScreenContext(
            foregroundPackage = "com.google.android.youtube",
            appDisplayName = "YouTube",
            allInteractiveElements = listOf(likeBtn)
        )

        val result = SemanticUiClassifier.resolveTarget("is video ko like karo", screenContext)
        assertTrue("Like button resolution should succeed for 'is video ko like karo'", result.isFound)
        assertEquals(likeBtn.id, result.matchedNode?.id)
    }
}
