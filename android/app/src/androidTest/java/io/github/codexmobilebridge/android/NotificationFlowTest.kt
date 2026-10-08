package io.github.codexmobilebridge.android

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NotificationFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var app:Application
    private lateinit var vm:BridgeViewModel
    private lateinit var server:MockWebServer
    private lateinit var originalFactory:(Connection,AppStore)->Gateway
    private val activityCalls=AtomicInteger(0)
    @Volatile private var status="active"
    @Volatile private var approval=true
    private val chat="11111111-1111-4111-8111-111111111111"
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("bridge",Context.MODE_PRIVATE).edit().clear().commit()
        app.getSystemService(NotificationManager::class.java).cancelAll()
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.path!!.substringBefore('?')
            val row=payload("id" to chat,"host" to "local","title" to "Monitor chat","connected" to true,"status" to status,"turnId" to "turn-one","turnStatus" to if(status=="active")"inProgress" else "completed")
            val body=when(path) {
                "/api/auth" -> payload("authenticated" to (request.getHeader("Cookie")?.contains("session=monitor")==true),"csrf" to "csrf")
                "/api/login" -> payload("csrf" to "csrf")
                "/api/sessions" -> payload("sessions" to JsonArray(listOf(row)))
                "/api/activity" -> {activityCalls.incrementAndGet();payload("sessions" to JsonArray(listOf(row)))}
                else -> payload("meta" to JsonObject(row+payload("requests" to JsonArray(if(approval)listOf(payload("id" to 7,"method" to "approval","supported" to true)) else emptyList()))))
            }
            return MockResponse().setBody(body.toString()).apply {if(path=="/api/login")addHeader("Set-Cookie","session=monitor; Secure; HttpOnly; Path=/; Max-Age=3600")}
        }}
        server.start()
        val address=server.url("/").toString()
        originalFactory=ReminderService.gatewayFactory
        ReminderService.gatewayFactory={connection,store->Gateway(connection,store::get,store::put){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)}}
        ui.runOnIdle {
            vm=BridgeViewModel(app);vm.preference("language","en")
            vm.gatewayFactory={connection->Gateway(connection,vm.store::get,vm.store::put){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)}}
            vm.saveConnection("Monitor",address)
        }
        ui.setContent {BridgeApp(vm)}
        ui.runOnIdle {vm.select(vm.connections.single())};ui.waitUntil(10000){!vm.busy}
        ui.runOnIdle {vm.login("admin","test")};ui.waitUntil(10000){vm.authenticated && !vm.busy}
    }
    @After fun cleanup() {
        ui.runOnIdle {vm.enableAppNotifications(false);vm.showConnections();vm.pause()}
        LiveNotifications.stop(app);app.getSystemService(NotificationManager::class.java).cancelAll()
        ReminderService.gatewayFactory=originalFactory
        server.shutdown()
    }
    private fun notices()=app.getSystemService(NotificationManager::class.java).activeNotifications
    @Test fun backgroundCompletionApprovalAndStopAreDeliveredWithoutDuplicateAlerts() {
        ui.runOnIdle {vm.openTool("app-notifications")}
        ui.onNodeWithText("Enable app notifications").assertExists()
        ui.runOnIdle {vm.enableAppNotifications(true)}
        ui.waitUntil(10000){notices().any {it.id==LiveNotifications.ONGOING}}
        // Simulate leaving the foreground; the service takes over with its own authenticated client.
        ui.runOnIdle {vm.closeTool();vm.pause()}
        ui.waitUntil(15000){notices().any {it.notification.extras.getString(Notification.EXTRA_TEXT)=="Waiting for your response"}}
        val approvalTime=notices().first {it.id==7002}.postTime
        val count=activityCalls.get();ui.waitUntil(12000){activityCalls.get()>count}
        assertEquals(approvalTime,notices().first {it.id==7002}.postTime)
        approval=false;status="idle"
        ui.waitUntil(12000){notices().any {it.notification.extras.getString(Notification.EXTRA_TEXT)=="Task completed"}}
        val completionTime=notices().first {it.id==7002}.postTime
        val nextCount=activityCalls.get();ui.waitUntil(12000){activityCalls.get()>nextCount}
        assertEquals(completionTime,notices().first {it.id==7002}.postTime)
        ui.runOnIdle {vm.enableAppNotifications(false)}
        ui.waitUntil(10000){notices().none {it.id==LiveNotifications.ONGOING}}
        assertEquals("false",vm.store.get("appNotifications"))
    }
}
