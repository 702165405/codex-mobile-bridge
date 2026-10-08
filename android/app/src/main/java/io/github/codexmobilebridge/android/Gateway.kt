package io.github.codexmobilebridge.android

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GatewayError(val status: Int, message: String) : IOException(message)
class Gateway(val connection: Connection, private val readState: (String) -> String?, private val writeState: (String,String?) -> Unit,
              customize: (OkHttpClient.Builder) -> Unit = {}) {
    var csrf: String = ""
        private set
    private val origin = normalizeOrigin(connection.origin).toHttpUrl()
    private val cookieKey = "cookies:${connection.id}"
    private var cookies = readState(cookieKey)?.lines()?.mapNotNull { Cookie.parse(origin,it) } ?: emptyList()
    val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            @Synchronized override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
                cookies = (cookies.filter { old -> values.none { it.name == old.name && it.path == old.path && it.domain == old.domain } } + values)
                    .filter { it.expiresAt > System.currentTimeMillis() }
                writeState(cookieKey,cookies.filter { it.persistent }.joinToString("\n") { it.toString() })
            }
            @Synchronized override fun loadForRequest(url: HttpUrl) = if(url.host == origin.host && url.port == origin.port && url.isHttps)
                cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } else emptyList()
        }).apply(customize).build()
    fun cancel() = client.dispatcher.cancelAll()
    fun clearSession() { cookies = emptyList(); csrf = ""; writeState(cookieKey,null) }
    private fun request(path: String, data: JsonObject?, raw: RequestBody? = null): Request {
        require(path.startsWith("/api/") && !path.startsWith("//"))
        val url = origin.resolve(path)!!
        require(url.host == origin.host && url.port == origin.port && url.isHttps)
        val builder = Request.Builder().url(url).header("User-Agent", "CodexMobileBridgeAndroid/1.0")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
        if(data != null || raw != null) builder.header("Origin",origin.toString().removeSuffix("/"))
            .header("X-CSRF-Token",csrf).post(raw ?: data.toString().toRequestBody("application/json".toMediaType()))
        return builder.build()
    }
    // Keep the OkHttp call active until its body is consumed, so switching also
    // cancels slow streams rather than just requests waiting for headers.
    private suspend fun <T> execute(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result=response.use(consume)
                    if(continuation.isActive)continuation.resume(result)
                } catch(e: Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun api(path: String, data: JsonObject? = null, raw: RequestBody? = null): JsonObject = withContext(Dispatchers.IO) {
        execute(request(path,data,raw)) { response ->
            val value = runCatching { json.parseToJsonElement(response.body!!.string()) as JsonObject }.getOrElse {
                throw GatewayError(response.code,"网关返回了无效响应 / Invalid gateway response")
            }
            if(!response.isSuccessful) throw GatewayError(response.code,value.str("error","HTTP ${response.code}"))
            if(path.substringBefore('?') in listOf("/api/auth", "/api/login", "/api/pair")) csrf = value.str("csrf")
            value
        }
    }
    suspend fun file(path: String, output: java.io.OutputStream): Unit = withContext(Dispatchers.IO) {
        execute(request(path,null)) { response ->
            if(!response.isSuccessful) throw GatewayError(response.code,"文件下载失败 / File download failed: ${response.code}")
            response.body!!.byteStream().use { it.copyTo(output) }
            Unit
        }
    }
    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        execute(request(path,null)) { response ->
            if(!response.isSuccessful) throw GatewayError(response.code,"图片加载失败 / Could not load image")
            require((response.body?.contentLength() ?: 0) <= 20L*1024*1024)
            val output = java.io.ByteArrayOutputStream()
            response.body!!.byteStream().use { input ->
                val buffer=ByteArray(8192);var total=0;var count:Int
                while(input.read(buffer).also{count=it}>=0) { total+=count;require(total<=20*1024*1024);output.write(buffer,0,count) }
            }
            output.toByteArray()
        }
    }
}
