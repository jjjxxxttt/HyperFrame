package dev.padlink.render

import java.nio.ByteBuffer
import java.util.Locale

/**
 * YUY2 整帧装配器：把一段段 bulk 传输拼回完整的一帧 YUY2 像素。
 *
 * ==================== 载荷头：每一段传输都带（这是 v0.11.0 踩的坑） ====================
 *
 * v0.11.0 只在一帧的第一段剥头，依据是"帧首才会带载荷头、且帧首必落在传输边界"。
 * 实测日志把这两个前提都推翻了：
 * - 会话刚开始连续 5 段传输各自只有 12 字节（`距上帧=12`），说明**载荷头可以单独占一段传输**
 *   （设备发完 12 字节头就用短包结束这次传输），也可能和数据同段；
 * - 更关键的是，如果只在帧首剥头，那么**后面每一段的 12 字节头都会被当成像素数据**
 *   写进帧缓冲。`0c cc 00 00 00 00 40 fa 00 00 0e 01` 当成 YUY2 解出来是
 *   Y=12 / Cb=204 / Y=0 / Cr=0，Y=12 已经在有限范围基准 16 之下，整帧被拉黑
 *   —— 这正是"有输出但画面全黑"的直接原因。
 *
 * 所以现在的做法与 Linux uvcvideo / libuvc 一致：**每一段传输都按同一个规则处理**。
 *
 * ==================== 帧边界：用 FID 判，而不是靠字节数凑 ====================
 *
 * 每段载荷头第二个字节是 bBitmap，bit0 = FID、bit1 = EOF。FID 在同一帧的所有载荷上不变、
 * 帧与帧之间翻转，因此"FID 变化"就是权威的帧边界（EOF 位本机的头里一直是 0，用不上）。
 * 同时保留一条兜底：累计数据到满一整帧也直接出帧 —— 会话刚开始是从流中间接入的，
 * 第一条 FID 边界的含义不可靠，靠字节数先对齐。
 *
 * 每次出帧都只取缓冲的前 [frameBytes] 字节：协议上的帧大小就是像素字节数，载荷头是
 * 额外开销，多出来的零头直接丢弃，且下次出帧会重新从 FID 边界开始，误差不会累积。
 *
 * ==================== 宽高参数化（v0.18.0） ====================
 *
 * 之前宽高硬编码 1920x1080，源一换分辨率整条装配链就废了。现在宽高由
 * [dev.padlink.uvc.StreamProfile] 传入，帧缓冲 / 行宽 / 出帧门槛全部按档位算。
 *
 * ==================== 黑场快速统计 + 延迟基线（v0.18.0） ====================
 *
 * 每出一帧做一次稀疏抽样（步长 [QUICK_STRIDE]），给出 Y 均值 / 极差与中部一行的极差，
 * 供上层判断"采集卡是不是没出画面"（源分辨率切换后的典型症状是恒定 Y=16 或全 0 黑场）。
 * 同时用载荷头里的 PTS/SCR（设备时钟 125MHz）换算"采集 → 装配"延迟。
 */
class Yuy2FrameAssembler(
    /** 帧宽（像素），来自 StreamProfile */
    private val width: Int,
    /** 帧高（像素），来自 StreamProfile */
    private val height: Int,
    /** 每装配出一整帧就回调一次；回调必须同步消费（返回后这块 buffer 会被立刻复用） */
    private val onFrame: (ByteBuffer) -> Unit,
    /** 诊断日志（已带换行） */
    private val onLog: (String) -> Unit
) : FrameAssembler {

    /** 一整帧 YUY2 的字节数 */
    private val frameBytes = width * height * 2

    /** 一行 YUY2 的字节数 */
    private val rowBytes = width * 2

    /** FID 翻转时，累计数据不到这个量就不出帧（按一整帧的 95% 算） */
    private val minFrameBytes = (frameBytes * 95) / 100

    /** 一整帧 YUY2 的直接缓冲：直接喂给 glTexSubImage2D */
    private val frame = ByteBuffer.allocateDirect(frameBytes)

    /** 当前帧已填入的字节数 */
    private var filled = 0

    /** 当前帧是否已经开始累计数据（用于判断 FID 翻转时该不该出帧） */
    private var frameStarted = false

    /** 上一个载荷头的 FID；-1 表示还没读到过 */
    private var lastFid = -1

    private var frames = 0L
    private var headerTransfers = 0L
    private var headerlessTransfers = 0L
    private var partialDrops = 0L
    private var totalReceived = 0L
    private var totalTransfers = 0L

    /** 前几段传输打详细信息（长度 + 头 4 字节），一次就能看清帧的真实分段结构 */
    private var dumpPrinted = 0

    /** 数据抽样诊断的节流：每 [STATS_EVERY_FRAMES] 帧打一次 */
    private var lastStatsFrame = -STATS_EVERY_FRAMES

    // ---- 黑场快速统计（每帧更新，任意线程可读）----

    @Volatile
    private var yAvg = 0.0

    @Volatile
    private var yRange = 0

    @Volatile
    private var midRowRange = 0

    override val lastYAvg: Double
        get() = yAvg

    override val lastYRange: Int
        get() = yRange

    override val lastMidRowRange: Int
        get() = midRowRange

    // ---- 延迟基线（PTS/SCR，设备时钟 125MHz）----

    /** 当前帧首个载荷的 PTS（-1 = 还没读到） */
    private var framePts = -1L

    /** 当前帧首个载荷到达时的主机时刻 */
    private var frameArrivalNs = 0L

    /** SCR 锚点：设备时钟刻度 + 对应主机时刻 */
    private var scrTick = 0.0
    private var scrHostNs = 0L

    /** 采集 → 装配延迟（毫秒；-1 = 还没测到） */
    @Volatile
    private var captureLatencyMs = -1.0

    override val lastCaptureLatencyMs: Double
        get() = captureLatencyMs

    override val counters: FrameAssembler.Counters
        get() = FrameAssembler.Counters(
            frames = frames,
            headerTransfers = headerTransfers,
            headerlessTransfers = headerlessTransfers,
            partialDrops = partialDrops,
            transfersPerFrame = if (frames > 0) totalTransfers.toDouble() / frames else 0.0,
            wireBytesPerFrame = if (frames > 0) totalReceived.toDouble() / frames else 0.0
        )

    /** 新会话开始前调用：清空累计状态 */
    override fun reset() {
        filled = 0
        frameStarted = false
        lastFid = -1
        frames = 0L
        headerTransfers = 0L
        headerlessTransfers = 0L
        partialDrops = 0L
        totalReceived = 0L
        totalTransfers = 0L
        dumpPrinted = 0
        lastStatsFrame = -STATS_EVERY_FRAMES
        yAvg = 0.0
        yRange = 0
        midRowRange = 0
        framePts = -1L
        frameArrivalNs = 0L
        scrTick = 0.0
        scrHostNs = 0L
        captureLatencyMs = -1.0
    }

    /**
     * 喂入一段刚收到的 bulk 数据。
     *
     * [buffer] 的 position 停在本次实际收到的字节数上（框架行为），这里只用绝对下标和
     * duplicate() 读它，绝不改动它的 position/limit —— 调用方随后还要 clear() 复用。
     */
    override fun push(buffer: ByteBuffer, length: Int) {
        if (length <= 0) return
        totalTransfers++
        totalReceived += length

        val b0 = buffer.get(0).toInt() and 0xFF
        val b1 = if (length >= 2) buffer.get(1).toInt() and 0xFF else 0
        // 合法载荷头：bHeaderLength 至少 2（含它自己和 bBitmap），最多 12（2 + PTS 4 + SCR 6）
        val hasHeader = b0 in MIN_HEADER_LEN..MAX_HEADER_LEN && b0 <= length

        if (dumpPrinted < DUMP_LIMIT) {
            dumpPrinted++
            onLog(
                String.format(
                    Locale.US,
                    ">>> [分段] 第 %d 段 长度=%d 头 4 字节=%02x %02x %02x %02x ｜ 判为%s\n",
                    totalTransfers,
                    length,
                    b0,
                    b1,
                    if (length >= 3) buffer.get(2).toInt() and 0xFF else 0,
                    if (length >= 4) buffer.get(3).toInt() and 0xFF else 0,
                    if (hasHeader) "带载荷头(剥 $b0 字节)" else "无头(整段都是数据)"
                )
            )
        }

        var dataStart = 0
        if (hasHeader) {
            headerTransfers++
            val fid = b1 and 0x01
            dataStart = b0
            if (frameStarted && fid != lastFid) {
                // FID 翻转 = 新一帧的第一段载荷 → 上一帧在此结束
                if (filled >= minFrameBytes) {
                    emitFrame("FID 翻转")
                } else {
                    // 数据不够一整帧：多半是从流中间接入时的残留，丢掉重新对齐
                    partialDrops++
                    filled = 0
                    frameStarted = false
                }
            }
            lastFid = fid
            // PTS（bmHeaderInfo bit2）/ SCR（bit3）：延迟基线用。
            // PTS 是本帧的采集时刻（设备时钟），SCR 是设备时钟的"当前值 + 主机 SOF"锚点。
            if (!frameStarted && dataStart >= 6 && (b1 and 0x04) != 0) {
                framePts = u32le(buffer, 2)
                frameArrivalNs = System.nanoTime()
            }
            if (dataStart >= 12 && (b1 and 0x08) != 0) {
                scrTick = u32le(buffer, 6).toDouble()
                scrHostNs = System.nanoTime()
            }
        } else {
            headerlessTransfers++
        }

        appendData(buffer, dataStart, length)
    }

    /** 把本段传输的载荷数据接到当前帧后面 */
    private fun appendData(buffer: ByteBuffer, start: Int, length: Int) {
        var pos = start
        while (pos < length) {
            val n = minOf(length - pos, frameBytes - filled)
            if (n <= 0) break
            val dup = buffer.duplicate()
            dup.position(pos)
            dup.limit(pos + n)
            frame.position(filled)
            frame.put(dup)
            filled += n
            frameStarted = true
            pos += n
            if (filled >= frameBytes) {
                // 兜底路径：累计满一整帧就出帧（会话刚开始时靠它先对齐）
                emitFrame("满一帧")
            }
        }
    }

    /** 出帧：只取前 [frameBytes] 字节，多余的零头丢掉，并且下次从 FID 边界重新开始 */
    private fun emitFrame(reason: String) {
        if (filled <= 0) return
        frames++
        frame.position(0)
        frame.limit(frameBytes)
        updateQuickStats()
        updateCaptureLatency()
        // 抽样诊断：直接回答"数据里到底有没有画面内容"，避免只能靠肉眼看屏幕
        if (frames - lastStatsFrame >= STATS_EVERY_FRAMES) {
            lastStatsFrame = frames
            logFrameStats(reason)
        }
        onFrame(frame)
        filled = 0
        frameStarted = false
        framePts = -1L
    }

    /**
     * 每帧一次的稀疏抽样（步长 [QUICK_STRIDE]）：Y 均值 / 极差 + 中部一行极差。
     *
     * 判"黑场/无信号"的组合条件（上层用）：均值很低 **且** 中部一行毫无起伏。
     * 真实的暗场景再黑，一整行 1920 个像素也会有起伏；采集卡丢输入后的黑场
     * （恒 Y=16 或全 0）则整行如尺子画出来一样平。
     */
    private fun updateQuickStats() {
        var minY = 255
        var maxY = 0
        var sum = 0L
        var n = 0L
        var i = 0
        while (i < frameBytes) {
            val y = frame.get(i).toInt() and 0xFF
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sum += y
            n++
            i += QUICK_STRIDE
        }
        yAvg = if (n > 0) sum.toDouble() / n else 0.0
        yRange = maxY - minY

        val rowStart = rowBytes * (height / 2)
        val rowEnd = minOf(rowStart + rowBytes, frameBytes)
        var rMin = 255
        var rMax = 0
        var j = rowStart
        while (j < rowEnd) {
            val y = frame.get(j).toInt() and 0xFF
            if (y < rMin) rMin = y
            if (y > rMax) rMax = y
            j += QUICK_STRIDE
        }
        midRowRange = rMax - rMin
    }

    /**
     * 采集 → 装配延迟（延迟基线 v1）。
     *
     * 设备时钟 125MHz（VC_CLOCK_FREQUENCY）：SCR 给出锚点 (设备刻度, 主机时刻)，
     * 于是"设备现在" ≈ scrTick + (主机流逝 ns) × 0.125 刻度/ns；
     * 延迟 = 设备现在 − PTS。两者都是 32 位环回的刻度，差值按 2^32 取模归一化。
     */
    private fun updateCaptureLatency() {
        if (framePts < 0 || scrHostNs == 0L) return
        val deviceNow = scrTick + (System.nanoTime() - scrHostNs) * TICKS_PER_NS
        var diff = deviceNow - framePts
        diff = ((diff % TWO_POW_32) + TWO_POW_32) % TWO_POW_32
        if (diff > TWO_POW_31) diff -= TWO_POW_32
        val ms = diff / TICKS_PER_MS
        // 合理性窗口（0 ~ 5 秒）外当噪声丢掉
        if (ms in 0.0..5000.0) captureLatencyMs = ms
    }

    /**
     * 抽样打印一帧的像素统计。
     *
     * Y 抽样（每隔 [STATS_STRIDE] 字节取一个，取到的是行内偶数位置的 Y 分量）的
     * 最小/最大/均值能一眼区分三种情况：
     * - 均值 ≈ 16 且极差极小 → 采集到的就是纯黑场（源头没出画面）；
     * - 均值/极差正常 → 数据里有画面，问题在渲染侧；
     * - 全是 0 → 数据没真正到达（纹理/拷贝链路问题）。
     */
    private fun logFrameStats(reason: String) {
        var minY = 255
        var maxY = 0
        var sum = 0L
        var count = 0L
        var i = 0
        while (i < frameBytes) {
            val y = frame.get(i).toInt() and 0xFF
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sum += y
            count++
            i += STATS_STRIDE
        }
        val avg = if (count > 0) sum.toDouble() / count else 0.0

        val head = StringBuilder()
        for (k in 0 until 16) {
            head.append(String.format(Locale.US, "%02x ", frame.get(k).toInt() and 0xFF))
        }
        // 画面中部某一行的开头 16 字节：确认不是只有首行有数据
        val rowOffset = rowBytes * (height / 2)
        val row = StringBuilder()
        for (k in 0 until 16) {
            row.append(String.format(Locale.US, "%02x ", frame.get(rowOffset + k).toInt() and 0xFF))
        }

        // 帧末尾 16 字节：用来判断"多出来的线上字节"是污染了帧尾，还是仅影响出帧时机
        val tail = StringBuilder()
        for (k in 0 until 16) {
            tail.append(String.format(Locale.US, "%02x ", frame.get(frameBytes - 16 + k).toInt() and 0xFF))
        }

        onLog(
            String.format(
                Locale.US,
                ">>> [数据] 帧#%d（%s）Y min/max/avg = %d/%d/%.1f ｜ 首16: %s｜ 行%d: %s｜ 帧尾16: %s\n",
                frames,
                reason,
                minY,
                maxY,
                avg,
                head.toString(),
                height / 2,
                row.toString(),
                tail.toString()
            )
        )
    }

    /** 会话结束时的装配小结：这里的两个"每帧平均"能直接判出载荷头是每段都带还是每帧只带一个 */
    override fun logSummary() {
        val c = counters
        val bytesMinusPixels = c.wireBytesPerFrame - frameBytes
        val verdict = when {
            c.frames == 0L -> "一帧都没装配出来，需回看 [分段] 日志确认载荷结构"
            kotlin.math.abs(bytesMinusPixels - HEADER_BYTES * c.transfersPerFrame) < HEADER_BYTES * 2 + 24 ->
                "每段传输都带一个载荷头（已全部剥掉）"
            kotlin.math.abs(bytesMinusPixels - HEADER_BYTES) < 64 ->
                "每帧只有一个载荷头"
            else -> "载荷结构异常：每帧线上比像素多 ${String.format(Locale.US, "%.1f", bytesMinusPixels)} 字节，而每帧有 ${String.format(Locale.US, "%.1f", c.transfersPerFrame)} 段"
        }
        onLog("")
        onLog("---- 帧装配小结 ----\n")
        onLog(String.format(Locale.US, "装配完整帧数    : %d（%dx%d）\n", c.frames, width, height))
        onLog(String.format(Locale.US, "带头的传输段数  : %d\n", c.headerTransfers))
        onLog(String.format(Locale.US, "无头的传输段数  : %d（落在载荷中间被截断的段，整段当数据处理）\n", c.headerlessTransfers))
        onLog(String.format(Locale.US, "丢弃的半帧数    : %d\n", c.partialDrops))
        onLog(String.format(Locale.US, "平均每帧段数    : %.2f 段\n", c.transfersPerFrame))
        onLog(String.format(Locale.US, "平均每帧线上字节: %.1f（像素 %d + 额外 %.1f）\n", c.wireBytesPerFrame, frameBytes, bytesMinusPixels))
        if (lastCaptureLatencyMs >= 0) {
            onLog(String.format(Locale.US, "采集→装配延迟    : %.1f ms（PTS/SCR 换算）\n", lastCaptureLatencyMs))
        }
        onLog(">>> 结论: $verdict\n")
    }

    private fun u32le(d: ByteBuffer, o: Int): Long =
        (d.get(o).toLong() and 0xFF) or
            ((d.get(o + 1).toLong() and 0xFF) shl 8) or
            ((d.get(o + 2).toLong() and 0xFF) shl 16) or
            ((d.get(o + 3).toLong() and 0xFF) shl 24)

    companion object {
        /** 合法载荷头长度下限：bHeaderLength 至少含它自己和 bBitmap */
        private const val MIN_HEADER_LEN = 2

        /** 合法载荷头长度上限：2 + PTS(4) + SCR(6) = 12 */
        private const val MAX_HEADER_LEN = 12

        /** 本机实测的载荷头长度，用于小结里的期望值比较 */
        private const val HEADER_BYTES = 12

        /** 前几段传输打详细分段信息 */
        private const val DUMP_LIMIT = 12

        /** 数据抽样诊断每多少帧打一次 */
        private const val STATS_EVERY_FRAMES = 60L

        /** 抽样步长（保持偶数，取到的都是 Y 分量） */
        private const val STATS_STRIDE = 64

        /** 每帧快速统计的抽样步长（保持偶数） */
        private const val QUICK_STRIDE = 512

        /** 设备时钟 125MHz：0.125 刻度/ns */
        private const val TICKS_PER_NS = 0.125

        /** 125 刻度/µs = 125000 刻度/ms */
        private const val TICKS_PER_MS = 125_000.0

        private const val TWO_POW_32 = 4294967296.0
        private const val TWO_POW_31 = 2147483648.0
    }
}
