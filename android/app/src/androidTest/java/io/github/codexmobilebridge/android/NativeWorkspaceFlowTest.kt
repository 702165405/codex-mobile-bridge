package io.github.codexmobilebridge.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeWorkspaceFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var vm:BridgeViewModel
    private lateinit var server:MockWebServer
    private val calls=CopyOnWriteArrayList<RecordedRequest>()
    private val id="12345678-1234-1234-1234-123456789abc"
    private var unknown=false
    private var terminalUnavailable=false
    @Before fun prepare(){
        val app=ApplicationProvider.getApplicationContext<Application>();app.getSharedPreferences("bridge",0).edit().clear().commit()
        vm=BridgeViewModel(app);vm.preference("language","en")
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            calls.add(request);val path=request.requestUrl!!.encodedPath
            if(terminalUnavailable && path.endsWith("/terminal"))return MockResponse().setResponseCode(404).setBody("{\"error\":\"Terminal expired\"}")
            val meta=payload("title" to "Native project","cwd" to "/test/project","connected" to true,"status" to "idle")
            val value=when {
                path=="/api/auth"->payload("authenticated" to true,"csrf" to "native-csrf")
                path=="/api/sessions"->payload("sessions" to JsonArray(listOf(payload("id" to id,"host" to "local","title" to "Native project"))))
                path=="/api/activity"->payload("sessions" to JsonArray(emptyList()))
                path.endsWith("/timeline") || path.endsWith("/changes")->payload("epoch" to "native","sequence" to 1,"meta" to meta,"rows" to JsonArray(listOf(payload("key" to "native-msg","order" to 1,"role" to "assistant","text" to "Native history"))))
                path=="/api/desktop-sessions/claude/list"->payload("connected" to true,"sessions" to JsonArray(listOf(payload("id" to id,"backend" to "code","title" to "Claude native"))))
                path=="/api/desktop-sessions/claude/detail"->payload("connected" to true,"session" to JsonObject(meta+payload("id" to id,"title" to "Claude native")),"capabilities" to payload("send" to true),"messages" to JsonArray(listOf(payload("id" to "claude-msg","role" to "assistant","text" to "Claude native history"))))
                path=="/api/desktop-sessions/claude/send"->payload("status" to if(unknown)"unknown" else "accepted")
                path.endsWith("/workspace")->payload("path" to "","project" to "project","total" to 1,"entries" to JsonArray(listOf(payload("name" to "hello.txt","path" to "hello.txt","size" to 5,"kind" to "file"))))
                path.endsWith("/workspace/preview")->payload("name" to "hello.txt","path" to "hello.txt","kind" to "text","text" to "File preview is native")
                path.endsWith("/workspace/git-status")->payload("available" to true,"scope" to ".","branch" to "main","version" to "fresh-state","entries" to JsonArray(listOf(payload("path" to "hello.txt","index" to ".","worktree" to "M","untracked" to false))))
                path.endsWith("/workspace/git-action")->payload("outcome" to "success","message" to "Staged","state" to payload("version" to "next-state"))
                path.endsWith("/terminal")->if(request.method=="POST")payload("output" to "Native terminal\r\n","cursor" to 17,"running" to true) else if(request.requestUrl!!.queryParameter("id")!=null)payload("output" to "","cursor" to 17,"running" to true) else payload("mode" to "pty","shell" to "/bin/zsh","cwd" to "/test/project")
                else->payload()
            }
            return MockResponse().setBody(value.toString()).let{if(path.endsWith("/changes") || path.endsWith("/detail"))it.setBodyDelay(300,TimeUnit.MILLISECONDS) else it}
        }};server.start()
        val address=server.url("/").toString()
        ui.runOnIdle{vm.saveConnection("Native Mac",address);vm.gatewayFactory={row->Gateway(row,vm.store::get,vm.store::put){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)}}}
        ui.setContent{BridgeApp(vm)}
        ui.runOnIdle{vm.select(vm.connections.single())};ui.waitUntil(10000){vm.authenticated && !vm.busy}
    }
    @After fun cleanup(){ui.runOnIdle{vm.pause()};server.shutdown()}
    private fun capture(name:String){val bitmap=ui.onRoot().captureToImage().asAndroidBitmap();File(ApplicationProvider.getApplicationContext<Application>().filesDir,name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun openCodex(){ui.runOnIdle{vm.openChat(id)};ui.waitUntil(10000){vm.timeline.sequence>=0}}
    @Test fun claudeUsesNativeChatAndRetriesOnlyOriginalSubmission(){
        ui.runOnIdle{vm.chooseProvider("claude")};ui.waitUntil(10000){!vm.busy && vm.sessions.any{it.str("title")=="Claude native"}}
        ui.onNodeWithText("Claude native",useUnmergedTree=true).performClick();ui.waitUntil(10000){vm.timeline.sequence>=0}
        ui.onNodeWithText("Claude native history",useUnmergedTree=true).assertExists()
        capture("native-claude");unknown=true;ui.runOnIdle{vm.editDraft(Draft(text="Claude draft"));vm.send()};ui.waitUntil(10000){!vm.busy};assertEquals("Claude draft",vm.draft.text)
        unknown=false;ui.runOnIdle{vm.send()};ui.waitUntil(10000){!vm.busy && vm.draft.text.isBlank()}
        val sends=calls.filter{it.requestUrl!!.encodedPath=="/api/desktop-sessions/claude/send"};assertEquals(2,sends.size)
        val bodies=sends.map{json.parseToJsonElement(it.body.clone().readUtf8()) as JsonObject}
        assertEquals(bodies[0].str("id"),bodies[1].str("id"));assertEquals(id,bodies[0].str("sessionId"));assertEquals("native-csrf",sends[0].getHeader("X-CSRF-Token"))
        ui.runOnIdle{vm.showSessions()};ui.waitUntil(10000){!vm.busy};ui.runOnIdle{vm.openChat(id,"desktop:claude");assertEquals(-1,vm.timeline.sequence);assertTrue(vm.timeline.messages.isNotEmpty());assertFalse(vm.timeline.meta.bool("connected"));assertTrue(vm.timeline.meta.rows("requests").isEmpty())}
    }
    @Test fun filesAndGitAreNativeAndMutationsCarryFreshVersion(){
        openCodex();ui.runOnIdle{vm.openWorkspace("files")}
        ui.waitUntil(10000){calls.any{it.requestUrl!!.encodedPath.endsWith("/workspace")}}
        ui.onNodeWithText("hello.txt",useUnmergedTree=true).performClick();ui.waitUntil(10000){ui.onAllNodesWithText("File preview is native",substring=true,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("File preview is native",substring=true,useUnmergedTree=true).assertExists()
        ui.onNodeWithContentDescription("Close preview").performClick();ui.onNodeWithText("Git",useUnmergedTree=true).performClick()
        ui.waitUntil(10000){calls.any{it.requestUrl!!.encodedPath.endsWith("/git-status")}}
        ui.onNodeWithText("Stage",useUnmergedTree=true).performClick();ui.waitUntil(10000){calls.any{it.requestUrl!!.encodedPath.endsWith("/git-action")}}
        val request=calls.first{it.requestUrl!!.encodedPath.endsWith("/git-action")};val body=json.parseToJsonElement(request.body.clone().readUtf8()) as JsonObject
        assertEquals("fresh-state",body.str("version"));assertEquals("hello.txt",body.str("path"));assertEquals("stage",body.str("action"));assertEquals("native-csrf",request.getHeader("X-CSRF-Token"))
        capture("native-git");assertFalse(calls.any{it.requestUrl!!.encodedPath.endsWith(".js") || it.requestUrl!!.encodedPath=="/"})
    }
    @Test fun discardedInputIsNotReplayedWhenExpiredTerminalReopens(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        lateinit var work:NativeWorkspaceSession
        lateinit var target:ChatTarget
        ui.runOnIdle{target=ChatTarget(vm.selected!!.id,id);work=NativeWorkspaceSession(vm.api!!,target,vm.store,app,"terminal");terminalUnavailable=true;work.terminalAction("input","original command\r")}
        ui.waitUntil(10000){!work.busy && work.inputUnconfirmed}
        val failed=calls.last{it.method=="POST" && it.requestUrl!!.encodedPath.endsWith("/terminal")}
        val original=json.parseToJsonElement(failed.body.clone().readUtf8()) as JsonObject
        ui.runOnIdle{work.dispose();work=NativeWorkspaceSession(vm.api!!,target,vm.store,app,"terminal");assertTrue(work.inputUnconfirmed);work.discardTerminalInput();assertFalse(work.inputUnconfirmed);assertNull(vm.store.get("terminal-input:${target.key}"));terminalUnavailable=false;work.terminalAction("open")}
        ui.waitUntil(10000){!work.busy && work.terminal.bool("running")}
        val writes=calls.filter{it.method=="POST" && it.requestUrl!!.encodedPath.endsWith("/terminal")}
        assertEquals(2,writes.size)
        val reopened=json.parseToJsonElement(writes.last().body.clone().readUtf8()) as JsonObject
        assertEquals("open",reopened.str("action"));assertNotEquals(original.str("id"),reopened.str("id"));assertFalse(reopened.containsKey("inputId"))
        ui.runOnIdle{work.dispose()}
    }
    @Test fun persistentTerminalCarriesStableInputIdentityAndChatContext(){
        openCodex();ui.runOnIdle{vm.openWorkspace("terminal")};ui.onNodeWithText("Open terminal",useUnmergedTree=true).performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("Native terminal",substring=true,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("Native terminal",substring=true,useUnmergedTree=true).assertExists()
        ui.onNodeWithText("Enter a command or reply").performTextInput("pwd");ui.onNodeWithText("Send to terminal",useUnmergedTree=true).performClick()
        ui.waitUntil(10000){calls.any{it.method=="POST" && it.body.clone().readUtf8().contains("inputId")}}
        capture("native-terminal");val request=calls.first{it.method=="POST" && it.body.clone().readUtf8().contains("inputId")};val body=json.parseToJsonElement(request.body.clone().readUtf8()) as JsonObject
        assertEquals("pwd\r",body.str("data"));assertTrue(runCatching{java.util.UUID.fromString(body.str("inputId"))}.isSuccess);assertEquals("local",request.requestUrl!!.queryParameter("host"));assertEquals("native-csrf",request.getHeader("X-CSRF-Token"))
    }
}
