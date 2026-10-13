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
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import okio.source
import okio.buffer
import kotlinx.serialization.json.jsonObject

@RunWith(AndroidJUnit4::class)
class AppUpdateFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Before fun ready(){var vm:BridgeViewModel?=null;ui.runOnIdle {vm=ViewModelProvider(ui.activity)[BridgeViewModel::class.java]};ui.waitUntil(10_000){vm?.updateBusy==false}}
    @Test fun versionPageAvailableWithoutGatewayAndCorruptDownloadRemoved() {
        ui.runOnIdle {val vm=ViewModelProvider(ui.activity)[BridgeViewModel::class.java];vm.showConnections();vm.preference("language","en");vm.openTool("updates")}
        ui.onNodeWithText("Codex Mobile Bridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        ui.onNodeWithText("Check for updates").assertExists()
        val update=AppUpdate("9.0.0",999,"a".repeat(64),"https://mac.lqilt.top/android/app.apk","")
        val client=OkHttpClient.Builder().addInterceptor {chain ->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(ResponseBody.create(null,"corrupt APK")).build()}.build()
        val updates=AppUpdates(client)
        assertTrue(runCatching {runBlocking {updates.download(ui.activity,update)}}.isFailure)
        assertFalse(updates.packageFile(ui.activity,update).exists())
        val ownApk=File(ui.activity.applicationInfo.sourceDir)
        assertTrue(runCatching {updates.verifyPackage(ui.activity,ownApk,update)}.isFailure)
    }
    @Test fun interruptedDownloadSurvivesRestartAndResumesButNeverInstallsInvalidApk() = runBlocking {
        val context=ui.activity
        val bytes=ByteArray(7000){(it%251).toByte()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val update=AppUpdate("9.0.0",999,hash,"https://mac.lqilt.top/android/app.apk","",bytes.size.toLong())
        val requests=mutableListOf<Request>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();requests+=request
            assertNull(request.header("Cookie"));assertNull(request.header("Authorization"));assertNull(request.header("X-CSRF-Token"))
            val first=request.header("Range")==null
            val input=if(first)object:InputStream(){
                var copied=0
                override fun read():Int=error("unused")
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(copied>=3000)throw IOException("fixture interrupted")
                    val count=minOf(length,3000-copied);bytes.copyInto(buffer,offset,copied,copied+count);copied+=count;return count
                }
            } else bytes.copyOfRange(3000,bytes.size).inputStream()
            val body=object:ResponseBody(){override fun contentType():MediaType?=null
                override fun contentLength()=if(first)bytes.size.toLong() else (bytes.size-3000).toLong()
                override fun source()=input.source().buffer()}
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(first)200 else 206).message("fixture")
                .header("ETag","\"fixture\"").apply{if(!first)header("Content-Range","bytes 3000-6999/7000")}.body(body).build()
        }.build()
        val updates=AppUpdates(client);updates.discard(context)
        try {
            assertTrue(runCatching {updates.download(context,update)}.isFailure)
            assertEquals(3000L,updates.packageFile(context,update).length())
            val record=File(context.filesDir,"updates/session.json")
            assertTrue("Partial download metadata must survive",record.isFile)
            assertEquals(update,parseAppUpdate(json.parseToJsonElement(record.readText()).jsonObject))
            assertEquals(File(updates.packageFile(context,update).parentFile!!.canonicalFile,updates.packageFile(context,update).name),updates.packageFile(context,update).canonicalFile)
            val restored=AppUpdates(client).restore(context)!!
            assertEquals(update,restored.update);assertFalse(restored.verified);assertFalse(restored.permissionPending)
            var progress=0L
            assertTrue(runCatching {updates.download(context,update){done,_->progress=done}}.isFailure)
            assertEquals(7000L,progress)
            assertEquals("bytes=3000-",requests.last().header("Range"));assertEquals("\"fixture\"",requests.last().header("If-Range"))
            assertFalse(updates.packageFile(context,update).exists());assertNull(updates.restore(context))
        } finally {updates.discard(context)}
    }
    @Test fun staleAndTamperedSessionIsDiscardedAndMetadataCannotBeShared() = runBlocking {
        val context=ui.activity;val updates=AppUpdates();updates.discard(context)
        val installed=File(context.applicationInfo.sourceDir)
        val hash=MessageDigest.getInstance("SHA-256").digest(installed.readBytes()).joinToString(""){"%02x".format(it)}
        val update=AppUpdate("9.0.0",999,hash,"https://mac.lqilt.top/android/app.apk","",installed.length())
        val file=updates.packageFile(context,update);installed.copyTo(file,overwrite=true)
        val metadata=File(context.filesDir,"updates/session.json")
        metadata.writeText(kotlinx.serialization.json.JsonObject(update.metadata()+payload("verified" to true,"permissionPending" to true)).toString())
        assertTrue(runCatching {androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",metadata)}.isFailure)
        assertNull(updates.restore(context));assertFalse(file.exists());assertFalse(metadata.exists())
    }

}
