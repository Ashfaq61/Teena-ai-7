package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun readAppNameFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Teena AI", appName)
    }

    @Test
    fun mainNavigationAndDashboardRender() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TEENA AI").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input_field").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_voice").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gemini Live Voice Assistant").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_toggle_btn").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("nav_agents").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_run_mission_btn").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_automation").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_api_manager"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_api_manager").assertIsDisplayed()
    }
}
