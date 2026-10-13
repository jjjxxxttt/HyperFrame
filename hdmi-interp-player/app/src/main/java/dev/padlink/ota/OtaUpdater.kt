package dev.padlink.ota

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

/**
 * 局域网 OTA（v0.24.0）：PC 端跑 `ota-server.ps1`，Pad 端自动发现服务器、检查版本、
 * 下载 APK 并拉起系统安装器 —— 全程只在内网里走，不出公网。
 *
 * 协议（与 ota-server.ps1 严格对应）：
 * - **发现**：App 向 255.255.255.255:[DISCOVER_PORT] 发 UDP 广播 `HYPERFRAME-OTA-DISCOVER`，
 *   服务器回 `HYPERFRAME-OTA-SERVER <httpPort>`，App 就拿到 `http://<服务器IP>:<端口>`；
 *   广播被网络禁掉时可手动填地址（主界面会引导）。
 * - **检查**：GET `/version.json` → `{versionCode, versionName, apk, sha256, notes}`，
 *   versionCode 比本机大即有新版本。
 * - **推送**：服务器发现 dist 里有新 APK 时向 [PUSH_PORT] 广播 `HYPERFRAME-OTA-NEW <versionCode>`，
 *   App 监听到就弹更新提示 —— 这就是"内网自动推送版本更新"。
 * - **下载安装**：GET `/apk文件名` 下载到应用私有目录，校验 sha256 后经
 *   [ApkFileProvider]（content:// URI）拉起系统安装器。
 *
 * 零第三方依赖：JSON 用框架自带 org.json，HTTP 用 HttpURLConnection，安装器走 ACTION_VIEW。
 */
object OtaUpdater {

    /** PC 端 HTTP 分发端口（ota-server.ps1 的 -HttpPort） */
    const val HTTP_PORT = 8787

    /** PC 端 UDP 发现应答端口（ota-server.ps1 的 -UdpDiscoverPort） */
    const val DISCOVER_PORT = 8788

    /** PC 端 UDP 推送广播端口（ota-server.ps1 的 -PushPort），App 监听它实现"推送弹窗" */
    const val PUSH_PORT = 8789

    private const val DISCOVER_MAGIC = "HYPERFRAME-OTA-DISCOVER"
    private const val SERVER_MAGIC = "HYPERFRAME-OTA-SERVER"
    private const val PUSH_MAGIC = "HYPERFRAME-OTA-NEW"

    private const val PREFS = "padlink_ota"
    private const val KEY_SERVER = "server"

    /** 服务器版本信息（version.json 的内容） */
    data class OtaInfo(
        val versionCode: Int,
        val versionName: String,
        /** APK 文件名（服务器 dist 目录里相对路径） */
        val apk: String,
        /** APK 的 sha256（小写十六进制），空串表示服务器没给 */
        val sha256: String,
        val notes: String
    )

    /**
     * UDP 广播发现局域网更新服务器，返回 `http://IP:Port`。
     * 找不到时返回上次记住的服务器地址（可能已失效，由 checkServer 再验证）。
     */
    fun discoverServer(timeoutMs: Int = 1500): String? {
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 250
                val msg = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
                sock.send(
                    DatagramPacket(
                        msg, msg.size,
                        InetAddress.getByName("255.255.255.255"), DISCOVER_PORT
                    )
                )
                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                        if (text.startsWith(SERVER_MAGIC)) {
                            val port = text.removePrefix(SERVER_MAGIC).trim().toIntOrNull() ?: HTTP_PORT
                            val base = "http://${pkt.address.hostAddress}:$port"
                            return base
                        }
                    } catch (t: SocketTimeoutException) {
                        // 没等到就继续等到截止时间
                    }
                }
            }
        } catch (t: Throwable) {
            // 广播失败（网络禁广播/无 WiFi 等）→ 走记住的地址兜底
        }
        return null
    }

    /** 记住服务器地址（下次优先试），任意线程可调 */
    fun rememberServer(context: Context, baseUrl: String) {
        try {
            prefs(context).edit().putString(KEY_SERVER, baseUrl.trimEnd('/')).apply()
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 上次记住的服务器地址（没有返回 null） */
    fun savedServer(context: Context): String? = try {
        prefs(context).getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /**
     * 拉取服务器版本信息；服务器不可达/返回不合法返回 null。
     * 有无新版本由调用方拿 [OtaInfo.versionCode] 和本机 versionCode 比。
     */
    fun fetchServerInfo(baseUrl: String): OtaInfo? {
        val url = baseUrl.trimEnd('/') + "/version.json"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.requestMethod = "GET"
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                OtaInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", "?"),
                    apk = json.optString("apk", ""),
                    sha256 = json.optString("sha256", "").trim().lowercase(),
                    notes = json.optString("notes", "").trim()
                )
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 下载 APK 到应用私有目录 `files/updates/`，校验 sha256（服务器给了就必查）。
     * [onProgress] 回传 0..100；失败抛异常（消息已中文化），半截文件自动删掉。
     */
    fun downloadApk(
        context: Context,
        baseUrl: String,
        info: OtaInfo,
        onProgress: (percent: Int) -> Unit = {}
    ): File {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(dir, info.apk)
        val part = File(dir, info.apk + ".part")
        val url = baseUrl.trimEnd('/') + "/" + info.apk
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) {
                    throw IllegalStateException("服务器返回 HTTP ${conn.responseCode}")
                }
                val total = conn.contentLength.toLong()
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256.isNotBlank() && sha != info.sha256) {
                    throw IllegalStateException("校验失败：下载文件的 sha256 与服务器不一致")
                }
            } finally {
                conn.disconnect()
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw IllegalStateException("下载完成但改名失败")
            }
            return target
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** 拉起系统安装器安装 APK（需要用户在系统设置里允许"安装未知应用"） */
    fun installApk(context: Context, apk: File) {
        val uri = ApkFileProvider.uriFor(context, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** 解析推送广播内容 `HYPERFRAME-OTA-NEW <versionCode>`；不是推送包返回 null */
    fun parsePushVersion(text: String): Int? {
        val t = text.trim()
        if (!t.startsWith(PUSH_MAGIC)) return null
        return t.removePrefix(PUSH_MAGIC).trim().toIntOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
