package io.github.codexmobilebridge.android

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var vm:BridgeViewModel
    private val servers=mutableListOf<MockWebServer>()
    private val calls=CopyOnWriteArrayList<Pair<String,RecordedRequest>>()
    private var unknownSend=false
    private var failUpload=false
    private var offline=false
    private var expired=false
    @Volatile private var activityStatus="idle"
    private var coldActivity=false
    private val activityRequests=java.util.concurrent.atomic.AtomicInteger(0)
    private var paging=false
    private var askQuestion=false
    private val chatId="11111111-1111-4111-8111-111111111111"
    @Before fun setup() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("bridge",Context.MODE_PRIVATE).edit().clear().commit()
        ui.runOnIdle{vm=BridgeViewModel(app);vm.preference("language","en")}
    }
    @After fun cleanup(){ui.runOnIdle{vm.pause();vm.showConnections()};servers.forEach{it.shutdown()}}
    private fun computer(label:String): Connection {
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server=MockWebServer();servers+=server
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            calls+=label to request
            val path=request.path!!.substringBefore('?')
            if(offline)return MockResponse().setResponseCode(503).setBody("{\"error\":\"offline\"}")
            if(expired && path.endsWith("/changes"))return MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}")
            if(failUpload && path.endsWith("/uploads"))return MockResponse().setResponseCode(503).setBody("{\"error\":\"upload failed\"}")
            val meta=payload("connected" to true,"title" to "$label chat","status" to "idle","requests" to JsonArray(if(askQuestion)listOf(payload("id" to 7,"method" to "item/tool/requestUserInput","supported" to true,"params" to payload("questions" to JsonArray(listOf(payload("id" to "direction","question" to "Choose direction?")))))) else emptyList()))
            val body=when {
                path=="/api/auth" -> payload("authenticated" to (request.getHeader("Cookie")?.contains("session=$label")==true),"csrf" to "csrf-$label","notifications" to true)
                path=="/api/login" -> payload("csrf" to "csrf-$label")
                path=="/api/sessions" && request.method=="GET" -> payload("sessions" to JsonArray(listOf(payload("id" to chatId,"host" to "local","title" to "$label chat","projectName" to "project"))))
                path=="/api/activity" -> {
                    val cold=coldActivity && activityRequests.incrementAndGet()==1
                    payload("sessions" to JsonArray(listOf(payload("id" to chatId,"host" to "local","connected" to !cold,"status" to if(cold)"unknown" else activityStatus,"turnId" to "test-turn","turnStatus" to if(activityStatus=="active")"inProgress" else "completed","recency" to 123))))
                }
                path.endsWith("/timeline") && request.requestUrl?.queryParameter("before")!=null -> payload("epoch" to label,"sequence" to 1,"rows" to JsonArray(listOf(payload("key" to "older","order" to -1,"version" to "v1","role" to "assistant","text" to "Earlier history"))),"meta" to meta,"hasMore" to false,"before" to "$label.older")
                path.endsWith("/timeline") || path.endsWith("/changes") -> payload("epoch" to label,"sequence" to 1,"rows" to JsonArray(listOf(payload("key" to "message","order" to 0,"version" to "v1","role" to "assistant","text" to "Hello $label\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n\$\$x^2\$\$","editable" to false))),"meta" to meta).let{if(path.endsWith("/timeline"))JsonObject(it+payload("hasMore" to paging,"before" to "$label.message"))else it}
                path.endsWith("/send") -> payload("status" to if(unknownSend)"unknown" else "sent")
                path.endsWith("/uploads") -> payload("image" to false)
                path.endsWith("/respond") -> payload("status" to "sent")
                path=="/api/accounts/switch" -> payload("status" to "switching")
                path=="/api/account/reset" -> payload("account" to payload("email" to "test","canReset" to false))
                else -> payload()
            }
            val response=MockResponse().setBody(body.toString())
            if(path=="/api/login")response.addHeader("Set-Cookie","session=$label; Secure; HttpOnly; Path=/; Max-Age=3600")
            if(path.endsWith("/changes"))response.setBodyDelay(400,java.util.concurrent.TimeUnit.MILLISECONDS)
            return response
        }}
        server.start()
        val address=server.url("/").toString()
        lateinit var row:Connection
        ui.runOnIdle {
            vm.saveConnection(label,address);row=vm.connections.last()
            val original=vm.gatewayFactory
            vm.gatewayFactory={connection ->if(connection.id==row.id)Gateway(connection,vm.store::get,vm.store::put){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)} else original(connection)}
        }
        return row
    }
    private fun connect(row:Connection) {
        ui.runOnIdle{vm.select(row)};ui.waitUntil(10000){!vm.busy}
        if(!vm.authenticated){ui.onNodeWithText("Username").performTextReplacement("admin");ui.onNodeWithText("Password").performTextReplacement("test");ui.onNodeWithText("Connect",useUnmergedTree=true).performClick()}
        ui.waitUntil(10000){vm.authenticated && !vm.busy}
        assertEquals("sessions",vm.screen);assertNull(vm.target)
    }
    @Test fun listActivityStartsImmediatelyWarmsUpAndHidesProtocolStatuses() {
        coldActivity=true;activityStatus="active"
        val a=computer("Alpha");ui.setContent{BridgeApp(vm)};connect(a)
        ui.waitUntil(4000){activityRequests.get()>=2 && vm.activities["local|$chatId"]?.indicator=="running"}
        ui.onNodeWithContentDescription("Running").assertExists()
        ui.onNodeWithText("unknown").assertDoesNotExist();ui.onNodeWithText("idle").assertDoesNotExist()
        activityStatus="idle";ui.runOnIdle{vm.refreshList()}
        ui.waitUntil(4000){!vm.busy && vm.activities["local|$chatId"]?.indicator=="completed"}
        ui.onNodeWithContentDescription("Completed, unread").assertExists()
        ui.onNodeWithText("idle").assertDoesNotExist()
        val before=activityRequests.get();ui.runOnIdle{vm.pause();vm.resume()}
        ui.waitUntil(4000){activityRequests.get()>before}
        ui.runOnIdle{vm.openChat(chatId)}
        ui.waitUntil(10000){vm.timeline.sequence>=0};assertNull(vm.activities["local|$chatId"]?.pending)
        ui.runOnIdle{vm.showSessions()};ui.waitUntil(10000){!vm.busy}
        ui.onNodeWithContentDescription("Completed, unread").assertDoesNotExist()
    }
    @Test fun twoComputersStartOnListAndRestoreDraftOnlyWhenOpened() {
        val a=computer("Alpha");val b=computer("Beta");ui.setContent{BridgeApp(vm)}
        connect(a);ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0}
        ui.runOnIdle{vm.editDraft(Draft(text="Alpha draft"))}
        connect(b);ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0}
        assertEquals("",vm.draft.text);ui.runOnIdle{vm.editDraft(Draft(text="Beta draft"))}
        connect(a);ui.onNodeWithText("Alpha chat").performClick();ui.waitUntil(10000){vm.target!=null && vm.timeline.sequence>=0};assertEquals("Alpha draft",vm.draft.text)
        ui.onNodeWithContentDescription("Send message").performClick();ui.waitUntil(10000){vm.draft.text.isEmpty()}
        val sent=calls.last{it.second.path!!.contains("/send")};assertEquals("Alpha",sent.first);assertEquals("csrf-Alpha",sent.second.getHeader("X-CSRF-Token"));assertEquals("session=Alpha",sent.second.getHeader("Cookie"))
        connect(b);ui.onNodeWithText("Beta chat").performClick();ui.waitUntil(10000){vm.target?.connection==b.id && vm.timeline.sequence>=0};assertEquals("Beta draft",vm.draft.text)
        assertEquals(1,calls.count{it.first=="Alpha" && it.second.path=="/api/login"})
        ui.runOnIdle{vm.logout()};ui.waitUntil(10000){!vm.authenticated};assertNotNull(vm.store.get("cookies:${a.id}"))
    }
    @Test fun encryptedDraftSurvivesRecreationAndThemeChanges() {
        val a=computer("Alpha");ui.setContent{BridgeApp(vm)};connect(a)
        ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0}
        ui.runOnIdle{vm.editDraft(Draft(text="long draft\n".repeat(50)));vm.preference("theme","dark");vm.preference("fontScale","1.2")}
        ui.onNodeWithText("Hello Alpha",substring=true,useUnmergedTree=true).assertExists()
        val app=ApplicationProvider.getApplicationContext<Application>();val raw=app.getSharedPreferences("bridge",Context.MODE_PRIVATE).getString("draft:${vm.target!!.key}",null)
        assertFalse(raw!!.contains("long draft"));assertEquals("long draft\n".repeat(50),AppStore(app).draft(vm.target!!.key).text)
        ui.runOnIdle{vm.pause()};val before=calls.size;Thread.sleep(1000);assertTrue(calls.size<=before+1)
        ui.runOnIdle{vm.resume()};ui.waitUntil(10000){calls.size>before}
    }
    @Test fun approvalsAndAccountActionsUseCapturedGateway() {
        val a=computer("Alpha");val b=computer("Beta");ui.setContent{BridgeApp(vm)};connect(a);ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0}
        ui.runOnIdle{vm.respond(payload("id" to 7),payload("answers" to payload("q" to JsonArray(listOf(JsonPrimitive("Yes"))))))};ui.waitUntil(10000){!vm.busy && "7" in vm.answered}
        val response=calls.last{it.second.path!!.contains("/respond")}.second.body.clone().readUtf8();assertEquals("Yes",(json.parseToJsonElement(response) as JsonObject).obj("response").obj("answers").strings("q").first())
        ui.runOnIdle{vm.perform("/api/accounts/switch",payload("id" to "account-b","confirmed" to true,"tasksConfirmed" to true),true,"switch:account-b","requestId")};ui.waitUntil(10000){!vm.busy}
        val switched=json.parseToJsonElement(calls.last{it.second.path=="/api/accounts/switch"}.second.body.clone().readUtf8()) as JsonObject
        assertTrue(switched.bool("confirmed"));assertTrue(switched.str("requestId").isNotEmpty())
        ui.runOnIdle{vm.resetAccount(payload("accountKey" to "account-a","creditId" to "credit-a"))};ui.waitUntil(10000){!vm.busy}
        val reset=json.parseToJsonElement(calls.last{it.second.path=="/api/account/reset"}.second.body.clone().readUtf8()) as JsonObject;assertEquals("credit-a",reset.str("creditId"));assertTrue(reset.bool("confirmed"))
        connect(b);assertEquals("",vm.draft.text)
    }
    @Test fun unknownSendAndFailedAttachmentRetryKeepOriginalIdentifiers() {
        val a=computer("Alpha");ui.setContent{BridgeApp(vm)};connect(a);ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0}
        val app=ApplicationProvider.getApplicationContext<Application>();val source=java.io.File(app.cacheDir,"test-attachment.txt").apply{writeText("attachment content")}
        failUpload=true;ui.runOnIdle{vm.addAttachments(listOf(android.net.Uri.fromFile(source)))};ui.waitUntil(10000){!vm.busy && vm.attachments.size==1};assertEquals("failed",vm.attachments.first().status)
        val uploadId=vm.attachments.first().id
        ui.runOnIdle{vm.editDraft(vm.draft.copy(text="retry safely"));vm.send()};assertTrue(vm.error.contains("upload",true));assertFalse(calls.any{it.second.path!!.contains("/send")})
        failUpload=false;ui.runOnIdle{vm.retryAttachment(uploadId)};ui.waitUntil(10000){!vm.busy && vm.attachments.first().status=="ready"}
        val uploads=calls.filter{it.second.path!!.contains("/uploads")};assertEquals(2,uploads.size);assertEquals(uploads[0].second.path,uploads[1].second.path)
        unknownSend=true;ui.runOnIdle{vm.send()};ui.waitUntil(10000){!vm.busy && vm.error.isNotEmpty()};assertEquals("retry safely",vm.draft.text)
        val first=json.parseToJsonElement(calls.last{it.second.path!!.contains("/send")}.second.body.clone().readUtf8()) as JsonObject
        unknownSend=false;ui.runOnIdle{vm.send()};ui.waitUntil(10000){!vm.busy && vm.draft.text.isEmpty()}
        val retry=json.parseToJsonElement(calls.last{it.second.path!!.contains("/send")}.second.body.clone().readUtf8()) as JsonObject;assertEquals(first,retry);assertTrue(vm.attachments.isEmpty());source.delete()
    }
    @Test fun offlineAndExpiredLoginKeepDraftAndOtherComputerSession() {
        val a=computer("Alpha");val b=computer("Beta");ui.setContent{BridgeApp(vm)};connect(b);connect(a)
        ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0};ui.runOnIdle{vm.editDraft(Draft(text="preserved draft"))}
        offline=true;ui.waitUntil(10000){vm.error=="offline"};ui.onNodeWithText("Retry connection").assertExists();assertEquals("preserved draft",vm.draft.text);assertTrue(vm.authenticated)
        offline=false;expired=true;ui.waitUntil(12000){!vm.authenticated};assertEquals("preserved draft",vm.store.draft(ChatTarget(a.id,chatId).key).text)
        assertNotNull(vm.store.get("cookies:${b.id}"));expired=false;connect(a);ui.onNodeWithText("Alpha chat").performClick();ui.waitUntil(10000){vm.target?.connection==a.id && vm.timeline.sequence>=0};assertEquals("preserved draft",vm.draft.text)
    }

    @Test fun nativeQuestionReplyAndHistoryPaginationRemainStable() {
        paging=true;askQuestion=true;val a=computer("Alpha");ui.setContent{BridgeApp(vm)};connect(a);ui.runOnIdle{vm.openChat(chatId)};ui.waitUntil(10000){vm.timeline.sequence>=0}
        ui.onNodeWithText("Answer / custom response").performTextInput("Forward")
        ui.onNodeWithText("Send response").performClick();ui.waitUntil(10000){"7" in vm.answered}
        ui.onNodeWithText("Send response").assertIsNotEnabled()
        val answered=json.parseToJsonElement(calls.last{it.second.path!!.contains("/respond")}.second.body.clone().readUtf8()) as JsonObject
        assertEquals(listOf("Forward"),answered.obj("response").obj("answers").strings("direction"))
        ui.runOnIdle{vm.older()};ui.waitUntil(10000){!vm.historyBusy && vm.timeline.messages.size==2}
        assertEquals(listOf("older","message"),vm.timeline.messages.map{it.str("key")});assertFalse(vm.timeline.hasMore)
        val count=calls.size;ui.waitUntil(10000){calls.size>count};assertEquals(2,vm.timeline.messages.size)
        ui.runOnIdle{vm.scrollPosition(0,12,"older",false)}
        connect(a);ui.onNodeWithText("Alpha chat").performClick();ui.waitUntil(10000){vm.target!=null && vm.timeline.messages.any{it.str("key")=="older"}}
        assertEquals("older",vm.draft.anchor)
    }

}
