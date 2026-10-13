package dev.padlink.ota

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 给系统安装器提供 APK 文件的 content:// URI（v0.24.0）。
 *
 * 为什么不直接用 androidx FileProvider：本工程是**零第三方依赖**纯 Kotlin 实现，
 * 为一个 URI 再拖一整个 androidx 不值当。安装器只需要两件事：
 * `getType()` 认出这是 APK、`openFile()` 拿到只读 fd —— 30 行自己写完。
 *
 * URI 形如 `content://dev.padlink.apkprovider/<文件名>`，
 * 只暴露应用私有目录 `files/updates/` 下的文件，路径穿越直接拒绝。
 */
class ApkFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!mode.contains("r") || mode.contains("w")) return null // 只读
        val file = resolve(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(arrayOf(file.name, file.length()))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /** URI → updates 目录下的文件；文件名里带路径分隔符（路径穿越）一律拒绝 */
    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val file = File(updatesDir(ctx), name)
        return if (file.isFile) file else null
    }

    companion object {
        /** 与 AndroidManifest 里 provider 的 authorities 一致 */
        const val AUTHORITY = "dev.padlink.apkprovider"

        /** APK 下载目录（应用私有外部存储，无需任何存储权限） */
        fun updatesDir(context: Context): File =
            File(context.getExternalFilesDir(null), "updates")

        /** 给指定 APK 生成 content:// URI */
        fun uriFor(context: Context, apk: File): Uri =
            Uri.parse("content://$AUTHORITY/${apk.name}")
    }
}
