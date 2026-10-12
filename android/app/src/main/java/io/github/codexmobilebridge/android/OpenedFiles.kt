package io.github.codexmobilebridge.android

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

class OpenedFileProvider : FileProvider()

/** Share only a completed private copy; never expose downloads in progress or updates. */
object OpenedFiles {
    fun prepare(context: Context, source: File, name: String): Uri {
        require(source.isFile)
        require(name.isNotBlank() && name !in listOf(".","..") && name.none { it=='/' || it=='\\' || it.isISOControl() })
        val root=File(context.cacheDir,"opened-files").apply {check(isDirectory || mkdirs())}
        val cutoff=System.currentTimeMillis()-24L*60*60*1000
        root.listFiles()?.filter {it.isDirectory && it.lastModified()<cutoff}?.forEach {it.deleteRecursively()}
        val folder=File(root,UUID.randomUUID().toString()).apply {check(mkdir())}
        try {
            val copy=source.copyTo(File(folder,name))
            return FileProvider.getUriForFile(context,context.packageName+".opened-files",copy)
        } catch(e:Exception) {folder.deleteRecursively();throw e}
    }
    fun intent(context: Context, uri: Uri): Intent {
        val mime=context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {clipData=ClipData.newRawUri("download",uri)}
    }
    fun open(context: Context, uri: Uri) {context.startActivity(intent(context,uri))}
}
