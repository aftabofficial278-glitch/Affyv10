package com.example

import com.example.data.remote.GeminiCandidate
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiError
import com.example.data.remote.GeminiGenerateResponse
import com.example.data.remote.GeminiPart
import com.example.data.remote.GeminiPromptFeedback
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiResponseParserTest {

    private val moshi = Moshi.Builder().build()
    private val responseAdapter = moshi.adapter(GeminiGenerateResponse::class.java)

    @Test
    fun testStandardGeminiResponseParsing() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"intent\":\"GENERAL_CHAT\",\"speech_response\":\"Hello boss!\"}"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        val extracted = response?.extractFirstText()
        assertNotNull(extracted)
        assertEquals("{\"intent\":\"GENERAL_CHAT\",\"speech_response\":\"Hello boss!\"}", extracted)
        assertNull(response?.getBlockOrFinishReason())
    }

    @Test
    fun testCandidateWithNullOrMissingPartsDoesNotCrash() {
        // Reproduces the exact error: "Request value parts missing at $.candidates[0].content"
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model"
                  },
                  "finishReason": "SAFETY"
                }
              ]
            }
        """.trimIndent()

        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        val text = response?.extractFirstText()
        assertNull(text)
        val reason = response?.getBlockOrFinishReason()
        assertEquals("Candidate finished with reason: SAFETY", reason)
    }

    @Test
    fun testEmptyCandidatesResponse() {
        val json = """
            {
              "candidates": []
            }
        """.trimIndent()

        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertNull(response?.extractFirstText())
    }

    @Test
    fun testBlockedPromptFeedbackResponse() {
        val json = """
            {
              "promptFeedback": {
                "blockReason": "SAFETY"
              }
            }
        """.trimIndent()

        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertNull(response?.extractFirstText())
        val reason = response?.getBlockOrFinishReason()
        assertEquals("Prompt blocked (SAFETY)", reason)
    }

    @Test
    fun testDetailedDiagnosisForEmptyCandidates() {
        val json = """{ "candidates": [] }"""
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertEquals("Empty 'candidates' array returned by model", response?.getDetailedDiagnosis())
    }

    @Test
    fun testDetailedDiagnosisForMissingContent() {
        val json = """{ "candidates": [ { "finishReason": "STOP" } ] }"""
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertEquals("Missing 'content' in candidate (finishReason: STOP)", response?.getDetailedDiagnosis())
    }

    @Test
    fun testDetailedDiagnosisForMissingParts() {
        val json = """{ "candidates": [ { "content": { "role": "model" }, "finishReason": "STOP" } ] }"""
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertEquals("Missing 'parts' in candidate content (finishReason: STOP)", response?.getDetailedDiagnosis())
    }

    @Test
    fun testDetailedDiagnosisForEmptyParts() {
        val json = """{ "candidates": [ { "content": { "parts": [] }, "finishReason": "STOP" } ] }"""
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertEquals("Empty 'parts' in candidate content (finishReason: STOP)", response?.getDetailedDiagnosis())
    }

    @Test
    fun testDetailedDiagnosisForApiError() {
        val json = """{ "error": { "code": 404, "message": "models/invalid-model is not found for API version v1beta" } }"""
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertEquals("models/invalid-model is not found for API version v1beta", response?.getDetailedDiagnosis())
    }

    @Test
    fun testDetailedDiagnosisForSafetyBlock() {
        val json = """{ "promptFeedback": { "blockReason": "SAFETY" } }"""
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertEquals("Prompt blocked (SAFETY)", response?.getDetailedDiagnosis())
    }

    @Test
    fun testMaxTokensExtractionPreservesUsablePartialText() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "The capital of Pakistan is Islamabad and it was established in the 1960s"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "MAX_TOKENS"
                }
              ]
            }
        """.trimIndent()
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        val text = response?.extractFirstText()
        assertEquals("The capital of Pakistan is Islamabad and it was established in the 1960s", text)
        // MAX_TOKENS is treated as a valid completion that yielded usable text, not a blocked failure
        assertNull(response?.getBlockOrFinishReason())
    }

    @Test
    fun testMaxTokensDiagnosisWhenContentMissing() {
        val json = """
            {
              "candidates": [
                {
                  "finishReason": "MAX_TOKENS"
                }
              ]
            }
        """.trimIndent()
        val response = responseAdapter.fromJson(json)
        assertNotNull(response)
        assertNull(response?.extractFirstText())
        assertEquals("Token limit reached before content generated (finishReason: MAX_TOKENS)", response?.getDetailedDiagnosis())
    }
}
