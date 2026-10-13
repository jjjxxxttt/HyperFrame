package dev.padlink.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * 日志导出公共逻辑：用 MediaStore 写入公共「下载/HyperFrame」目录。
 *
 * 原实现是 MainActivity 的私有方法，取流页面也需要同样的行为，因此抽到这里共用：
 * API 29+ 写公共 Download 目录不需要任何存储权限，insert 返回的 content:// URI
 * 可以直接当附件分享，不必自建 FileProvider。
 */
object LogExporter {

    /** 通过 MediaStore 写入公共下载目录，返回该文件的 content:// URI；失败返回 null */
    fun writeToDownloads(context: Context, fileName: String, content: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/HyperFrame"
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return null
        } catch (t: Throwable) {
            // 写失败就把这条空记录删掉，避免在下载目录里留垃圾
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Throwable) {
                // 忽略清理失败
            }
            return null
        }
        return uri
    }

    /** 拉起系统分享面板；返回是否成功。分享面板打不开不影响导出结果 */
    fun share(context: Context, uri: Uri, fileName: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, "发送日志文件"))
            true
        } catch (t: Throwable) {
            false
        }
    }
}