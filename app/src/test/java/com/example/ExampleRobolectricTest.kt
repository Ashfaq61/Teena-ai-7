package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.ImageProxy
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import com.example.system.SketchFileProvider
import com.example.system.SketchPoint
import com.example.system.SketchStroke
import com.example.system.TeenaBootReceiver
import com.example.system.TeenaDeviceAdminReceiver
import com.example.system.TeenaFaceDetectionEngine
import com.example.system.TeenaFirebaseBridge
import com.example.system.TeenaFirebaseMessagingService
import com.example.system.TeenaKeyboardService
import com.example.system.TeenaScreenShareService
import com.example.system.TransientFaceDetectionState
import com.example.ui.ShareToTeenaActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        composeRule.onNodeWithTag("emergency_stop_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_input_field").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_voice").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gemini Live Voice Assistant").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_assistant_list").performScrollToNode(hasTestTag("voice_orb_canvas"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_orb_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_assistant_list").performScrollToNode(hasTestTag("voice_toggle_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_toggle_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("voice_assistant_list").performScrollToNode(hasTestTag("voice_live_vision_card"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_live_vision_card").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_camera_toggle_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("voice_photo_live_btn").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("nav_agents").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("agent_run_mission_btn").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_automation").assertIsDisplayed()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_api_manager"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_api_manager").assertIsDisplayed()

        // Verify Research Center ScrollableTabRow renders cleanly
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_research"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_research").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("research_tabs").assertIsDisplayed()
        composeRule.onNodeWithTag("github_search_btn").assertIsDisplayed()

        // Navigate back to Hub and open Marketplace to verify Auto Mode & Gap Detector controls
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_marketplace"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_marketplace").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("marketplace_auto_mode_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("detect_gaps_propose_btn").assertIsDisplayed()

        // Navigate back to Hub and open Automation & Device -> Screen UI tab
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_automation").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_automation_status_pill").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_blocked_reason_text").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_automation_list").performScrollToNode(hasTestTag("screen_describe_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_describe_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_automation_list").performScrollToNode(hasTestTag("screen_goal_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_goal_input").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_automation_list").performScrollToNode(hasTestTag("screen_goal_run_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_goal_run_btn").assertIsDisplayed()

        // Navigate back to Hub and open Identity & Persona (Phase 2)
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_identity"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_identity").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("identity_greeting_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_list").performScrollToNode(hasTestTag("identity_save_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("identity_save_btn").performScrollTo().assertIsDisplayed()

        // Navigate back to Hub and open Voice Rooms (Phase 4)
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_voice_rooms"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_voice_rooms").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_rooms_toggle_config_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_rooms_list").performScrollToNode(hasTestTag("voice_rooms_refresh_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_rooms_refresh_btn").performScrollTo().assertIsDisplayed()

        // Navigate back to Hub and open Music Studio (Lyria 3)
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_music_studio"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_music_studio").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_mode_clip_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("music_mode_pro_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("music_prompt_input").assertIsDisplayed()
        composeRule.onNodeWithTag("music_studio_list").performScrollToNode(hasTestTag("music_generate_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_generate_btn").performScrollTo().assertIsDisplayed()

        // Navigate back to Hub and open Website Forge (Phase 5)
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_website_forge"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_website_forge").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("website_forge_prompt_input").assertIsDisplayed()
        composeRule.onNodeWithTag("website_forge_list").performScrollToNode(hasTestTag("website_forge_tab_preview"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("website_forge_tab_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("website_forge_tab_code").assertIsDisplayed()
        composeRule.onNodeWithTag("website_forge_list").performScrollToNode(hasTestTag("website_forge_webview_container"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("website_forge_webview_container").assertIsDisplayed()
        composeRule.onNodeWithTag("website_forge_tab_code").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("website_forge_list").performScrollToNode(hasTestTag("website_forge_source_editor"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("website_forge_source_editor").assertIsDisplayed()

        // Navigate back to Hub -> Automation -> Social (6) tab and test Phase 6 State Machine UI
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_automation").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("automation_tab_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_task_status_pill").assertIsDisplayed()
        composeRule.onNodeWithTag("social_automation_list").performScrollToNode(hasTestTag("social_task_text_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_task_text_input").performScrollTo()
        composeRule.onNodeWithTag("social_task_text_input").performTextInput("Teena launch post")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_stage_task_btn").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_stage_task_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_automation_list").performScrollToNode(hasTestTag("social_task_status_pill"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_task_status_pill").assertTextContains("WAITING FOR CONFIRMATION")
        composeRule.onNodeWithTag("social_automation_list").performScrollToNode(hasTestTag("social_task_reject_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_task_reject_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("social_automation_list").performScrollToNode(hasTestTag("social_task_status_pill"))
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag("social_task_status_pill").assertTextContains("FAILED")
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("social_task_status_pill").assertTextContains("FAILED")
    }

    @Test
    fun shareToTeenaActivityExtractsTextAndImageAndHandlesNullIntentSafely() {
        val emptyParsed = ShareToTeenaActivity.extractShareFromIntent(null)
        assertEquals("", emptyParsed.text)
        assertTrue(emptyParsed.imageUris.isEmpty())

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_SUBJECT, "Lahore Sunset")
            putExtra(Intent.EXTRA_TEXT, "Check this photo in Teena")
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/media/42"))
        }
        val parsed = ShareToTeenaActivity.extractShareFromIntent(shareIntent)
        assertEquals("Lahore Sunset", parsed.subject)
        assertEquals("Check this photo in Teena", parsed.text)
        assertEquals(1, parsed.imageUris.size)

        val controller = Robolectric.buildActivity(ShareToTeenaActivity::class.java, shareIntent).setup()
        assertEquals(false, controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun phase7NotificationsTriggersAndKeyboardRenderSafely() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // BootReceiver & TeenaKeyboardService must initialize/handle null or boot actions without crashing
        val bootReceiver = TeenaBootReceiver()
        bootReceiver.onReceive(ctx, null)
        bootReceiver.onReceive(ctx, Intent("com.example.UNKNOWN_ACTION"))
        val kbController = Robolectric.buildService(TeenaKeyboardService::class.java).create()
        val kbView = kbController.get().onCreateInputView()
        assertNotNull(kbView)
        kbController.destroy()

        // Navigate to Hub -> Notifications -> Triggers & Keyboard
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_notifications"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_notifications").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("whatsapp_read_aloud_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("open_triggers_from_notif_btn").performClick()
        composeRule.waitForIdle()

        // Create a scheduled trigger and verify it appears in list
        composeRule.onNodeWithTag("triggers_list").performScrollToNode(hasTestTag("trigger_title_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trigger_title_input").performTextInput("Morning Brief")
        composeRule.onNodeWithTag("triggers_list").performScrollToNode(hasTestTag("trigger_payload_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trigger_payload_input").performTextInput("Summarize today reminders")
        composeRule.onNodeWithTag("triggers_list").performScrollToNode(hasTestTag("trigger_save_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trigger_save_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        var foundTrigger = false
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline && !foundTrigger) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            foundTrigger = runCatching {
                composeRule.onNodeWithTag("triggers_list").performScrollToNode(hasText("Morning Brief"))
                composeRule.onNodeWithText("Morning Brief").assertIsDisplayed()
                true
            }.getOrDefault(false)
            if (!foundTrigger) Thread.sleep(50)
        }
        assertTrue("Expected saved trigger 'Morning Brief' to appear in triggers list", foundTrigger)
        composeRule.onNodeWithTag("triggers_list").performScrollToNode(hasTestTag("keyboard_test_input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("keyboard_enable_settings_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("keyboard_picker_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("keyboard_test_input").assertIsDisplayed()
    }

    @Test
    fun phase8MapsSketchLatexAndDeviceAdminRenderAndWorkSafely() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. Optional Device Admin must default inactive and reject screen lock until user manually enables
        assertFalse(TeenaDeviceAdminReceiver.isAdminActive(ctx))
        assertTrue(TeenaDeviceAdminReceiver.lockScreenIfAuthorized(ctx, featureEnabled = false).isFailure)
        assertTrue(TeenaDeviceAdminReceiver.lockScreenIfAuthorized(ctx, featureEnabled = true).isFailure)
        val adminIntent = TeenaDeviceAdminReceiver.buildEnableAdminIntent(ctx)
        assertEquals(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, adminIntent.action)
        val adminReceiver = TeenaDeviceAdminReceiver()
        assertNotNull(adminReceiver.onDisableRequested(ctx, Intent()))

        // 2. SketchFileProvider renders strokes to Bitmap, saves PNG, and resolves content:// URI without FileProvider clash
        val sampleStrokes = listOf(
            SketchStroke(
                points = listOf(SketchPoint(0.1f, 0.2f), SketchPoint(0.8f, 0.7f)),
                colorArgb = android.graphics.Color.BLUE,
                strokeWidthPx = 8f
            )
        )
        val savedSketch = SketchFileProvider.saveSketchPng(ctx, sampleStrokes).getOrThrow()
        assertTrue(savedSketch.file.exists())
        assertTrue(savedSketch.file.length() > 0L)
        assertTrue(savedSketch.contentUri.toString().contains(SketchFileProvider.AUTHORITY_SUFFIX))
        assertEquals(Intent.ACTION_CHOOSER, savedSketch.shareIntent.action)
        SketchFileProvider.deleteSketch(savedSketch.file)

        // 3. Navigate Hub -> Interactive Maps (works with no location permission granted)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_maps"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_maps").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("maps_webview_container").assertIsDisplayed()
        composeRule.onNodeWithTag("maps_gps_btn").performScrollTo().assertIsDisplayed()

        // 4. Navigate Hub -> Sketch Pad and save a sketch via UI button
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_sketch"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_sketch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_canvas_box").assertIsDisplayed()
        composeRule.onNodeWithTag("sketch_screen_list").performScrollToNode(hasTestTag("sketch_save_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_save_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_screen_list").performScrollToNode(hasTestTag("sketch_feedback_text"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sketch_feedback_text").assertTextContains("Saved:", substring = true)
    }

    @Test
    fun phase9FaceDetectionRequiresCameraPermissionAndDoesNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val app = ctx as android.app.Application
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // 1. Ensure CAMERA permission is denied by default -> explicit check must fail
        shadowApp.denyPermissions(Manifest.permission.CAMERA)
        assertFalse(TeenaFaceDetectionEngine.hasExplicitCameraPermission(ctx))

        // 2. Analyzer must close ImageProxy immediately without reading image data when permission is missing
        val engine = TeenaFaceDetectionEngine(ctx)
        var lastState: TransientFaceDetectionState? = null
        val analyzer = engine.createAnalyzer(
            isFrontCameraProvider = { false },
            isEnabledProvider = { true }
        ) { state ->
            lastState = state
        }

        var closedCount = 0
        val fakeProxy = Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "close" -> {
                    closedCount++
                    null
                }
                "getImage" -> throw AssertionError("Must never access ImageProxy.image without CAMERA permission")
                else -> null
            }
        } as ImageProxy

        analyzer.analyze(fakeProxy)
        assertEquals(1, closedCount)
        assertNotNull(lastState)
        assertFalse(lastState!!.enabled)
        assertFalse(lastState!!.permissionGranted)

        // 3. When CAMERA permission is granted but toggle is OFF, analyzer also closes ImageProxy without processing
        shadowApp.grantPermissions(Manifest.permission.CAMERA)
        assertEquals(PackageManager.PERMISSION_GRANTED, ctx.checkSelfPermission(Manifest.permission.CAMERA))
        val disabledAnalyzer = engine.createAnalyzer(
            isFrontCameraProvider = { false },
            isEnabledProvider = { false }
        ) { state ->
            lastState = state
        }
        disabledAnalyzer.analyze(fakeProxy)
        assertEquals(2, closedCount)
        val disabledState = lastState!!
        assertFalse(disabledState.enabled)
        assertTrue(disabledState.permissionGranted)
        engine.closeDetector()

        // 4. Reset CAMERA to denied and verify Camera & Media tab renders BLOCKED status and disabled switch safely
        shadowApp.denyPermissions(Manifest.permission.CAMERA)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_automation").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("automation_tab_3").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("camera_media_list").performScrollToNode(hasTestTag("camera_face_detection_status"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("camera_face_detection_status").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_face_detection_status").assertTextContains("BLOCKED", substring = true)
        composeRule.onNodeWithTag("camera_face_detection_switch").performScrollTo().assertIsOff()
    }

    @Test
    fun phase10RoomsMediaProjectionFgsAndOptionalFirebaseWorkWithoutCrash() = kotlinx.coroutines.test.runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. Optional Firebase Auth & FCM stay safely DISABLED when google-services.json is absent
        assertFalse(TeenaFirebaseBridge.isGoogleServicesConfigured(ctx))
        val fbBridge = TeenaFirebaseBridge(ctx)
        assertEquals("DISABLED", fbBridge.state.value.statusLabel)
        assertTrue(fbBridge.signInWithEmailPassword("user@example.com", "secret12").isFailure)
        assertTrue(fbBridge.refreshFcmToken().isFailure)

        // 2. TeenaFirebaseMessagingService no-ops safely when unconfigured
        val fcmSvc = Robolectric.buildService(TeenaFirebaseMessagingService::class.java).create()
        fcmSvc.get().onNewToken("fake_token_should_ignore")
        fcmSvc.destroy()

        // 3. ScreenShare Foreground Service builds ongoing notification cleanly
        val notif = TeenaScreenShareService.buildOngoingNotification(ctx)
        assertNotNull(notif)

        // 4. Rooms screen disables unconfigured backend & Firebase controls cleanly
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_hub").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_grid").performScrollToNode(hasTestTag("hub_tile_voice_rooms"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hub_tile_voice_rooms").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_rooms_list").performScrollToNode(hasTestTag("voice_rooms_refresh_btn"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_rooms_refresh_btn").assertIsNotEnabled()
        composeRule.onNodeWithTag("voice_rooms_direct_join_btn").assertIsNotEnabled()

        composeRule.onNodeWithTag("voice_rooms_list").performScrollToNode(hasTestTag("rooms_firebase_card"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rooms_firebase_status_pill").assertTextContains("DISABLED", substring = true)
        composeRule.onNodeWithTag("rooms_firebase_signin_btn").assertIsNotEnabled()
        composeRule.onNodeWithTag("rooms_firebase_fcm_btn").assertIsNotEnabled()
    }
}

