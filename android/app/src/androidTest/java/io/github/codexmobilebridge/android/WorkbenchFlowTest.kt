package io.github.codexmobilebridge.android

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkbenchFlowTest {
 @Test fun authenticatedPostAndDraftStayInNativeEncryptedStorage() {
  val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
  val trusted=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
  val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
  val diagnostics=java.util.concurrent.CopyOnWriteArrayList<String>();val result=CountDownLatch(1);val requests=java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
   requests.add(request)
   return when(request.path) {
    "/"->MockResponse().setHeader("Content-Type","text/html").setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'").setBody("<html><head><script src='/flow.js'></script></head><body>Fixture</body></html>")
    "/flow.js"->MockResponse().setHeader("Content-Type","application/javascript").setBody("(async()=>{try{await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':'csrf-fixture'},body:JSON.stringify({password:'private-password'})});localStorage.setItem('draft','private-draft');await fetch('/api/auth');}catch(e){document.title='error:'+e.message;}})();")
    "/api/login"->MockResponse().setHeader("Content-Type","application/json").setHeader("Set-Cookie","codex_mobile_session=private-session; Path=/; Secure; HttpOnly; Max-Age=3600").setBody("{}")
    "/api/auth"->{result.countDown();MockResponse().setHeader("Content-Type","application/json").setBody("{}")}
    else->MockResponse().setResponseCode(404)
   }
  }}
  server.start();val origin=server.url("/").toString().removeSuffix("/")
  val original=Workbench.gatewayFactory
  Workbench.gatewayFactory={row,store->Gateway(row,store::get,store::put){it.sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)}}
  var network:WorkbenchNetwork?=null
  try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
   scenario.onActivity{activity->
    Workbench.initialize(activity)
    val view=WebView(activity);view.settings.javaScriptEnabled=true;view.settings.domStorageEnabled=true;view.settings.allowFileAccess=false
    CookieManager.getInstance().setAcceptCookie(false)
    network=WorkbenchNetwork(activity,view,origin);view.addJavascriptInterface(network!!,"CmbNative")
    view.webViewClient=object:WebViewClient(){override fun shouldInterceptRequest(web:WebView,request:WebResourceRequest):WebResourceResponse=network!!.intercept(request)}
    view.webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage):Boolean{diagnostics.add(message.message());return true};override fun onReceivedTitle(web:WebView,title:String){diagnostics.add("title="+title)}}
    activity.setContentView(view);view.loadUrl(origin+"/")
   }
   assertTrue("WebView POST/auth flow did not complete: $diagnostics; paths=${requests.map{it.path}}",result.await(30,TimeUnit.SECONDS))
   val post=requests.first{it.path=="/api/login"};assertEquals("POST",post.method);assertEquals("csrf-fixture",post.getHeader("X-CSRF-Token"));assertEquals(origin,post.getHeader("Origin"))
   val auth=requests.first{it.path=="/api/auth"};assertEquals("codex_mobile_session=private-session",auth.getHeader("Cookie"))
   scenario.onActivity{activity->
    val store=AppStore(activity);assertTrue(store.get("workbench:page:$origin")!!.contains("private-draft"))
    assertFalse(activity.getSharedPreferences("bridge",0).all.values.joinToString().contains("private-session"));assertFalse(activity.getSharedPreferences("bridge",0).all.values.joinToString().contains("private-draft"))
    assertTrue(CookieManager.getInstance().getCookie(origin).isNullOrEmpty());network!!.close()
   }
  }} finally {network?.close();Workbench.gatewayFactory=original;server.shutdown()}
 }
}
