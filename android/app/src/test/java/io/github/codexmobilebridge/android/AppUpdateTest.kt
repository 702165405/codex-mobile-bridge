package io.github.codexmobilebridge.android

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun metadata(url:String="Codex-Mobile-Bridge-1.0.4.apk")=payload("applicationId" to "io.github.codexmobilebridge.android", "versionName" to "1.0.4", "versionCode" to 5,
        "sha256" to "a".repeat(64), "apkUrl" to url, "notes" to "更新")
    @Test fun remoteCheckReadsGitHubManifestWithoutGatewayCredentialsAndReportsFailure() = runBlocking {
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
    @Test fun metadataAllowsOnlyCurrentGitHubRepository() {
        val fields=metadata()
        assertEquals(GITHUB_RELEASE_ROOT+"latest/download/Codex-Mobile-Bridge-1.0.4.apk",parseAppUpdate(fields).apkUrl)
        assertEquals(GITHUB_RELEASE_ROOT+"download/android-v1.0.4/app.apk",parseAppUpdate(metadata(GITHUB_RELEASE_ROOT+"download/android-v1.0.4/app.apk")).apkUrl)
        assertEquals("更新",parseAppUpdate(fields).notes)
        for(change in listOf(payload("applicationId" to "other"),payload("versionCode" to 0),payload("versionCode" to 4294967299L),payload("versionName" to ""),payload("sha256" to "bad")))
            assertTrue(runCatching {parseAppUpdate(kotlinx.serialization.json.JsonObject(fields+change))}.isFailure)
        for(url in listOf("", "http://updates.example.test/app.apk", "https://github.com/other/app.apk", "https://github.com/702165405/other/releases/download/test/app.apk", "https://updates.example.test.evil.example/app.apk", "//evil.example/app.apk", "https://updates.example.test:8443/app.apk", "https://user:secret@updates.example.test/app.apk", "app.txt", "app.apk#fragment"))
            assertTrue(url,runCatching {parseAppUpdate(metadata(url))}.isFailure)
    }
    @Test fun updateSourceRequiresPlainHttpsJsonUrl() {
        assertEquals(UPDATE_MANIFEST_URL,normalizeUpdateManifestUrl(" $UPDATE_MANIFEST_URL "))
        for(url in listOf("http://updates.example.test/update.json","https://user:secret@updates.example.test/update.json","https://updates.example.test/update.json#fragment","https://updates.example.test/update.json?token=secret","https://updates.example.test/app.apk"))
            assertTrue(runCatching {normalizeUpdateManifestUrl(url)}.isFailure)
    }
    @Test fun githubReleaseRedirectsAreBoundedAndRestrictedToTrustedHttpsHosts() = runBlocking {
        val requests=mutableListOf<String>()
        var destination="https://release-assets.githubusercontent.com/example/update.json?signature=fixture"
        val client=OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request=chain.request();requests+=request.url.toString()
            assertNull(request.header("Cookie"));assertNull(request.header("Authorization"))
            val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("test")
            if(request.url.host=="github.com")response.code(302).header("Location",destination).body("".toResponseBody()).build()
            else response.code(200).body(metadata(GITHUB_RELEASE_ROOT+"download/android-v1.0.4/app.apk").toString().toResponseBody()).build()
        }.build()
        val updates=AppUpdates(client)
        assertEquals(5,updates.check().code);assertEquals(2,requests.size)
        for(url in listOf("https://evil.example/update.json","http://release-assets.githubusercontent.com/update.json","https://github.com/other/repo/releases/download/test/update.json",UPDATE_MANIFEST_URL)) {
            requests.clear();destination=url
            assertTrue(url,runCatching {updates.check()}.isFailure)
            assertTrue(requests.size<=6)
        }
    }
}
