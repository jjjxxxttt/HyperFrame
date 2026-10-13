package dev.padlink.lsfg

import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer

/**
 * LSFG 直连渲染通路（PadLink 1.0 直呈架构）：
 *
 * ```
 * MJPEG 解码帧（RGBA）→ AHB 喂帧池 → pushFrame → lsfg_render_loop（Vulkan）→ SurfaceView
 * ```
 *
 * 相比 LSFG-Android 参考实现（MediaProjection 屏幕捕获 + 悬浮层），这里跳过了
 * 「合成器截屏 → 悬浮层再上屏」的 50-80ms 绕路：解码像素直接进帧生成管线，
 * 输出由渲染循环自持的 Vulkan swapchain（失败自动降 CPU blit）直呈到 PadLink
 * 自己的 SurfaceView。
 *
 * 【Surface 单生产者铁律】同一个 SurfaceView 的 BufferQueue 只允许一个生产者：
 * EGL（GSFG 通路）与 VkSurface（本通路）互斥。进/出 LSFG 模式必须严格按
 * 「GL 全停 → detach → attach → init」的顺序切换（由 PreviewActivity 编排）。
 *
 * 线程模型：submitFrame 由 MJPEG 解码线程调用（拷进 AHB 池 + pushFrame 即返回）；
 * surface 事件由 UI 线程调用；渲染循环内部自持工作线程。
 */
class LsfgDirectRenderer(
    /** 采集帧宽（= AHB 池与帧生成上下文的宽） */
    private val frameWidth: Int,
    /** 采集帧高 */
    private val frameHeight: Int,
    private val onLog: (String) -> Unit
) {

    companion object {
        /** AHB 池槽位数：渲染循环队列深度（≤6）之外的余量，轮转间距天然安全 */
        private const val POOL_SIZE = 6
    }

    /** 喂帧池 + 渲染循环是否已就绪 */
    @Volatile
    private var active = false

    private var poolIndex = 0

    /** 已喂入的帧数 */
    @Volatile
    var submittedFrames = 0L
        private set

    /**
     * 启动直连通路：建池 → 绑定输出 surface → 初始化渲染循环。
     *
     * @param surface 输出 SurfaceView 的 surface
     * @param outW/outH 输出窗口尺寸（供 swapchain / setBuffersGeometry 缩放用）
     * @param cacheDir SPIR-V 着色器缓存目录（ShaderExtractor.cacheDir）
     * @param settings 帧生成参数
     * @return 0 = 帧生成已启用；1 = 帧生成不可用、仅直绘（渲染循环照常出画面）；
     *         负值 = 初始化失败，调用方应回退 GSFG/直绘通路。
     */
    fun start(
        surface: Surface,
        outW: Int,
        outH: Int,
        cacheDir: String,
        settings: LsfgSettings
    ): Int {
        if (active) return 0
        val poolRc = NativeBridge.poolCreate(frameWidth, frameHeight, POOL_SIZE)
        if (poolRc != 0) {
            onLog("!! [LSFG] 喂帧池创建失败（错误码 $poolRc）\n")
            return poolRc
        }
        // 顺序照参考实现：先绑 surface 后 init（渲染循环首帧 blit 时惰性建 swapchain）
        NativeBridge.setOutputSurface(surface, outW, outH)
        val framegenFp16 = try {
            NativeBridge.isFramegenFp16Supported(cacheDir)
        } catch (e: Throwable) {
            false
        }
        val rc = NativeBridge.initContext(
            cacheDir,
            frameWidth,
            frameHeight,
            settings.multiplier,
            settings.flowScale,
            false,                  // performance：默认质量档（LSFG_3_1）
            false,                  // hdr
            true,                   // antiArtifacts：高差异帧对抑制插帧
            framegenFp16,
            settings.targetFpsCap,
            0.125f,                 // emaAlpha：帧间隔 EMA 平滑
            4.0f,                   // outlierRatio：帧间隔异常倍率
            2.0f,                   // vsyncSlackMs
            settings.queueDepth
        )
        if (rc < 0) {
            onLog("!! [LSFG] 渲染循环初始化失败（错误码 $rc），回退经典插帧\n")
            NativeBridge.setOutputSurface(null, 0, 0)
            NativeBridge.poolDestroy()
            return rc
        }
        NativeBridge.setBypass(settings.bypass)
        NativeBridge.setAntiArtifacts(true)
        active = true
        submittedFrames = 0L
        poolIndex = 0
        val fgText = if (rc == 1) "帧生成不可用（仅直绘）" else "帧生成已启用"
        onLog(
            ">>> [LSFG] 直连通路已启动：${frameWidth}x${frameHeight} " +
            "倍率=${settings.multiplier}x 光流=${settings.flowScale} " +
            "FP16=$framegenFp16 $fgText\n"
        )
        return rc
    }

    /** surface 尺寸变化（含全屏切换）：重绑输出窗口，渲染循环内部重建 swapchain */
    fun onSurfaceChanged(surface: Surface, outW: Int, outH: Int) {
        if (!active) return
        NativeBridge.setOutputSurface(surface, outW, outH)
    }

    /** surface 销毁：解绑输出窗口（渲染循环照常运行，等新 surface 再绑） */
    fun onSurfaceDestroyed() {
        if (!active) return
        NativeBridge.setOutputSurface(null, 0, 0)
    }

    /**
     * 喂入一解码帧（RGBA8888 直接缓冲，frameWidth×frameHeight×4 字节）。
     * 拷进 AHB 池后立即返回；渲染循环异步消费。调用方可立刻复用 src。
     */
    fun submitFrame(src: ByteBuffer) {
        if (!active) return
        val rc = NativeBridge.poolSubmit(poolIndex, src, System.nanoTime())
        if (rc == 0) {
            poolIndex = (poolIndex + 1) % POOL_SIZE
            submittedFrames++
        } else if (rc != -51 && submittedFrames < 3L) {
            onLog("!! [LSFG] 喂帧失败（错误码 $rc）\n")
        }
    }

    /** 停止直连通路：销毁渲染循环 + 解绑窗口 + 销毁喂帧池（幂等） */
    fun stop() {
        if (!active) {
            NativeBridge.poolDestroy()
            return
        }
        active = false
        try {
            NativeBridge.destroyContext()
        } catch (e: Throwable) {
            onLog("!! [LSFG] 渲染循环销毁异常：$e\n")
        }
        NativeBridge.setOutputSurface(null, 0, 0)
        NativeBridge.poolDestroy()
        onLog(">>> [LSFG] 直连通路已停止\n")
    }

    // ==================== HUD 统计（PerfHud 用） ====================

    /** 实际上屏（post）帧总数 */
    fun postedFrameCount(): Long = if (active) NativeBridge.getPostedFrameCount() else 0L

    /** 内容去重后的采集帧数（≈源帧率） */
    fun uniqueCaptureCount(): Long = if (active) NativeBridge.getUniqueCaptureCount() else 0L

    /** 已生成（插值）帧总数 */
    fun generatedFrameCount(): Long = if (active) NativeBridge.getGeneratedFrameCount() else 0L

    /** 帧平均显示延迟（毫秒，-1 = 无数据） */
    fun averageLatencyMs(): Double = if (active) NativeBridge.getAverageLatencyMs() else -1.0
}

/**
 * LSFG 帧生成参数（由 OverlayPanel「插帧」子页驱动）。
 *
 * @param multiplier 输出帧率倍率（2..8，总帧率 = 源帧率 × 倍率）
 * @param flowScale 光流精度：高=1.0 / 中=0.5 / 低=0.25（越大越精细、越慢）
 * @param targetFpsCap 目标帧率上限（0 = 不限）
 * @param queueDepth 待处理帧队列深度（2..6）
 * @param bypass 调试用：跳过帧生成、直接上屏采集帧
 */
data class LsfgSettings(
    val multiplier: Int = 2,
    val flowScale: Float = 1.0f,
    val targetFpsCap: Int = 0,
    val queueDepth: Int = 4,
    val bypass: Boolean = false
)
