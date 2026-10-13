package dev.padlink.render

import java.nio.ByteBuffer

/**
 * 帧装配器公共接口：YUY2 与 MJPEG 两条装配链给上层（看门狗 / 状态栏）的
 * 统计口径完全一致，上层不需要关心格式差异。
 *
 * 为什么不直接复用 [Yuy2FrameAssembler]：MJPEG 是变长压缩帧（SOI/EOI 分帧 +
 * 解码），与 YUY2 的定长像素流装配实现完全不同，但"帧计数 / 载荷头计数 /
 * 黑场统计 / PTS 延迟"这些**监控口径**必须一致 —— 看门狗正是靠它们判
 * 「取流停滞 / 纯黑场 / 输出冻结」。
 */
interface FrameAssembler {

    /** 装配统计，给界面状态行与结束小结用 */
    data class Counters(
        val frames: Long,
        /** 带载荷头的传输段数 */
        val headerTransfers: Long,
        /** 不带头的传输段数（落在载荷中间被截断的段） */
        val headerlessTransfers: Long,
        /** 因 FID 翻转但数据不足一整帧而被丢弃的半帧数 */
        val partialDrops: Long,
        /** 平均每帧用掉多少段传输 */
        val transfersPerFrame: Double,
        /** 平均每帧线上多少字节（含头开销） */
        val wireBytesPerFrame: Double
    )

    val counters: Counters

    /** 最近一帧的 Y（亮度）均值（稀疏抽样；MJPEG 为解码后像素的亮度近似） */
    val lastYAvg: Double

    /** 最近一帧的亮度极差（max-min） */
    val lastYRange: Int

    /** 最近一帧中部一行的亮度极差：纯黑/无信号时恒为 0，真实画面几乎必有起伏 */
    val lastMidRowRange: Int

    /** 采集 → 装配延迟（毫秒；-1 = 还没测到） */
    val lastCaptureLatencyMs: Double

    /** 新会话开始前调用：清空累计状态 */
    fun reset()

    /**
     * 喂入一段刚收到的 bulk 数据。
     *
     * [buffer] 的 position 停在本次实际收到的字节数上（框架行为），实现里只允许
     * 用绝对下标或 duplicate() 读，绝不改动它的 position/limit —— 调用方随后还要复用。
     */
    fun push(buffer: ByteBuffer, length: Int)

    /** 会话结束时的装配小结（写进诊断日志） */
    fun logSummary()

    /** 释放内部资源（如 MJPEG 的解码线程）；YUY2 无资源可释放 */
    fun release() {}
}
