package io.github.codexmobilebridge.android

import org.junit.Test
import org.junit.Assert.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

class NativeProtocolTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    @Test fun claudeRoutesKeepConversationAndEncodedWorkspacePath() {
        val chat=ChatTarget("computer",id,"desktop:claude")
        val route=("https://phone.invalid"+chat.route("workspace/preview",mapOf("path" to "目录/a&b.txt"))).toHttpUrl()
        assertEquals("/api/desktop-sessions/claude/workspace/preview",route.encodedPath)
        assertEquals(id,route.queryParameter("sessionId"));assertEquals("目录/a&b.txt",route.queryParameter("path"));assertEquals("detail",chat.route("timeline").substringBefore('?').substringAfterLast('/'))
        assertNotEquals(chat.key,ChatTarget("computer",id).key)
    }
    @Test fun claudeSnapshotPreservesStableKeysAndCapabilities() {
        val detail=payload("session" to payload("id" to id,"title" to "Claude","status" to "active"),"connected" to true,"capabilities" to payload("send" to false),"messages" to JsonArray(listOf(payload("id" to "block","role" to "assistant","text" to "Hi"))))
        val first=claudeTimeline(detail,TimelineState());assertEquals("block",first.messages.single().str("key"));assertFalse(first.meta.obj("capabilities").bool("send"))
        assertSame(first,claudeTimeline(detail,first));assertFalse(first.meta.bool("goalRuntimeAvailable"))
        val second=claudeTimeline(JsonObject(detail+payload("messages" to JsonArray(listOf(payload("id" to "block","text" to "Changed"))))),first)
        assertEquals("block",second.messages.single().str("key"));assertNotEquals(first.messages.single().str("version"),second.messages.single().str("version"))
    }
    @Test fun terminalHandlesChunkedControlsCarriageReturnsAndNoClipboardPayload() {
        val buffer=NativeTerminalBuffer();buffer.append("progress 10%\rprogress 20%\n\u001b[3");buffer.append("1mred\u001b[0m\u001b]52;c;secret-clipboard\u0007")
        assertEquals("progress 20%\nred",buffer.text());buffer.append("\u001b[2DOK");assertTrue(buffer.text().endsWith("rOK"))
        buffer.append("\u001b[2J\u001b[Hnew");assertEquals("new",buffer.text())
        buffer.append("\nrow".repeat(1100));assertTrue(buffer.text().lines().size<=1000)
    }
    private fun https(block:(MockWebServer,Gateway,MutableMap<String,String>)->Unit) {
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build();val state=mutableMapOf<String,String>()
        val gateway=Gateway(Connection(name="test",origin=server.url("/").toString()),state::get,{k,v->if(v==null)state.remove(k) else state[k]=v}){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)}
        try{block(server,gateway,state)}finally{gateway.cancel();server.shutdown()}
    }
    @Test fun claudeWritesUseBodyConversationCsrfAndDurableId()=https{server,gateway,_->runBlocking{
        server.enqueue(MockResponse().setBody(payload("csrf" to "csrf-native").toString()).addHeader("Set-Cookie","session=native; Secure; HttpOnly; Path=/"))
        gateway.api("/api/auth");server.takeRequest()
        server.enqueue(MockResponse().setBody("{\"status\":\"accepted\"}"))
        val fields=payload("text" to "Native Claude","id" to id)
        gateway.chat(ChatTarget("computer",id,"desktop:claude"),"send",fields)
        val request=server.takeRequest();val body=json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals(id,body.str("sessionId"));assertEquals(id,body.str("id"));assertEquals("csrf-native",request.getHeader("X-CSRF-Token"));assertEquals("session=native",request.getHeader("Cookie"));assertEquals(server.url("/").toString().removeSuffix("/"),request.getHeader("Origin"))
    }}
    @Test fun downloadResumesOnlyMatchingRepresentation()=https{server,gateway,_->runBlocking{
        val file=File.createTempFile("native-resume-",".bin")
        try {
            server.enqueue(MockResponse().setBody("abcdefghij").addHeader("ETag","\"first\""));gateway.downloadTo("/api/test/download",file);server.takeRequest()
            file.writeText("abcd")
            server.enqueue(MockResponse().setResponseCode(206).setBody("efghij").addHeader("ETag","\"first\"").addHeader("Content-Range","bytes 4-9/10"))
            gateway.downloadTo("/api/test/download",file);val request=server.takeRequest()
            assertEquals("bytes=4-",request.getHeader("Range"));assertEquals("\"first\"",request.getHeader("If-Range"));assertEquals("abcdefghij",file.readText())
            file.writeText("abcd")
            server.enqueue(MockResponse().setBody("new-content").addHeader("ETag","\"second\""));gateway.downloadTo("/api/test/download",file);assertEquals("new-content",file.readText())
        }finally{file.delete()}
    }}
}
