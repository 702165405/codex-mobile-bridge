package io.github.codexmobilebridge.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val UPDATE_MANIFEST_URL = "https://mac.lqilt.top/android/update.json"
fun normalizeUpdateManifestUrl(address:String): String {
    val url=address.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null && url.query==null && url.encodedPath.endsWith(".json")) { "请输入 HTTPS 更新清单地址 / Enter an HTTPS manifest URL" }
    return url.toString()
}
data class AppUpdate(val version: String, val code: Int, val sha256: String, val apkUrl: String, val notes: String)
fun parseAppUpdate(value: JsonObject, manifestUrl:String=UPDATE_MANIFEST_URL): AppUpdate {
    require(value.str("applicationId") == "io.github.codexmobilebridge.android") { "更新包应用标识不匹配 / Wrong application" }
    val code=(value["versionCode"] as? JsonPrimitive)?.intOrNull ?: error("无效版本号 / Invalid version code")
    require(value.str("versionName").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    val hash=value.str("sha256")
    val source=normalizeUpdateManifestUrl(manifestUrl).toHttpUrl()
    val url=source.resolve(value.str("apkUrl")) ?: error("无效下载地址 / Invalid download URL")
    require(code > 0 && hash.matches(Regex("[a-f0-9]{64}"))) { "无效更新信息 / Invalid update metadata" }
    require(value.str("apkUrl").isNotBlank() && url.isHttps && url.host==source.host && url.port==source.port && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null && url.query==null && url.encodedPath.endsWith(".apk")) { "无效下载地址 / Invalid download URL" }
    return AppUpdate(value.str("versionName"),code,hash,url.toString(),value.str("notes"))
}
// This separate client never sends gateway cookies or credentials to the update server.
class AppUpdates(private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()) {
    private fun request(url:String)=Request.Builder().url(url).header("User-Agent","CodexMobileBridgeAndroid/${BuildConfig.VERSION_NAME}").header("Cache-Control","no-cache").build()
    private fun readJson(url:String): JsonElement = client.newCall(request(url)).execute().use { response ->
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
    suspend fun download(context:Context, update:AppUpdate): File = withContext(Dispatchers.IO) {
        val dir=File(context.cacheDir,"updates").apply { mkdirs() }
        val file=File(dir,"update.apk")
        file.delete()
        try {
            client.newCall(request(update.apkUrl)).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                require(response.body!!.contentLength() <= 150L*1024*1024)
                response.body!!.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer=ByteArray(8192); var total=0L
                    while(true) { val count=input.read(buffer); if(count<0)break; total+=count; require(total<=150L*1024*1024); output.write(buffer,0,count) }
                } }
            }
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(8192); while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
            check(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "文件校验失败 / Checksum mismatch" }
            verifyPackage(context,file,update)
            file
        } catch(e:Exception) { file.delete(); throw e }
    }
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
    fun install(context:Context,file:File,update:AppUpdate): Boolean {
        verifyPackage(context,file,update)
        if(!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    fun cancel() = client.dispatcher.cancelAll()
}
private fun java.io.InputStream.readBytesBounded(limit:Int): ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) {val n=read(buffer);if(n<0)break;require(output.size()+n<=limit);output.write(buffer,0,n)}
    return output.toByteArray()
}
