package dev.padlink.lsfg

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** 着色器抽取结果：成功 / 失败（错误码 + 中文原因） */
sealed class ExtractResult {
    data object Success : ExtractResult()
    data class Failure(val code: Int, val message: String) : ExtractResult()
}

/**
 * Lossless.dll 着色器抽取器（移植自 LSFG-Android 的 ShaderExtractor）。
 *
 * 流程：SAF URI 拷入私有目录 → SHA-256 → [NativeBridge.extractShaders]
 * （DXBC 资源 → SPIR-V 转译落盘）→ **立即删除 DLL 副本**（License 要求：
 * 专有资产不随包分发、不在设备上留存）→ [NativeBridge.probeShaders]
 * （设备驱动逐个加载校验，提前暴露不兼容）。
 *
 * SPIR-V 缓存布局由原生层约定：`<cacheDir>/<id>.spv`（DXBC 转译，255..302）、
 * `<cacheDir>/fp16/<id>.spv`（304..351）、`<cacheDir>/fp32/<id>.spv`（353..400）。
 */
object ShaderExtractor {

    private const val TAG = "ShaderExtractor"

    /** SPIR-V 着色器缓存目录（原生层按上述布局读写） */
    fun cacheDir(ctx: Context): File = File(ctx.filesDir, "spirv").apply { mkdirs() }

    /** 缓存里是否已有抽取产物（以 255 号探针着色器为准） */
    fun hasCache(ctx: Context): Boolean = File(cacheDir(ctx), "255.spv").exists()

    /**
     * 执行完整抽取流程。同步阻塞（含 DXBC→SPIR-V 转译，可能耗时数秒），
     * 必须在后台线程调用。
     */
    fun extract(ctx: Context, dllUri: Uri): ExtractResult {
        val dllDir = File(ctx.filesDir, "dll").apply { mkdirs() }
        val cache = cacheDir(ctx)
        val dllFile = File(dllDir, "Lossless.dll")

        try {
            ctx.contentResolver.openInputStream(dllUri)?.use { input ->
                dllFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return ExtractResult.Failure(-1, describe(-1))
        } catch (t: Throwable) {
            Log.e(TAG, "拷贝 Lossless.dll 失败", t)
            return ExtractResult.Failure(-1, "读取所选文件失败：${t.message}")
        }

        val sha = sha256(dllFile)
        Log.i(TAG, "Lossless.dll sha256=$sha size=${dllFile.length()}")

        val code = try {
            NativeBridge.extractShaders(dllFile.absolutePath, sha, cache.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "原生 extractShaders 异常", t)
            return ExtractResult.Failure(-1, "着色器抽取异常：${t.message}")
        } finally {
            // 无论成败都删掉 DLL 副本 —— 专有资产不在设备上留存
            dllFile.delete()
        }

        if (code != 0) {
            return ExtractResult.Failure(code, describe(code))
        }

        // 第二道防线：让设备驱动逐个加载刚写出的 SPIR-V。
        // 驱动在 vkCreateShaderModule 时就会拒绝非法 SPIR-V，比等到管线构建时便宜得多。
        val probe = try {
            NativeBridge.probeShaders(cache.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "原生 probeShaders 异常", t)
            return ExtractResult.Failure(-1, "着色器校验异常：${t.message}")
        }
        return if (probe == 0) {
            ExtractResult.Success
        } else {
            ExtractResult.Failure(probe, describe(probe))
        }
    }

    /** 错误码 → 中文提示（与原生层 android_shader_loader / android_vk_probe 的码值一一对应） */
    fun describe(code: Int): String = when (code) {
        0 -> "成功"
        -1 -> "所选文件不是有效的 Lossless.dll"
        -2 -> "缺少所需着色器，请更新 Lossless Scaling"
        -3 -> "着色器转换失败，版本可能不受支持"
        -4 -> "写入存储失败"
        -10 -> "设备无 Vulkan 加载器（LSFG 需要 Vulkan 1.1+）"
        -11 -> "着色器缓存缺失，请重新导入抽取"
        -12 -> "GPU 驱动拒绝着色器，设备可能不兼容"
        else -> "未知错误（$code）"
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
