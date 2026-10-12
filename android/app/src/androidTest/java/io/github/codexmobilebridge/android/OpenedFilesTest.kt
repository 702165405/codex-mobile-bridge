package io.github.codexmobilebridge.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenedFilesTest {
    @Test fun completedCopyHasReadOnlyIntentAndSurvivesOriginalDeletion() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val source=File.createTempFile("fixture-",".part",context.cacheDir).apply {writeText("PDF fixture")}
        val uri=OpenedFiles.prepare(context,source,"report.pdf");source.delete()
        assertEquals(context.packageName+".opened-files",uri.authority)
        assertEquals("application/pdf",context.contentResolver.getType(uri))
        assertEquals("PDF fixture",context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()})
        val intent=OpenedFiles.intent(context,uri)
        assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals(uri,intent.data)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
    }
    @Test fun unrelatedCacheAndTraversalNamesCannotBeShared() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val source=File.createTempFile("unshared-",".part",context.cacheDir).apply {writeText("fixture")}
        try {
            for(name in listOf("../private","folder/file","folder\\file","..","bad\nname")) {
                try {OpenedFiles.prepare(context,source,name);fail("Unsafe name accepted")}catch(_:IllegalArgumentException){}
            }
            try {FileProvider.getUriForFile(context,context.packageName+".opened-files",source);fail("Cache root exposed")}catch(_:IllegalArgumentException){}
            val update=File(context.cacheDir,"updates/unshared.apk").apply {parentFile!!.mkdirs();writeText("fixture")}
            try {FileProvider.getUriForFile(context,context.packageName+".opened-files",update);fail("Update cache exposed")}catch(_:IllegalArgumentException){}finally{update.delete()}
        } finally {source.delete()}
    }
}
