package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ActionTypes
import com.example.data.remote.GeminiRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AFFY", appName)
    }

    @Test
    fun `test offline fallback intent for app opening`() {
        val repo = GeminiRepository()
        val plan = repo.createOfflineFallbackPlan("TikTok kholo")
        assertEquals("OPEN_APP", plan.intent)
        assertEquals(ActionTypes.OPEN_APP, plan.actions.firstOrNull()?.type)
        assertEquals("TikTok", plan.actions.firstOrNull()?.target)
        assertTrue(plan.speechResponse.contains("TikTok"))
    }

    @Test
    fun `test offline fallback intent for calling contact`() {
        val repo = GeminiRepository()
        val plan = repo.createOfflineFallbackPlan("Ali ko call karo")
        assertEquals("CALL_CONTACT", plan.intent)
        assertEquals(ActionTypes.CALL_CONTACT, plan.actions.firstOrNull()?.type)
        assertEquals("Ali", plan.actions.firstOrNull()?.target)
    }

    @Test
    fun `test offline fallback intent for like video action`() {
        val repo = GeminiRepository()
        val plan = repo.createOfflineFallbackPlan("Is video ko like kar do")
        assertEquals("TAP_LIKE", plan.intent)
        assertEquals(ActionTypes.TAP_LIKE, plan.actions.firstOrNull()?.type)
    }

    @Test
    fun `test offline fallback intent for scroll`() {
        val repo = GeminiRepository()
        val plan = repo.createOfflineFallbackPlan("Neeche scroll karo")
        assertEquals("SCROLL_DOWN", plan.intent)
        assertEquals(ActionTypes.SCROLL_DOWN, plan.actions.firstOrNull()?.type)
    }
}
