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
    private var projects=JsonArray(emptyList())
    private var paginated=false
    private var pageFailure=false
    private var unknown=false
    private var terminalUnavailable=false
    private var catalogFailed=false
    private var authUnavailable=false
    private var liveCookieRequired=false
    private lateinit var serverCertificates:HandshakeCertificates
    @Before fun prepare(){
        val app=ApplicationProvider.getApplicationContext<Application>();app.getSharedPreferences("bridge",0).edit().clear().commit()
        vm=BridgeViewModel(app);vm.preference("language","en")
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        serverCertificates=HandshakeCertificates.Builder().heldCertificate(cert).build()
        server=MockWebServer();server.useHttps(serverCertificates.sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            calls.add(request);val path=request.requestUrl!!.encodedPath
            if(paginated && path=="/api/sessions" && request.method=="GET") {
                val offset=request.requestUrl!!.queryParameter("offset")!!.toInt()
                if(offset>0 && pageFailure)return MockResponse().setResponseCode(503).setBody("{\"error\":\"Page temporarily unavailable\"}")
                val rows=if(request.requestUrl!!.queryParameter("q")=="filtered")listOf(payload("id" to id,"host" to "local","title" to "Filtered chat")) else (offset until offset+if(offset==0)100 else 2).map {payload("id" to "chat-$it","host" to "local","title" to "Paged $it")}
                return MockResponse().setBody(payload("sessions" to JsonArray(rows)).toString())
            }
            if(path.endsWith("/workspace/download"))return MockResponse().setBody("File content").addHeader("Content-Type","text/plain").addHeader("ETag","fixture-download")
            if(terminalUnavailable && path.endsWith("/terminal"))return MockResponse().setResponseCode(404).setBody("{\"error\":\"Terminal expired\"}")
            if(authUnavailable && path=="/api/auth")return MockResponse().setResponseCode(503).setBody("{\"error\":\"Gateway offline\"}")
            if(path.endsWith("/catalog")) {
                if(catalogFailed)return MockResponse().setResponseCode(503).setBody("{\"error\":\"Models temporarily unavailable\"}")
                val kind=request.requestUrl!!.queryParameter("kind")
                if(!path.startsWith("/api/desktop-sessions/") && kind==null)return MockResponse().setResponseCode(500).setBody("{\"error\":\"Skills read failed in combined catalog\"}")
                val rows=JsonArray(listOf(payload("id" to "alpha","name" to "Model Alpha","efforts" to listOf("medium","high")),payload("id" to "beta","name" to "Model Beta","efforts" to listOf("low","medium"))))
                return MockResponse().setBody(if(kind=="skills")payload("skills" to JsonArray(listOf(payload("id" to "skill-one","name" to "Native Skill")))).toString() else payload("models" to rows,"currentModel" to "alpha","currentEffort" to "medium","modelSource" to "api","modelAccessName" to "Test provider","capabilities" to payload("models" to true,"effort" to true)).toString())
            }
            val meta=payload("title" to "Native project","cwd" to "/test/project","connected" to true,"status" to "idle")
            val value=when {
                path=="/api/auth"->payload("authenticated" to (!liveCookieRequired || request.getHeader("Cookie")?.contains("session=live")==true),"csrf" to "native-csrf")
                path=="/api/mobile/update"->payload("supported" to true,"release" to AppUpdate("9.0.0",999,"a".repeat(64),"https://mac.lqilt.top/android/Codex-Mobile-Bridge-9.0.0.apk","Fixture update",1024).metadata())
                path=="/api/projects"->payload("projects" to projects)
                path=="/api/sessions" && request.method=="POST"->payload("id" to id,"host" to "local")
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
            return MockResponse().setBody(value.toString()).apply{if(path=="/api/auth")addHeader("Set-Cookie","session=live; Secure; HttpOnly; Path=/")}.let{if(path.endsWith("/changes") || path.endsWith("/detail"))it.setBodyDelay(300,TimeUnit.MILLISECONDS) else it}
        }};server.start()
        val address=server.url("/").toString()
        ui.runOnIdle{vm.saveConnection("Native Mac",address);vm.gatewayFactory={row->Gateway(row,vm.store::get,vm.store::put){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)}}}
        ui.setContent{BridgeApp(vm)}
        ui.runOnIdle{vm.select(vm.connections.single())};ui.waitUntil(10000){vm.authenticated && !vm.busy}
    }
    @After fun cleanup(){ui.runOnIdle{vm.pause()};server.shutdown()}
    private fun capture(name:String){val bitmap=ui.onRoot().captureToImage().asAndroidBitmap();File(ApplicationProvider.getApplicationContext<Application>().filesDir,name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun openCodex(){ui.runOnIdle{vm.openChat(id)};ui.waitUntil(10000){vm.timeline.sequence>=0}}
    @Test fun nativePaginationLoadsNearEndKeepsFailedPageAndRetriesManually() {
        paginated=true
        ui.runOnIdle{vm.refreshList()};ui.waitUntil(10000){!vm.busy && vm.sessions.size==100}
        pageFailure=true
        ui.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        ui.waitUntil(10000){!vm.busy && vm.error=="Page temporarily unavailable"}
        assertEquals(100,vm.sessions.size);assertTrue(vm.listHasMore)
        val failed=calls.count{it.requestUrl!!.encodedPath=="/api/sessions" && it.requestUrl!!.queryParameter("offset")=="100"}
        Thread.sleep(500)
        assertEquals(failed,calls.count{it.requestUrl!!.encodedPath=="/api/sessions" && it.requestUrl!!.queryParameter("offset")=="100"})
        pageFailure=false
        ui.runOnIdle{vm.loadMoreSessions()};ui.waitUntil(10000){!vm.busy && vm.sessions.size==102}
        assertFalse(vm.listHasMore)
        ui.runOnIdle{vm.search="filtered";vm.refreshList()};ui.waitUntil(10000){!vm.busy && vm.sessions.size==1}
        assertEquals("Filtered chat",vm.sessions.single().str("title"))
        assertEquals("0",calls.last{it.requestUrl!!.encodedPath=="/api/sessions"}.requestUrl!!.queryParameter("offset"))
    }
    @Test fun nativeOpenFileDownloadsFromCapturedAuthenticatedWorkspace() {
        openCodex();ui.runOnIdle{vm.openWorkspace("files")}
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("Open file").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("Open file").performClick()
        ui.waitUntil(10000){calls.any{it.requestUrl!!.encodedPath.endsWith("/workspace/download")}}
        val request=calls.last{it.requestUrl!!.encodedPath.endsWith("/workspace/download")}
        assertEquals("hello.txt",request.requestUrl!!.queryParameter("path"))
        assertEquals("1",request.requestUrl!!.queryParameter("explicit"))
        assertTrue(request.getHeader("Cookie")!!.contains("session=live"))
        assertFalse(calls.any{it.requestUrl!!.encodedPath.endsWith(".js")})
    }
    @Test fun newCodexChatAllowsNoProjectWithOrWithoutSavedProjects(){
        for(savedProjects in listOf(JsonArray(emptyList()),JsonArray(listOf(payload("key" to "local|saved","name" to "Saved project","host" to "local","cwd" to "/saved/project"))))){
            projects=savedProjects
            ui.runOnIdle{vm.openTool("new")};ui.waitUntil(10000){!vm.toolLoading}
            ui.onNodeWithText("No project").assertExists()
            ui.onNodeWithText("Create an independent chat on this computer").assertExists()
            ui.onNodeWithText("Create chat").assertIsEnabled().performClick()
            ui.waitUntil(10000){!vm.busy && vm.tool==null && vm.target?.id==id}
            val request=calls.last{it.requestUrl!!.encodedPath=="/api/sessions" && it.method=="POST"}
            val body=json.parseToJsonElement(request.body.clone().readUtf8()) as JsonObject
            assertEquals("local|independent",body.str("project"));assertEquals("New chat",body.str("title"))
            java.util.UUID.fromString(body.str("id"));assertEquals("native-csrf",request.getHeader("X-CSRF-Token"))
        }
        assertEquals(2,calls.count{it.requestUrl!!.encodedPath=="/api/sessions" && it.method=="POST"})
    }
    @Test fun newCodexChatCanStillSelectRemoteProject(){
        projects=JsonArray(listOf(payload("key" to "remote|saved","name" to "Remote project","host" to "remote","cwd" to "/remote/project")))
        ui.runOnIdle{vm.openTool("new")};ui.waitUntil(10000){!vm.toolLoading}
        ui.onNodeWithText("No project").performClick();ui.onNodeWithText("Remote project · remote").performClick()
        ui.onNodeWithText("/remote/project").assertExists()
        ui.onNodeWithText("Name (optional)").performTextInput("  Remote task  ")
        ui.onNodeWithText("Create chat").performClick();ui.waitUntil(10000){!vm.busy && vm.tool==null}
        val request=calls.last{it.requestUrl!!.encodedPath=="/api/sessions" && it.method=="POST"}
        val body=json.parseToJsonElement(request.body.clone().readUtf8()) as JsonObject
        assertEquals("remote|saved",body.str("project"));assertEquals("Remote task",body.str("title"))
    }
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
    @Test fun nativeModelPickerDoesNotLoadSkillsAndAppliesSelectedModel(){
        openCodex();ui.runOnIdle{vm.openTool("model")};ui.waitUntil(10000){!vm.toolLoading && vm.toolData.rows("models").size==2}
        val request=calls.last{it.requestUrl!!.encodedPath.endsWith("/catalog")}
        assertEquals("models",request.requestUrl!!.queryParameter("kind"));assertNull(request.requestUrl!!.queryParameter("refresh"))
        ui.onNodeWithText("Model source: Current account's API provider · Test provider").assertExists()
        ui.onNodeWithText("Model Alpha").performClick();ui.onNodeWithText("Model Beta").performClick();ui.onNodeWithText("Apply settings").performClick()
        ui.waitUntil(10000){calls.any{it.requestUrl!!.encodedPath.endsWith("/settings")}}
        val settings=calls.last{it.requestUrl!!.encodedPath.endsWith("/settings")}
        assertEquals("beta",(json.parseToJsonElement(settings.body.clone().readUtf8()) as JsonObject).str("model"))
        ui.waitUntil(10000){!vm.busy};ui.runOnIdle{vm.openTool("skills")};ui.waitUntil(10000){!vm.toolLoading && vm.toolData.rows("skills").isNotEmpty()}
        assertEquals("skills",calls.last{it.requestUrl!!.encodedPath.endsWith("/catalog")}.requestUrl!!.queryParameter("kind"))
    }
    @Test fun modelErrorsCanBeRetriedAndClaudeUsesDesktopCatalog(){
        openCodex();catalogFailed=true;ui.runOnIdle{vm.openTool("model")};ui.waitUntil(10000){!vm.toolLoading && vm.error=="Models temporarily unavailable"}
        ui.onNodeWithText("Models temporarily unavailable").assertExists();catalogFailed=false
        ui.onNodeWithText("Retry model list").performClick();ui.waitUntil(10000){!vm.toolLoading && vm.toolData.rows("models").size==2}
        assertEquals("true",calls.last{it.requestUrl!!.encodedPath.endsWith("/catalog")}.requestUrl!!.queryParameter("refresh"))
        ui.runOnIdle{vm.closeTool();vm.chooseProvider("claude")};ui.waitUntil(10000){!vm.busy};ui.runOnIdle{vm.openChat(id,"desktop:claude")};ui.waitUntil(10000){vm.timeline.sequence>=0}
        ui.runOnIdle{vm.openTool("model")};ui.waitUntil(10000){!vm.toolLoading && vm.toolData.rows("models").size==2}
        val request=calls.last{it.requestUrl!!.encodedPath.endsWith("/catalog")};assertEquals("/api/desktop-sessions/claude/catalog",request.requestUrl!!.encodedPath);assertEquals(id,request.requestUrl!!.queryParameter("sessionId"));assertNull(request.requestUrl!!.queryParameter("kind"))
        ui.onNodeWithText("Model Alpha").performClick();ui.onNodeWithText("Model Beta").performClick();ui.onNodeWithText("Apply settings").performClick()
        ui.waitUntil(10000){calls.any{it.requestUrl!!.encodedPath=="/api/desktop-sessions/claude/settings"}}
        val body=json.parseToJsonElement(calls.last{it.requestUrl!!.encodedPath=="/api/desktop-sessions/claude/settings"}.body.clone().readUtf8()) as JsonObject
        assertEquals("beta",body.str("model"));assertEquals(id,body.str("sessionId"));assertTrue(body.str("id").isNotEmpty())
    }
    @Test fun homeShowsEachGatewayAndRefreshesFailureWithoutSigningIn(){
        val other=MockWebServer();other.useHttps(serverCertificates.sslSocketFactory(),false);other.enqueue(MockResponse().setBody("{\"authenticated\":false}"));other.start()
        val address=other.url("/").toString()
        try{
            ui.runOnIdle{assertTrue(vm.store.get("cookies:${vm.selected!!.id}").isNullOrBlank());liveCookieRequired=true;vm.showConnections();vm.saveConnection("Other Mac",address)}
            ui.waitUntil(10000){vm.connectionStates.values.containsAll(listOf("connected","login"))}
            ui.onNodeWithText("Gateway connected").assertExists();ui.onNodeWithText("Gateway online · Sign in required").assertExists()
            val probe=other.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals("GET",probe.method);assertEquals("/api/auth",probe.requestUrl!!.encodedPath);assertNull(probe.getHeader("Cookie"));assertEquals(0,probe.bodySize)
            authUnavailable=true;other.enqueue(MockResponse().setBody("{\"authenticated\":false}"))
            ui.onNodeWithText("Refresh status").performClick();ui.waitUntil(10000){vm.connectionStates[vm.selected!!.id]=="unreachable"};ui.onNodeWithText("Gateway unreachable").assertExists()
            ui.runOnIdle{vm.pause()};val count=calls.size;Thread.sleep(500);assertEquals(count,calls.size)
        }finally{ui.runOnIdle{vm.stopConnectionChecks()};other.shutdown()}
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
    @Test fun computerUpdateCheckRetainsPersonalSourceAndNativeUi() {
        ui.waitUntil(10_000){!vm.updateBusy}
        ui.runOnIdle {vm.openTool("updates")}
        ui.onNodeWithText("Check through computer").performClick()
        ui.waitUntil(10_000){vm.availableUpdate?.code==999 && !vm.updateBusy}
        ui.onNodeWithText("New version: 9.0.0").assertExists()
        ui.onNodeWithText("Download through computer").assertExists()
        assertEquals("https://mac.lqilt.top/android/Codex-Mobile-Bridge-9.0.0.apk",vm.availableUpdate!!.apkUrl)
        val request=calls.last {it.requestUrl!!.encodedPath=="/api/mobile/update"}
        assertEquals(BuildConfig.VERSION_NAME.removeSuffix("-debug"),request.requestUrl!!.queryParameter("current"))
        assertEquals("GET",request.method)
    }

}
