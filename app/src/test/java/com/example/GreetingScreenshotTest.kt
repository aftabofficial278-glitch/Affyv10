package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.example.data.model.AffyState
import com.example.ui.components.AffyAiCoreOrb
import com.example.ui.components.AffyHologramPresence
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun greeting_screenshot() {
        composeTestRule.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AffyHologramPresence(
                        state = AffyState.IDLE,
                        amplitude = 0.1f,
                        modifier = Modifier.fillMaxSize()
                    )
                    AffyAiCoreOrb(
                        state = AffyState.IDLE,
                        amplitude = 0.1f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("affy_ai_core_orb").assertExists()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
    }

    @Test
    fun listening_full_screen_universe_renders() {
        composeTestRule.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AffyHologramPresence(
                        state = AffyState.LISTENING,
                        amplitude = 0.78f,
                        modifier = Modifier.fillMaxSize()
                    )
                    AffyAiCoreOrb(
                        state = AffyState.LISTENING,
                        amplitude = 0.78f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("affy_ai_core_orb").assertExists()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/listening_universe.png")
    }
}
