package io.github.codexmobilebridge.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import android.util.Base64

/** Both readers use the existing per-computer encrypted cookie jar. WebView stores no login. */
object Workbench {
    private lateinit var context: Context
    private val gateways = mutableMapOf<String, Gateway>()
    internal var gatewayFactory:(Connection,AppStore)->Gateway={row,store->Gateway(row,store::get,store::put)}
    @JvmStatic fun initialize(value: Context) { context = value.applicationContext }
    @JvmStatic @Synchronized fun gateway(address: String): Gateway {
        val origin = normalizeOrigin(address.substringBefore('#').substringBefore('?'))
        return gateways.getOrPut(origin) {
            val store = AppStore(context)
            val row = store.connections().find { it.origin == origin } ?: Connection(name=origin.toHttpUrl().host,origin=origin).also {
                store.saveConnections(store.connections()+it)
            }
            gatewayFactory(row,store)
        }.also{it.reloadCookies()}
    }
    @JvmStatic fun selected(address:String) { val row=gateway(address).connection; val store=AppStore(context);store.put("selected",row.id);if(store.get("appNotifications")=="true")runCatching{LiveNotifications.start(context,row)} }
    @JvmStatic fun cookies(url: String): String {
        val parsed=url.toHttpUrl(); val origin=normalizeOrigin(parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
        return gateway(origin).client.cookieJar.loadForRequest(parsed).joinToString("; ") { "${it.name}=${it.value}" }
    }
    @JvmStatic fun removeComputer(origin:String) { forget(origin);val store=AppStore(context);store.connections().find{it.origin==origin}?.let{store.removeConnection(it.id)};store.put("workbench:page:$origin",null);val prefs=WorkbenchPreferences.open(context);val origins=prefs.getStringSet("origins",mutableSetOf()).orEmpty().toMutableSet();origins.remove(origin);prefs.edit().putStringSet("origins",origins).remove("name:"+origin).apply() }
    @JvmStatic fun renameComputer(origin:String,name:String) {val store=AppStore(context);store.saveConnections(store.connections().map{if(it.origin==origin)it.copy(name=name.ifBlank{origin.toHttpUrl().host}) else it})}
    @JvmStatic fun setCookie(url: String, value: String) {
        val parsed=url.toHttpUrl(); val origin=normalizeOrigin(parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
        Cookie.parse(parsed,value)?.let { gateway(origin).client.cookieJar.saveFromResponse(parsed,listOf(it)) }
    }
    @JvmStatic @Synchronized fun forget(origin: String) { gateways.remove(origin)?.let { it.cancel(); it.clearSession() }; AppStore(context).connections().find{it.origin==origin}?.let { AppStore(context).put("cookies:${it.id}",null) } }
    @JvmStatic fun open(context: Context, connection: Connection? = null, thread: String? = null, host: String? = null, provider: String = "codex") {
        initialize(context)
        require(provider in listOf("codex","claude"))
        val url=connection?.let { it.origin+"/?client="+provider+if(thread!=null&&host!=null) "#"+Uri.encode(thread)+"~"+Uri.encode(host) else "" }
        val prefs=WorkbenchPreferences.open(context);val store=AppStore(context);val origins=prefs.getStringSet("origins",mutableSetOf()).orEmpty().toMutableSet();val edit=prefs.edit()
        for(row in store.connections()){origins.add(row.origin);edit.putString("name:"+row.origin,row.name)}
        edit.putStringSet("origins",origins).apply()
        context.startActivity(Intent(context,io.github.try2love.codexbridge.MainActivity::class.java).apply { putExtra("bridgeURL",url) })
    }
}

/** Compatibility with upstream's CookieManager calls, backed solely by Keystore state. */
class WorkbenchCookies private constructor() {
    companion object { private val instance=WorkbenchCookies(); @JvmStatic fun getInstance()=instance }
    fun getCookie(url: String)=Workbench.cookies(url)
    fun setCookie(url: String,value: String) = Workbench.setCookie(url,value)
    fun setCookie(url: String,value: String,done: ValueCallback<Boolean>) { try { setCookie(url,value);done.onReceiveValue(true) } catch(_:Exception) {done.onReceiveValue(false)} }
    fun flush() = Unit
}

/** Upstream metadata is namespaced and encrypted; it cannot collide with native settings. */
class WorkbenchPreferences private constructor(context: Context): SharedPreferences {
    private val store=AppStore(context)
    private var values=JSONObject(store.get("workbench:settings") ?: "{}")
    private val listeners=mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    companion object { private var instance:WorkbenchPreferences?=null; @JvmStatic @Synchronized fun open(context:Context):SharedPreferences { Workbench.initialize(context); return instance ?: WorkbenchPreferences(context.applicationContext).also{instance=it} } }
    @Synchronized override fun getAll():Map<String,*> = values.keys().asSequence().associateWith { values.opt(it).let {v->if(v is JSONArray)(0 until v.length()).map{v.getString(it)}.toSet() else v} }
    @Synchronized override fun getString(key:String?,default:String?):String?=if(values.has(key))values.getString(key) else default
    @Synchronized override fun getStringSet(key:String?,default:MutableSet<String>?):MutableSet<String>?=values.optJSONArray(key)?.let {v->(0 until v.length()).map{v.getString(it)}.toMutableSet()} ?: default?.toMutableSet()
    @Synchronized override fun getInt(key:String?,default:Int)=values.optInt(key,default)
    @Synchronized override fun getLong(key:String?,default:Long)=values.optLong(key,default)
    @Synchronized override fun getFloat(key:String?,default:Float)=values.optDouble(key,default.toDouble()).toFloat()
    @Synchronized override fun getBoolean(key:String?,default:Boolean)=values.optBoolean(key,default)
    @Synchronized override fun contains(key:String?)=values.has(key)
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {if(listener!=null)listeners.add(listener)}
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {listeners.remove(listener)}
    override fun edit():SharedPreferences.Editor=object:SharedPreferences.Editor {
        val changes=mutableMapOf<String?,Any?>();var clear=false
        override fun putString(key:String?,value:String?)=apply{changes[key]=value}
        override fun putStringSet(key:String?,value:MutableSet<String>?)=apply{changes[key]=value?.let{JSONArray(it.toList())}}
        override fun putInt(key:String?,value:Int)=apply{changes[key]=value}
        override fun putLong(key:String?,value:Long)=apply{changes[key]=value}
        override fun putFloat(key:String?,value:Float)=apply{changes[key]=value}
        override fun putBoolean(key:String?,value:Boolean)=apply{changes[key]=value}
        override fun remove(key:String?)=apply{changes[key]=null}
        override fun clear()=apply{clear=true}
        override fun commit():Boolean { synchronized(this@WorkbenchPreferences){val next=if(clear)JSONObject() else JSONObject(values.toString()); for((key,value)in changes){if(value==null)next.remove(key)else next.put(key,value)};store.put("workbench:settings",next.toString());values=next};for(key in changes.keys)for(listener in listeners.toList())listener.onSharedPreferenceChanged(this@WorkbenchPreferences,key);return true }
        override fun apply(){commit()}
    }
}

/** Same-origin HTTPS transport keeps credentials out of WebView's on-disk cookie database. */
class WorkbenchNetwork(private val context:Context,private val view:WebView,private val base:String) {
    private val secret=UUID.randomUUID().toString()
    private val worker=Executors.newFixedThreadPool(4)
    private val pending=ConcurrentHashMap<String,Call>()
    private val active=ConcurrentHashMap.newKeySet<String>()
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private val gateway=Workbench.gateway(base)
    private val store=AppStore(context)
    @Volatile private var closed=false
    private fun accepts(url:String):Boolean = runCatching { val u=url.toHttpUrl(); u.isHttps&&u.username.isEmpty()&&u.password.isEmpty()&&u.host==base.toHttpUrl().host&&u.port==base.toHttpUrl().port }.getOrDefault(false)
    fun intercept(request:WebResourceRequest):WebResourceResponse {
        val url=request.url.toString()
        if(closed||!accepts(url)||request.method!="GET")return failure()
        if(request.url.path=="/__cmb_host.js") {
            val script=context.assets.open("fork-workbench.js").bufferedReader().use{it.readText()}.replace("__CMB_TOKEN__",secret)
            return WebResourceResponse("application/javascript","UTF-8",ByteArrayInputStream(script.toByteArray()))
        }
        return try {
            val builder=Request.Builder().url(url).header("User-Agent","BridgeMobile/1.1-Android").header("Origin",base)
            for((key,value)in request.requestHeaders)if(key.lowercase() !in listOf("cookie","host","origin","user-agent","accept-encoding"))builder.header(key,value)
            val response=gateway.client.newCall(builder.build()).execute()
            if(response.isRedirect){response.close();return failure()}
            val mime=response.header("Content-Type","application/octet-stream")!!.substringBefore(';')
            val headers=response.headers.toMultimap().filterKeys{it.lowercase() !in listOf("set-cookie","content-length","content-encoding","transfer-encoding")}.mapValues{it.value.joinToString(", ")}
            val stream=if(mime=="text/html") {
                response.use { val bytes=it.body!!.bytes(); require(bytes.size<=2*1024*1024);ByteArrayInputStream(String(bytes,Charsets.UTF_8).replace("<head>","<head><script src=\"/__cmb_host.js\"></script>").toByteArray()) }
            } else object:FilterInputStream(response.body!!.byteStream()){override fun close(){super.close();response.close()}}
            WebResourceResponse(mime,"UTF-8",response.code,response.message.ifBlank{"OK"},headers,stream)
        } catch(_:Exception){failure()}
    }
    private fun failure()=WebResourceResponse("text/plain","UTF-8",503,"Unavailable",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream("连接失败，请检查 HTTPS 网关后刷新".toByteArray()))
    @JavascriptInterface fun state(token:String):String = if(!closed&&token==secret)store.get("workbench:page:$base") ?: "{}" else "{}"
    @JavascriptInterface fun saveState(token:String,value:String) {if(!closed&&token==secret&&value.toByteArray().size<=524288)runCatching{JSONObject(value);store.put("workbench:page:$base",value)} }
    @JavascriptInterface fun cancel(token:String,id:String){if(token==secret&&id in active){cancelled.add(id);pending[id]?.cancel()}}
    @JavascriptInterface fun post(token:String,id:String,url:String,headers:String,body:String) {
        if(closed||token!=secret||id.length>80||!accepts(url)||!url.toHttpUrl().encodedPath.startsWith("/api/")||body.length>28*1024*1024||active.size>=32||!active.add(id)){reply(id,0,"{}","",true);return}
        worker.execute {
            try {
                if(closed||id in cancelled)return@execute
                val bytes=Base64.decode(body,Base64.NO_WRAP);require(bytes.size<=20*1024*1024)
                val data=JSONObject(headers);val content=data.optString("content-type","application/json")
                val request=Request.Builder().url(url).header("User-Agent","BridgeMobile/1.1-Android").header("Origin",base)
                    .post(bytes.toRequestBody(content.toMediaType()))
                for(key in data.keys())if(key.lowercase() in listOf("content-type","x-csrf-token"))request.header(key,data.getString(key))
                val call=gateway.client.newCall(request.build());pending[id]=call
                if(closed||id in cancelled){call.cancel();return@execute}
                call.execute().use { response->
                    require((response.body?.contentLength() ?: 0)<=4*1024*1024)
                    val output=java.io.ByteArrayOutputStream();response.body!!.byteStream().use {input->val buffer=ByteArray(8192);var size:Int;while(input.read(buffer).also{size=it}>0){require(output.size()+size<=4*1024*1024);output.write(buffer,0,size)}}
                    reply(id,response.code,JSONObject(response.headers.toMultimap().filterKeys{it.lowercase() !in listOf("set-cookie","content-length","content-encoding")}.mapValues{it.value.joinToString(", ")}).toString(),Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP),false)
                }
            }catch(_:Exception){reply(id,0,"{}","",true)}finally{pending.remove(id);active.remove(id);cancelled.remove(id)}
        }
    }
    private fun reply(id:String,status:Int,headers:String,body:String,error:Boolean) {view.post {if(!closed)view.evaluateJavascript("window.__cmbReceive?.(${JSONObject.quote(secret)},${JSONObject.quote(id)},$status,$headers,${JSONObject.quote(body)},$error)",null)} }
    fun close(){closed=true;for(call in pending.values)call.cancel();pending.clear();active.clear();cancelled.clear();worker.shutdownNow()}
}
