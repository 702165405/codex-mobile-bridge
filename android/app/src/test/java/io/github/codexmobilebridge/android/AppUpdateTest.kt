package io.github.codexmobilebridge.android

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun metadata(url:String="Codex-Mobile-Bridge-1.0.4.apk")=payload("applicationId" to "io.github.codexmobilebridge.android", "versionName" to "1.0.4", "versionCode" to 5,
        "sha256" to "a".repeat(64), "apkUrl" to url, "notes" to "更新")
    @Test fun remoteCheckReadsDomainManifestWithoutGatewayCredentialsAndReportsFailure() = runBlocking {
        val requests=mutableListOf<String>();var status=200
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();requests+=request.url.toString()
            assertNull(request.header("Cookie"));assertNull(request.header("X-CSRF-Token"));assertNull(request.header("Authorization"))
            assertEquals("no-cache",request.header("Cache-Control"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test").body(metadata().toString().toResponseBody()).build()
        }.build()
        val updates=AppUpdates(client)
        assertEquals(5,updates.check().code);assertEquals(listOf(UPDATE_MANIFEST_URL),requests)
        for(code in listOf(401,404,503,302)) {status=code;assertTrue(runCatching {updates.check()}.isFailure)}
    }
    @Test fun metadataAllowsRelativeOrSameOriginApkOnly() {
        val fields=metadata()
        assertEquals("https://mac.lqilt.top/android/Codex-Mobile-Bridge-1.0.4.apk",parseAppUpdate(fields).apkUrl)
        assertEquals("https://mac.lqilt.top/files/app.apk",parseAppUpdate(metadata("https://mac.lqilt.top/files/app.apk")).apkUrl)
        assertEquals("更新",parseAppUpdate(fields).notes)
        for(change in listOf(payload("applicationId" to "other"),payload("versionCode" to 0),payload("versionCode" to 4294967299L),payload("versionName" to ""),payload("sha256" to "bad")))
            assertTrue(runCatching {parseAppUpdate(kotlinx.serialization.json.JsonObject(fields+change))}.isFailure)
        for(url in listOf("", "http://mac.lqilt.top/app.apk", "https://github.com/other/app.apk", "https://mac.lqilt.top.evil.example/app.apk", "//evil.example/app.apk", "https://mac.lqilt.top:8443/app.apk", "https://user:secret@mac.lqilt.top/app.apk", "app.txt", "app.apk#fragment"))
            assertTrue(url,runCatching {parseAppUpdate(metadata(url))}.isFailure)
    }
    @Test fun updateSourceRequiresPlainHttpsJsonUrl() {
        assertEquals(UPDATE_MANIFEST_URL,normalizeUpdateManifestUrl(" $UPDATE_MANIFEST_URL "))
        for(url in listOf("http://mac.lqilt.top/update.json","https://user:secret@mac.lqilt.top/update.json","https://mac.lqilt.top/update.json#fragment","https://mac.lqilt.top/update.json?token=secret","https://mac.lqilt.top/app.apk"))
            assertTrue(runCatching {normalizeUpdateManifestUrl(url)}.isFailure)
    }
}
