package io.github.codexmobilebridge.android

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProtocolTest {
    @Test fun markdownMathLeavesCodeUnchanged() {
        val raw="Math \$x^2\$ and \\(y\\), `\$code\$`, ![image](https://example.com/image.png)\n```kotlin\nval x = 1\n```"
        val result=readableMarkdown(raw)
        assertTrue(result.contains("\$\$x^2\$\$"));assertTrue(result.contains("\$\$y\$\$"));assertTrue(result.contains("`\$code\$`"))
        assertTrue(result.endsWith(raw.substring(raw.indexOf("```"))))
        assertTrue(result.contains("[image](https://example.com/image.png)"));assertFalse(result.contains("![image]"))
    }
    @Test fun originsAreHttpsAndCanonical() {
        assertEquals("https://example.com",normalizeOrigin(" HTTPS://Example.com:443/ "))
        for(value in listOf("http://example.com","https://a/path","https://a?q=1","https://u:p@a","invalid"))assertTrue(runCatching{normalizeOrigin(value)}.isFailure)
    }
    @Test fun pendingRetriesReuseIdentityButChangedOperationsDoNot() {
        val fields=payload("text" to "hello","mode" to "send")
        val original=pendingAttempt(null,fields)
        assertEquals(original,pendingAttempt(original,fields))
        assertNotEquals(original.str("id"),pendingAttempt(original,payload("text" to "other")).str("id"))
        val reset=pendingAttempt(null,payload("accountKey" to "a","creditId" to null),"requestId")
        assertEquals(reset,pendingAttempt(reset,payload("accountKey" to "a","creditId" to null),"requestId"))
    }
    @Test fun timelinePagesPreserveLiveRowsAndHandleResets() {
        fun row(key:String,order:Int,text:String)=payload("key" to key,"order" to order,"text" to text)
        fun page(seq:Int,rows:List<JsonObject>,epoch:String="a")=payload("epoch" to epoch,"sequence" to seq,"rows" to JsonArray(rows),"meta" to payload("title" to "chat"),"hasMore" to true,"before" to "a.x")
        var state=TimelineState().apply(page(5,listOf(row("b",1,"new"))))
        state=state.apply(page(3,listOf(row("a",0,"old"),row("b",1,"stale"))),true)
        assertEquals(5L,state.sequence);assertEquals(listOf("old","new"),state.messages.map{it.str("text")})
        assertEquals(state,state.apply(page(2,emptyList())))
        state=state.apply(page(6,listOf(row("b",1,"newer"),row("c",2,"last"))))
        assertEquals(3,state.messages.size);assertEquals("newer",state.messages[1].str("text"))
        state=state.apply(page(0,listOf(row("z",0,"reset")),"b"));assertEquals(1,state.messages.size);assertEquals("b",state.epoch)
    }
    @Test fun scopeSeparatesHostsAndConnections() {
        val id=newId();assertNotEquals(ChatTarget("one",id).key,ChatTarget("two",id).key)
        assertNotEquals(ChatTarget("one",id).key,ChatTarget("one",id,"remote").key)
        assertTrue(ChatTarget("one",id,"remote & host").route("send").contains("host=remote%20%26%20host"))
    }
    @Test fun failedAndOversizedAttachmentsCannotBeSent() {
        val attachment=Attachment(name="file",size=20L*1024*1024,path="cache",status="failed")
        validateAttachments(emptyList(),listOf(attachment))
        assertTrue(runCatching{validateAttachments(emptyList(),listOf(attachment.copy(size=attachment.size+1)))}.isFailure)
        assertTrue(runCatching{validateAttachments(List(10){attachment},listOf(attachment))}.isFailure)
        assertTrue(runCatching{validateAttachments(List(5){attachment},listOf(attachment))}.isFailure)
    }
    private fun tlsServer(): Pair<MockWebServer,HandshakeCertificates> {
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        return server to HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    }
    @Test fun twoGatewaysIsolateCookiesCsrfAndOrigin()=runBlocking {
        val (a,ta)=tlsServer();val(b,tb)=tlsServer();val state=mutableMapOf<String,String>()
        try {
            fun gateway(id:String,s:MockWebServer,t:HandshakeCertificates)=Gateway(Connection(id,id,s.url("/").toString()),{state[it]},{k,v->if(v==null)state.remove(k)else state[k]=v}){it.sslSocketFactory(t.sslSocketFactory(),t.trustManager)}
            val ga=gateway("a",a,ta);val gb=gateway("b",b,tb)
            a.enqueue(MockResponse().addHeader("Set-Cookie","session=alpha; Secure; HttpOnly; Path=/; Max-Age=3600").setBody("{\"authenticated\":true,\"csrf\":\"csrf-a\"}"))
            b.enqueue(MockResponse().addHeader("Set-Cookie","session=beta; Secure; HttpOnly; Path=/; Max-Age=3600").setBody("{\"authenticated\":true,\"csrf\":\"csrf-b\"}"))
            ga.api("/api/auth");gb.api("/api/auth");a.takeRequest();b.takeRequest()
            for((s,g,csrf,cookie) in listOf(arrayOf(a,ga,"csrf-a","session=alpha"),arrayOf(b,gb,"csrf-b","session=beta"))) {
                s as MockWebServer;g as Gateway;s.enqueue(MockResponse().setBody("{}"));g.api("/api/login",payload("username" to "user"))
                val req=s.takeRequest();assertEquals(csrf,req.getHeader("X-CSRF-Token"));assertEquals(cookie,req.getHeader("Cookie"));assertEquals(s.url("/").toString().removeSuffix("/"),req.getHeader("Origin"));assertEquals("CodexMobileBridgeAndroid/1.0",req.getHeader("User-Agent"))
            }
            assertTrue(state["cookies:a"]!!.contains("alpha"));assertFalse(state["cookies:a"]!!.contains("beta"))
            val restored=gateway("a",a,ta);a.enqueue(MockResponse().setBody("{}"));restored.api("/api/auth");assertEquals("session=alpha",a.takeRequest().getHeader("Cookie"))
            gb.clearSession();assertNull(state["cookies:b"]);assertNotNull(state["cookies:a"])
        } finally {a.shutdown();b.shutdown()}
    }
    @Test fun cancellationStopsOldConnectionAndUnauthorizedIsLocal()=runBlocking {
        val(a,ta)=tlsServer();val(b,tb)=tlsServer()
        try {
            fun gateway(s:MockWebServer,t:HandshakeCertificates)=Gateway(Connection(name="test",origin=s.url("/").toString()),{null},{_,_->}){it.sslSocketFactory(t.sslSocketFactory(),t.trustManager)}
            val ga=gateway(a,ta);val gb=gateway(b,tb)
            a.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request=async{ga.api("/api/auth")};withContext(Dispatchers.IO){assertNotNull(a.takeRequest(5,TimeUnit.SECONDS))};request.cancelAndJoin();ga.cancel()
            b.enqueue(MockResponse().setBody("{\"authenticated\":true}"));assertTrue(gb.api("/api/auth").bool("authenticated"))
            a.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"));val error=runCatching{ga.api("/api/auth")}.exceptionOrNull();assertTrue(error is GatewayError && error.status==401)
        } finally {a.shutdown();b.shutdown()}
    }
    @Test fun badCertificatesAndRedirectsAreRejected()=runBlocking {
        val(s,t)=tlsServer()
        try {
            val insecure=Gateway(Connection(name="test",origin=s.url("/").toString()),{null},{_,_->})
            assertTrue(runCatching{insecure.api("/api/auth")}.isFailure)
            val g=Gateway(Connection(name="test",origin=s.url("/").toString()),{null},{_,_->}){it.sslSocketFactory(t.sslSocketFactory(),t.trustManager)}
            s.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://other.example/api/auth").setBody("{}"))
            assertTrue(runCatching{g.api("/api/auth")}.exceptionOrNull() is GatewayError)
            assertTrue(runCatching{g.api("https://other.example/api/auth")}.isFailure)
        } finally{s.shutdown()}
    }
    @Test fun cancellingSlowResponseBodyReleasesConnection()=runBlocking {
        val(s,t)=tlsServer()
        try {
            val g=Gateway(Connection(name="test",origin=s.url("/").toString()),{null},{_,_->}){it.sslSocketFactory(t.sslSocketFactory(),t.trustManager)}
            s.enqueue(MockResponse().setBody("{\"large\":\""+"x".repeat(1000)+"\"}").throttleBody(1,1,TimeUnit.SECONDS))
            val request=async{g.api("/api/auth")}
            withContext(Dispatchers.IO){assertNotNull(s.takeRequest(5,TimeUnit.SECONDS))}
            request.cancelAndJoin();g.cancel()
            withTimeout(5000){while(g.client.dispatcher.runningCallsCount()!=0)delay(10)}
            assertEquals(0,g.client.dispatcher.runningCallsCount())
        } finally {s.shutdown()}
    }

}
