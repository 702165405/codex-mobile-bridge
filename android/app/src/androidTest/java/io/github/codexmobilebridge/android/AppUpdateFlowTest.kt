package io.github.codexmobilebridge.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdateFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun versionPageAvailableWithoutGatewayAndCorruptDownloadRemoved() {
        ui.runOnIdle {val vm=ViewModelProvider(ui.activity)[BridgeViewModel::class.java];vm.showConnections();vm.preference("language","en");vm.openTool("updates")}
        ui.onNodeWithText("Codex Mobile Bridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        ui.onNodeWithText("Check for updates").assertExists()
        val update=AppUpdate("9.0.0",999,"a".repeat(64),"https://mac.lqilt.top/android/app.apk","")
        val client=OkHttpClient.Builder().addInterceptor {chain ->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(ResponseBody.create(null,"corrupt APK")).build()}.build()
        val updates=AppUpdates(client)
        assertTrue(runCatching {runBlocking {updates.download(ui.activity,update)}}.isFailure)
        assertFalse(File(ui.activity.cacheDir,"updates/update.apk").exists())
        val ownApk=File(ui.activity.applicationInfo.sourceDir)
        assertTrue(runCatching {updates.verifyPackage(ui.activity,ownApk,update)}.isFailure)
    }
}
