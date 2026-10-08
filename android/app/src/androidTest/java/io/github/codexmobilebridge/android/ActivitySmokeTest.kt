package io.github.codexmobilebridge.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivitySmokeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun keyboardBackRotationThemeAndSavedConnection() {
        lateinit var vm:BridgeViewModel
        ui.runOnIdle {
            vm=ViewModelProvider(ui.activity)[BridgeViewModel::class.java]
            vm.showConnections();vm.connections.toList().forEach(vm::removeConnection);vm.preference("language","en")
        }
        ui.onNodeWithText("Add computer").performClick()
        ui.onNodeWithText("Name").performClick()
        ui.waitUntil(10000){ui.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true}
        ui.onNodeWithText("Name").performTextInput("Smoke Computer")
        ui.onNodeWithText("HTTPS URL").performTextReplacement("https://gateway.example.com")
        Espresso.pressBack()
        ui.onNodeWithText("Save").performClick();ui.onNodeWithText("Smoke Computer").assertExists()
        ui.runOnIdle{ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(10000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithText("Smoke Computer").assertExists()
        ui.onNodeWithContentDescription("Appearance").performClick()
        ui.onAllNodesWithText("System").onFirst().performClick();ui.onNodeWithText("Dark").performClick()
        Espresso.pressBack();ui.waitUntil(10000){vm.tool==null};assertEquals("dark",vm.theme)
        ui.activityRule.scenario.recreate();ui.onNodeWithText("Smoke Computer").assertExists()
        ui.runOnIdle {
            val recreated=ViewModelProvider(ui.activity)[BridgeViewModel::class.java]
            assertEquals("dark",recreated.theme);ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        ui.waitUntil(10000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
    }
}
