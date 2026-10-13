package io.github.codexmobilebridge.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val GITHUB_RELEASE_ROOT = "https://github.com/702165405/codex-mobile-bridge/releases/"
const val UPDATE_MANIFEST_URL = "https://mac.lqilt.top/android/update.json"
fun githubReleaseUrl(address:String): Boolean {
    val url=address.toHttpUrl()
    return url.isHttps && url.host=="github.com" && url.port==443 && url.username.isEmpty() && url.password.isEmpty() && url.query==null && url.fragment==null &&
        (url.encodedPath.startsWith("/702165405/codex-mobile-bridge/releases/download/") || url.encodedPath.startsWith("/702165405/codex-mobile-bridge/releases/latest/download/"))
}
fun normalizeUpdateManifestUrl(address:String): String {
    val url=address.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null && url.query==null && url.encodedPath.endsWith(".json")) { "请输入 HTTPS 更新清单地址 / Enter an HTTPS manifest URL" }
    return url.toString()
}
data class AppUpdate(val version: String, val code: Int, val sha256: String, val apkUrl: String, val notes: String, val size: Long = -1)
fun parseAppUpdate(value: JsonObject, manifestUrl:String=UPDATE_MANIFEST_URL): AppUpdate {
    require(value.str("applicationId") == "io.github.codexmobilebridge.android") { "更新包应用标识不匹配 / Wrong application" }
    val code=(value["versionCode"] as? JsonPrimitive)?.intOrNull ?: error("无效版本号 / Invalid version code")
    require(value.str("versionName").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    val hash=value.str("sha256")
    val source=normalizeUpdateManifestUrl(manifestUrl).toHttpUrl()
    val url=source.resolve(value.str("apkUrl")) ?: error("无效下载地址 / Invalid download URL")
    require(code > 0 && hash.matches(Regex("[a-f0-9]{64}"))) { "无效更新信息 / Invalid update metadata" }
    require(value.str("apkUrl").isNotBlank() && url.isHttps && url.host==source.host && url.port==source.port && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null && url.query==null && url.encodedPath.endsWith(".apk")) { "无效下载地址 / Invalid download URL" }
    require(!githubReleaseUrl(source.toString()) || githubReleaseUrl(url.toString())) { "更新包不属于当前 GitHub 仓库 / Wrong GitHub repository" }
    val size=if("size" in value)(value["size"] as? JsonPrimitive)?.longOrNull ?: error("无效安装包大小 / Invalid APK size") else -1L
    require(size == -1L || size in 1..MAX_UPDATE_SIZE) { "无效安装包大小 / Invalid APK size" }
    return AppUpdate(value.str("versionName"),code,hash,url.toString(),value.str("notes"),size)
}
const val MAX_UPDATE_SIZE = 150L*1024*1024
fun AppUpdate.metadata() = payload("applicationId" to "io.github.codexmobilebridge.android", "versionName" to version,
    "versionCode" to code, "sha256" to sha256, "apkUrl" to apkUrl, "notes" to notes, "size" to size)
data class PendingUpdate(val update: AppUpdate, val file: File, val verified: Boolean, val permissionPending: Boolean)
// This separate client never sends gateway cookies or credentials to the update server.
class AppUpdates(client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()) {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private fun request(url:String)=Request.Builder().url(url).header("User-Agent","CodexMobileBridgeAndroid/${BuildConfig.VERSION_NAME}").header("Cache-Control","no-cache").build()
    private fun response(address:String): okhttp3.Response {
        var url=address
        val github=githubReleaseUrl(address)
        repeat(6) {
            val response=client.newCall(request(url)).execute()
            if(response.code !in listOf(301,302,303,307,308))return response
            val target=response.header("Location")?.let {response.request.url.resolve(it)}
            response.close()
            require(github && target!=null && target.isHttps && target.port==443 && target.username.isEmpty() && target.password.isEmpty() && target.fragment==null &&
                (githubReleaseUrl(target.toString()) || target.host=="release-assets.githubusercontent.com")) { "更新地址跳转不受信任 / Untrusted update redirect" }
            url=target.toString()
        }
        error("更新地址跳转过多 / Too many update redirects")
    }
    private fun readJson(url:String): JsonElement = response(url).use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val body=response.body!!
        require(body.contentLength() <= 1024*1024)
        val bytes=body.byteStream().use { it.readBytesBounded(1024*1024) }
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
    }
    suspend fun check(manifestUrl:String=UPDATE_MANIFEST_URL): AppUpdate = withContext(Dispatchers.IO) {
        val url=normalizeUpdateManifestUrl(manifestUrl)
        parseAppUpdate(readJson(url).jsonObject,url)
    }
    private fun session(context:Context)=AtomicFile(File(context.filesDir,"updates/session.json"))
    fun packageFile(context:Context,update:AppUpdate):File = File(context.filesDir,"updates/packages/${update.code}-${update.sha256}.apk").apply { parentFile!!.mkdirs() }
    private fun persist(context:Context,update:AppUpdate,etag:String?=null,verified:Boolean=false,permission:Boolean=false) {
        val atomic=session(context);atomic.baseFile.parentFile!!.mkdirs()
        val stream=atomic.startWrite()
        try {stream.write(JsonObject(update.metadata()+payload("etag" to (etag ?: ""),"verified" to verified,"permissionPending" to permission)).toString().toByteArray());atomic.finishWrite(stream)}
        catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    private fun saved(context:Context):JsonObject? = runCatching {session(context).openRead().use {json.parseToJsonElement(it.readBytesBounded(1024*1024).toString(Charsets.UTF_8)).jsonObject}}.getOrNull()
    fun discard(context:Context) {
        session(context).delete()
        File(context.filesDir,"updates/packages").listFiles()?.forEach {it.delete()}
    }
    suspend fun restore(context:Context):PendingUpdate? = withContext(Dispatchers.IO) {
        try {
            val data=saved(context) ?: run {discard(context);return@withContext null}
            val update=parseAppUpdate(data)
            if(update.code<=BuildConfig.VERSION_CODE){discard(context);return@withContext null}
            val file=packageFile(context,update)
            require(file.canonicalFile==File(file.parentFile!!.canonicalFile,file.name) && file.isFile && file.length() in 1..MAX_UPDATE_SIZE)
            require(update.size<0 || file.length()<=update.size)
            val verified=data.bool("verified") || (update.size>0 && file.length()==update.size)
            if(verified){verify(context,file,update);persist(context,update,verified=true,permission=data.bool("permissionPending"))}
            PendingUpdate(update,file,verified,data.bool("permissionPending"))
        } catch(e:Exception){discard(context);null}
    }
    private suspend fun <T> fetch(request:Request,consume:(okhttp3.Response)->T):T {
        val finished=CompletableDeferred<Unit>();val call=client.newCall(request)
        try {return suspendCancellableCoroutine {continuation->
            continuation.invokeOnCancellation {call.cancel()}
            call.enqueue(object:okhttp3.Callback {
                override fun onFailure(call:okhttp3.Call,e:java.io.IOException){try{if(continuation.isActive)continuation.resumeWithException(e)}finally{finished.complete(Unit)}}
                override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){
                    try {val result=response.use(consume);if(continuation.isActive)continuation.resume(result)}
                    catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                    finally {finished.complete(Unit)}
                }
            })
        }} finally {
            // A paused task releases its file only after the socket/body is closed.
            withContext(NonCancellable){finished.await()}
        }
    }
    suspend fun download(context:Context, update:AppUpdate, progress:(Long,Long)->Unit={_,_->}):File = withContext(Dispatchers.IO) {
        // All public download entry points validate the personal feed before opening a socket.
        require(parseAppUpdate(update.metadata())==update && update.code>BuildConfig.VERSION_CODE)
        val file=packageFile(context,update)
        val old=saved(context)
        if(old?.str("sha256")!=update.sha256 || old?.str("versionCode")!=update.code.toString()){discard(context);file.parentFile!!.mkdirs()}
        if(update.size>0 && file.isFile && file.length()==update.size){
            if(runCatching {verify(context,file,update)}.isSuccess){persist(context,update,verified=true);return@withContext file}
            discard(context);file.parentFile!!.mkdirs()
        }
        val etag=old?.str("etag")?.takeIf {it.startsWith("\"") && it.endsWith("\"") && !it.startsWith("W/")}
        val offset=if(etag!=null && file.isFile && file.length()<MAX_UPDATE_SIZE)file.length() else 0L
        val builder=request(update.apkUrl).newBuilder().header("Accept-Encoding","identity")
        if(offset>0)builder.header("Range","bytes=$offset-").header("If-Range",etag!!)
        fetch(builder.build()) {response->
            check(response.code==200 || response.code==206){"HTTP ${response.code}"}
            require(response.header("Content-Encoding","identity")=="identity")
            val body=response.body ?: error("Empty update response")
            val resumed=response.code==206
            var expected=body.contentLength()
            if(resumed){
                val range=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range") ?: "") ?: error("无效续传响应 / Invalid range")
                val (first,last,total)=range.groupValues.drop(1).map {it.toLong()}
                require(offset>0 && first==offset && last>=first && last==total-1 && total<=MAX_UPDATE_SIZE && response.header("ETag")==etag)
                require(expected<0 || expected==last-first+1);require(update.size<0 || total==update.size)
                expected=total
            }
            if(expected<0 && update.size>0)expected=update.size
            require(expected<=MAX_UPDATE_SIZE && (resumed || update.size<0 || expected<0 || expected==update.size))
            val nextTag=response.header("ETag")?.takeIf {it.startsWith("\"") && it.endsWith("\"") && !it.startsWith("W/")}
            if(!resumed)file.writeBytes(byteArrayOf())
            persist(context,update,nextTag)
            var copied=if(resumed)offset else 0L
            progress(copied,expected)
            java.io.FileOutputStream(file,resumed).use {output->body.byteStream().use {input->
                val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;copied+=n
                    require(copied<=MAX_UPDATE_SIZE && (update.size<0 || copied<=update.size) && (expected<0 || copied<=expected))
                    output.write(buffer,0,n);progress(copied,expected)
                }
                output.fd.sync()
            }}
            check(expected<0 || copied==expected){"下载中断，可继续 / Download interrupted; resume available"}
        }
        coroutineContext.ensureActive()
        complete(context,file,update)
    }
    suspend fun downloadViaGateway(context:Context,update:AppUpdate,gateway:Gateway,identifier:String,progress:(Long,Long)->Unit):File = withContext(Dispatchers.IO) {
        require(identifier.matches(Regex("[a-f0-9]{64}")) && parseAppUpdate(update.metadata())==update && update.size>0)
        val file=packageFile(context,update)
        if(saved(context)?.str("sha256")!=update.sha256){discard(context);file.parentFile!!.mkdirs()}
        if(file.isFile && file.length()==update.size){
            if(runCatching {verify(context,file,update)}.isSuccess){persist(context,update,verified=true);return@withContext file}
            discard(context);file.parentFile!!.mkdirs()
        }
        persist(context,update)
        gateway.downloadTo("/api/mobile/update/packages/$identifier",file,maxSize=update.size,progress=progress)
        coroutineContext.ensureActive()
        complete(context,file,update)
    }
    private fun complete(context:Context,file:File,update:AppUpdate):File {
        try {verify(context,file,update);persist(context,update,verified=true);return file}
        catch(e:Exception){discard(context);throw e}
    }
    fun verify(context:Context,file:File,update:AppUpdate) {
        require(file.length() in 1..MAX_UPDATE_SIZE && (update.size<0 || file.length()==update.size))
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        check(digest.digest().joinToString(""){"%02x".format(it)}==update.sha256){"文件校验失败 / Checksum mismatch"}
        verifyPackage(context,file,update)
    }
    suspend fun save(context:Context,file:File,update:AppUpdate,uri:Uri) = withContext(Dispatchers.IO) {
        verify(context,file,update)
        context.contentResolver.openOutputStream(uri,"wt").use {output->requireNotNull(output);file.inputStream().use {it.copyTo(output)}}
    }
    fun permissionPending(context:Context)=saved(context)?.bool("permissionPending")==true
    fun clearPermission(context:Context,update:AppUpdate)=persist(context,update,verified=true)
    @Suppress("DEPRECATION")
    fun verifyPackage(context:Context,file:File,update:AppUpdate) {
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm=context.packageManager
        val archive=pm.getPackageArchiveInfo(file.absolutePath,flags) ?: error("无效 APK / Invalid APK")
        val installed=pm.getPackageInfo(context.packageName,flags)
        check(archive.packageName == context.packageName && archive.versionName == update.version)
        val version=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
        check(version==update.code.toLong() && version>BuildConfig.VERSION_CODE) { "更新版本无效 / Invalid update version" }
        fun signatures(info:android.content.pm.PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        val current=signatures(installed)?.map { it.toCharsString() }?.toSet().orEmpty()
        check(current.isNotEmpty() && current==signatures(archive)?.map { it.toCharsString() }?.toSet()) { "更新签名不匹配 / Signing certificate mismatch" }
    }
    suspend fun install(context:Context,file:File,update:AppUpdate): Boolean = withContext(Dispatchers.IO) {
        verify(context,file,update)
        withContext(Dispatchers.Main) {
        if(!context.packageManager.canRequestPackageInstalls()) {
            persist(context,update,verified=true,permission=true)
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext false
        }
        clearPermission(context,update)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
        }
    }
    fun cancel() = client.dispatcher.cancelAll()
}
private fun java.io.InputStream.readBytesBounded(limit:Int): ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) {val n=read(buffer);if(n<0)break;require(output.size()+n<=limit);output.write(buffer,0,n)}
    return output.toByteArray()
}
