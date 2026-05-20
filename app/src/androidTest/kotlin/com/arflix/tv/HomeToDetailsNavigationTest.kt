package com.arflix.tv

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import android.os.SystemClock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeToDetailsNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val device by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun clickingVideoCard_navigatesToDetailsScreen() {
        val profilePickerVisible = try {
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodesWithTag("profile_screen")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            true
        } catch (e: ComposeTimeoutException) {
            false
        }

        if (profilePickerVisible) {
            // Wait past the 300 ms isReadyForInput guard in ProfileSelectionScreen
            SystemClock.sleep(500)
            device.waitForIdle()
            // Cloud connected — select the focused profile
            composeTestRule.onAllNodesWithTag("profile_avatar").onFirst().performClick()
        }

        // Wait for home screen content to load from network (up to 60 seconds)
        composeTestRule.waitUntil(timeoutMillis = 60_000) {
            composeTestRule.onAllNodesWithTag("media_card")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Click the first visible card
        composeTestRule.onAllNodesWithTag("media_card").onFirst().performClick()

        // Wait for details screen to appear
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag("details_screen")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("details_screen").assertExists()

        // Stay on details screen for 5 seconds to catch deferred crashes
        SystemClock.sleep(5_000)
        device.waitForIdle()
        composeTestRule.onNodeWithTag("details_screen").assertExists()
    }
}
