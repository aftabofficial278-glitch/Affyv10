package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AffyFastCommandRouter
import com.example.agent.AffyIntentRouter
import com.example.data.model.ActionTypes
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
class AffyCapabilityUpgradeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCameraCommandRouting() {
        val openCameraPlan = AffyFastCommandRouter.tryFastMatch("Affy, camera kholo")
        assertNotNull(openCameraPlan)
        assertEquals(ActionTypes.OPEN_CAMERA, openCameraPlan?.intent)

        val photoPlan = AffyFastCommandRouter.tryFastMatch("photo lo")
        assertNotNull(photoPlan)
        assertEquals(ActionTypes.TAKE_PHOTO, photoPlan?.intent)

        val compoundCameraPlan = AffyFastCommandRouter.tryFastMatch("camera kholo aur photo le lo")
        assertNotNull(compoundCameraPlan)
        assertEquals(ActionTypes.TAKE_PHOTO, compoundCameraPlan?.intent)
        assertEquals("open_and_capture", compoundCameraPlan?.actions?.firstOrNull()?.value)
    }

    @Test
    fun testBatteryStatusRouting() {
        val batteryQueryPlan = AffyFastCommandRouter.tryFastMatch("Affy, battery kitni hai?")
        assertNotNull(batteryQueryPlan)
        assertEquals(ActionTypes.GET_BATTERY, batteryQueryPlan?.intent)

        val chargingQueryPlan = AffyFastCommandRouter.tryFastMatch("Charging ho rahi hai?")
        assertNotNull(chargingQueryPlan)
        assertEquals(ActionTypes.GET_BATTERY, chargingQueryPlan?.intent)
    }

    @Test
    fun testIntentRouterDelegatesCameraAndBattery() {
        val intentClassification1 = AffyIntentRouter.classify(
            rawSpeech = "Camera kholo",
            normalizedSpeech = "camera kholo"
        )
        assertEquals(ActionTypes.OPEN_CAMERA, intentClassification1.matchedPlan?.intent)

        val intentClassification2 = AffyIntentRouter.classify(
            rawSpeech = "Battery kitni bachi hai?",
            normalizedSpeech = "battery kitni bachi hai"
        )
        assertEquals(ActionTypes.GET_BATTERY, intentClassification2.matchedPlan?.intent)
    }
}
