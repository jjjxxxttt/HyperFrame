package dev.padlink.lsfg

import android.hardware.HardwareBuffer
import android.view.Surface
import java.nio.ByteBuffer

/**
 * LSFG 原生帧生成引擎（liblsfg-android.so）的 JNI 声明。
 *
 * 与 lsfg_jni.cpp 中 `Java_dev_padlink_lsfg_NativeBridge_*` 导出一一对应。
 */
object NativeBridge {

    init {
        System.loadLibrary("lsfg-android")
    }

    /** 返回原生库版本字符串（"padlink-lsfg x.y.z"）。 */
    external fun nativeVersion(): String

    /** 初始化崩溃报告器，指定崩溃转储文件与日志文件路径。 */
    external fun initCrashReporter(crashPath: String, logPath: String)

    /** 从 Lossless Scaling DLL 提取着色器资源并转存为 SPIR-V 缓存，返回错误码。 */
    external fun extractShaders(dllPath: String, dllSha256: String, cacheDir: String): Int

    /** 用设备驱动校验缓存中的 SPIR-V 着色器是否全部可用，返回错误码。 */
    external fun probeShaders(cacheDir: String): Int

    /**
     * 初始化帧生成上下文并启动渲染循环。
     *
     * @param cacheDir SPIR-V 着色器缓存目录
     * @param width/height 采集帧尺寸
     * @param multiplier 输出帧率倍率（2 = 30fps 插到 60fps）
     * @param flowScale 光流质量（0.25..1.0，越大越精细）
     * @param performance 性能模式（LSFG_3_1P）
     * @param hdr HDR 输入
     * @param antiArtifacts 高差异帧对抑制插帧
     * @param framegenFp16 使用 FP16 着色器变体
     * @param targetFpsCap 目标帧率上限（0 = 不限）
     * @param emaAlpha 帧间隔 EMA 平滑系数
     * @param outlierRatio 帧间隔异常值判定倍率
     * @param vsyncSlackMs 对齐 vsync 的松弛毫秒数
     * @param queueDepth 待处理帧队列深度（2..6）
     * @return 0 成功；负值为失败；1 表示帧生成不可用（回退镜像模式）
     */
    external fun initContext(
        cacheDir: String,
        width: Int,
        height: Int,
        multiplier: Int,
        flowScale: Float,
        performance: Boolean,
        hdr: Boolean,
        antiArtifacts: Boolean,
        framegenFp16: Boolean,
        targetFpsCap: Int,
        emaAlpha: Float,
        outlierRatio: Float,
        vsyncSlackMs: Float,
        queueDepth: Int,
    ): Int

    /** 查询设备是否支持 FP16 帧生成（需 GPU 支持 shaderFloat16 且 FP16 缓存已就绪）。 */
    external fun isFramegenFp16Supported(cacheDir: String): Boolean

    /** 绑定（或替换）输出 Surface；传 null 表示解绑。 */
    external fun setOutputSurface(surface: Surface?, w: Int, h: Int)

    /** 推入一帧采集画面（AHardwareBuffer），timestampNs 为采集时间戳（纳秒）。 */
    external fun pushFrame(hardwareBuffer: HardwareBuffer, timestampNs: Long)

    /** 已生成（插值）帧总数。 */
    external fun getGeneratedFrameCount(): Long

    /** 实际上屏（post）帧总数。 */
    external fun getPostedFrameCount(): Long

    /** 内容去重后的真实采集帧数（目标应用的真实渲染帧率）。 */
    external fun getUniqueCaptureCount(): Long

    /** 帧平均入队等待时间（毫秒）。 */
    external fun getAverageQueueMs(): Double

    /** 帧平均显示延迟（毫秒）。 */
    external fun getAverageLatencyMs(): Double

    /** 读取最近若干次上屏间隔（纳秒）到 outArray，返回实际写入条数。 */
    external fun getRecentPostIntervalsNs(outArray: LongArray): Int

    /** 读取最近一个性能剖析窗口的统计到 outArray（6 个元素），返回写入条数。 */
    external fun getProfileWindowNs(outArray: LongArray): Int

    /** 开关旁路模式：开启后跳过帧生成，直接上屏原始采集帧。 */
    external fun setBypass(bypass: Boolean)

    /** 开关高差异帧对的插帧抑制（抗伪影）。 */
    external fun setAntiArtifacts(enabled: Boolean)

    /** 上报显示刷新周期（纳秒），用于帧节奏对齐 vsync。 */
    external fun setVsyncPeriodNs(periodNs: Long)

    /** 热更新帧节奏参数：帧率上限、EMA 系数、异常倍率、vsync 松弛、队列深度。 */
    external fun setPacingParams(
        targetFpsCap: Int,
        emaAlpha: Float,
        outlierRatio: Float,
        vsyncSlackMs: Float,
        queueDepth: Int,
    )

    /** 销毁帧生成上下文并停止渲染循环（可重复调用）。 */
    external fun destroyContext()

    // ==================== AHB 喂帧池（PadLink 直连通路） ====================

    /**
     * 分配 count 个 width×height 的 RGBA8888 AHardwareBuffer 喂帧池。
     * 已有池存在时先整体销毁重建。返回 0 成功；-30 分配失败；-52 参数非法。
     */
    external fun poolCreate(width: Int, height: Int, count: Int): Int

    /**
     * 把一帧 RGBA 像素（直接缓冲，width×height×4 字节）拷进 index 号池位并
     * 喂给渲染循环。返回 0 成功；-50 池未建；-51 序号越界；-52 字节数不足；
     * -53 写锁失败。
     */
    external fun poolSubmit(index: Int, src: ByteBuffer, timestampNs: Long): Int

    /** 销毁喂帧池（幂等）。 */
    external fun poolDestroy()
}
