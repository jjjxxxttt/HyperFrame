package dev.padlink.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.util.Locale

/**
 * MJPEG 整帧装配 + 解码器：把一段段 bulk 传输拼回完整 JPEG，再解码成 RGBA 喂给渲染器。
 *
 * 为什么需要 MJPEG（v0.22.0）：1080p120 / 4K60 的 **YUY2** 理论带宽分别是
 * 497.7 / 995.5 MB/s，USB 3.x 实测撑死 ~380-420 MB/s，物理上不可持续
 * （v0.21.0 实测 4K30 YUY2 也是 2-5 秒就 queue 全灭）。MJPEG 是压缩流，
 * 同档位典型只有 100~200 MB/s，是实现 1080p120 / 4K60 的唯一可行路线。
 *
 * ==================== 载荷头 / 帧边界（与 YUY2 装配器同一套规则） ====================
 *
 * 每段传输都带 12 字节 UVC 载荷头（bHeaderLength + bBitmap + PTS + SCR），
 * **每段都要剥**（只在帧首剥会把后续每段的头字节混进 JPEG 流，解码直接坏帧）。
 * 帧边界用 FID（bBitmap bit0）翻转判定；MJPEG 帧内容再用 SOI(FF D8) / EOI(FF D9)
 * 双重校验，取不到完整边界就丢弃该半帧。
 *
 * ==================== 解码线程池（v1.1.2 并行化） ====================
 *
 * USB 取流线程绝不能做解码（1080p JPEG 软解 3~6ms、4K 15ms+，会拖垮 request 流水线），
 * 所以装配完成后丢给解码线程池（积压丢最旧），解码结果走与 YUY2 相同的
 * `onFrame(直接缓冲)` 契约交给渲染器。[FrameAssembler.counters] 的帧数在**解码成功**
 * 时才累计 —— 看门狗"帧计数在涨"因此等价于"画面真的在推进"。
 *
 * 解码用 [BitmapFactory] + `inBitmap` 复用位图：4K RGBA 一帧 33MB，
 * 每帧新分配会把 GC 打爆。位图像素内存序是 RGBA（GLUtils 对 ARGB_8888 也是按
 * GL_RGBA 上传），可直接 glTexSubImage2D。线程池里每条线程持有私有位图与输出缓冲；
 * 并行解码会乱序完成，输出侧按帧序号单调把关，旧帧直接丢（画面绝不回跳）。
 *
 * 黑场快速统计（[FrameAssembler.lastYAvg] 等）在解码后的像素上按亮度近似
 * Y ≈ 0.299R + 0.587G + 0.114B 稀疏抽样，口径与 YUY2 装配器一致。
 */
class MjpegFrameAssembler(
    /** 帧宽（像素），来自 StreamProfile */
    private val width: Int,
    /** 帧高（像素），来自 StreamProfile */
    private val height: Int,
    /** JPEG 帧缓冲上限（字节）：StreamProfile.maxVideoFrameSize（dwMaxVideoFrameBufferSize） */
    maxFrameBytes: Int,
    /** 每解码出一整帧就回调一次（RGBA 直接缓冲）；回调必须同步消费（返回后缓冲会被复用） */
    private val onFrame: (ByteBuffer) -> Unit,
    /** 诊断日志（已带换行） */
    private val onLog: (String) -> Unit
) : FrameAssembler {

    /** 解码后一帧 RGBA 的字节数 */
    private val rgbaBytes = width * height * 4

    /** 一行 RGBA 的字节数 */
    private val rowRgbaBytes = width * 4

    /** JPEG 帧缓冲：按描述符 dwMaxVideoFrameBufferSize 上限，兜底 256KB */
    private val jpegCapacity = maxFrameBytes.coerceAtLeast(256 * 1024)

    private val jpegBuf = ByteArray(jpegCapacity)

    /** 当前帧已填入的字节数 */
    private var filled = 0

    /**
     * 增量扫描位置（v0.31.0 吞吐根因修复）：[0, scanPos) 已扫描判定完毕，
     * 每段传输只扫**新增字节**，不再从头重扫整个累积缓冲（旧实现 O(帧²) 扫描
     * 把取流线程钉死在 ~700μs/段、吞吐上限 22~24MB/s）。
     */
    private var scanPos = 0

    /** 是否已见到当前帧的 SOI（入帧后 SOI 恒位于缓冲 0 号位） */
    private var inFrame = false

    @Volatile
    private var frames = 0L

    private var headerTransfers = 0L
    private var headerlessTransfers = 0L
    private var partialDrops = 0L
    private var totalReceived = 0L
    private var totalTransfers = 0L
    private var decodeFailures = 0L
    private var droppedForDecode = 0L

    /** 前几段传输打详细信息（长度 + 头 4 字节） */
    private var dumpPrinted = 0

    /** 数据抽样诊断的节流：每 [STATS_EVERY_FRAMES] 帧打一次 */
    private var progressLogged = 0L

    // ---- 黑场快速统计（解码线程写，任意线程读）----

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

    // ---- 延迟基线（PTS/SCR，设备时钟 125MHz，与 YUY2 装配器同口径）----

    private var framePts = -1L
    private var scrTick = 0.0
    private var scrHostNs = 0L

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

    // ==================== 解码线程池（v1.1.2 并行化） ====================
    // 单线程 BitmapFactory 软解 1080p 约 10-15ms/帧，正好卡在 120fps 的 8.3ms 边缘
    //（v1.1.1 实测 34% 的帧被"丢最旧保最新"）。改为 DECODE_THREADS 条线程并行：
    // JPEG 帧互相独立、可乱序解码；输出侧按帧序号单调把关，解码慢的旧帧直接丢，
    // 画面绝不回跳。每条线程持有私有的复用位图与 RGBA 缓冲，无跨线程共享。

    /** 一帧待解码任务（seq 全局单调递增，输出侧去乱序用） */
    private data class PendingFrame(val seq: Long, val jpeg: ByteArray)

    private val decodeLock = Object()

    /** 待解码队列（满则丢队头 = 最旧帧） */
    private val pendingQueue = ArrayDeque<PendingFrame>()
    private var nextSeq = 0L

    /** 输出串行化锁：onFrame 的契约是"回调同步消费"，任意时刻只允许一条线程在回调里 */
    private val outputLock = Object()

    /** 已上屏的最大帧序号；比它小的解码结果直接丢弃（画面不回跳） */
    private var lastOutputSeq = -1L

    @Volatile
    private var decoding = true

    private val decodeThreads = ArrayList<Thread>()

    init {
        repeat(DECODE_THREADS) { i ->
            val t = Thread({ decodeLoop() }, "padlink-mjpeg-decode-$i")
            decodeThreads.add(t)
            t.start()
        }
    }

    override fun reset() {
        filled = 0
        scanPos = 0
        inFrame = false
        frames = 0L
        headerTransfers = 0L
        headerlessTransfers = 0L
        partialDrops = 0L
        totalReceived = 0L
        totalTransfers = 0L
        decodeFailures = 0L
        droppedForDecode = 0L
        dumpPrinted = 0
        progressLogged = 0L
        yAvg = 0.0
        yRange = 0
        midRowRange = 0
        framePts = -1L
        scrTick = 0.0
        scrHostNs = 0L
        captureLatencyMs = -1.0
        synchronized(decodeLock) {
            pendingQueue.clear()
            decodeLock.notifyAll()
        }
        synchronized(outputLock) {
            lastOutputSeq = -1L
        }
    }

    override fun release() {
        decoding = false
        synchronized(decodeLock) { decodeLock.notifyAll() }
        for (t in decodeThreads) {
            try {
                t.join(1500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        decodeThreads.clear()
    }

    // ==================== 装配（USB 取流线程） ====================

    override fun push(buffer: ByteBuffer, length: Int) {
        if (length <= 0) return
        totalTransfers++
        totalReceived += length

        val b0 = buffer.get(0).toInt() and 0xFF
        val b1 = if (length >= 2) buffer.get(1).toInt() and 0xFF else 0
        val hasHeader = b0 in MIN_HEADER_LEN..MAX_HEADER_LEN && b0 <= length

        if (dumpPrinted < DUMP_LIMIT) {
            dumpPrinted++
            onLog(
                String.format(
                    Locale.US,
                    ">>> [MJPEG 分段] 第 %d 段 长度=%d 头 4 字节=%02x %02x %02x %02x ｜ 判为%s\n",
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
            dataStart = b0
            // 帧边界**不**用 FID 判定（v0.23.0 教训）：丢段/错序后 FID 翻转点不可靠，
            // MJPEG 帧边界以 SOI(FF D8)/EOI(FF D9) 扫描为准，FID 只留诊断用途。
            // 帧 PTS = 该帧第一段载荷头里的 PTS（缓冲为空时记下）
            if (framePts < 0 && dataStart >= 6 && (b1 and 0x04) != 0) {
                framePts = u32le(buffer, 2)
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

    private fun appendData(buffer: ByteBuffer, start: Int, length: Int) {
        var pos = start
        while (pos < length) {
            val n = minOf(length - pos, jpegCapacity - filled)
            if (n <= 0) break
            // 批量拷贝（v0.31.0）：旧版逐字节 get() 在 24MB/s 下每秒上千万次调用，
            // 与全量重扫一起构成吞吐瓶颈。改 bulk get 后恢复 buffer 的 position，
            // 遵守 TransferSink「不改动 position/limit」的契约（取流线程随后还会 clear）。
            val savedPos = buffer.position()
            buffer.position(pos)
            buffer.get(jpegBuf, filled, n)
            buffer.position(savedPos)
            filled += n
            pos += n
        }
        drainFrames()
    }

    /**
     * 流式出帧（v0.31.0 增量扫描重写）：缓冲里按 SOI → 其后第一个 EOI 严格切出**一帧完整 JPEG**。
     *
     * 【v0.30.0 吞吐根因教训】旧实现在**每段传输**都从头全量重扫累积缓冲找 SOI/EOI
     * （O(帧²)：1440p 一帧 ~500KB 分 32 段，累计重扫 8MB/帧）+ 逐字节拷贝，
     * 把取流线程钉死在 ~700μs/段 → 吞吐上限 22~24MB/s，4K 帧更大、同样封顶只能跑出几帧。
     * 现在扫描位置（[scanPos]）跨段持久、只扫新增字节，整体 O(字节数)。
     *
     * 【v0.23.0 教训（帧边界规则不变）】绝不能用"FID 翻转出帧 + 头扫 SOI/尾扫 EOI"——
     * 丢段/错序后会把两帧拼成巨帧。规则：SOI(FF D8) → 其后**第一个** EOI(FF D9)；
     * 没等到 EOI 就来下一个 SOI = 当前帧坏，跳到下一帧重来；缓冲顶满无 EOI 整块丢弃。
     */
    private fun drainFrames() {
        while (true) {
            var i = scanPos
            var shifted = false
            while (i + 1 < filled) {
                if ((jpegBuf[i].toInt() and 0xFF) != 0xFF) {
                    i++
                    continue
                }
                val lo = jpegBuf[i + 1].toInt() and 0xFF
                if (lo == 0xD8) {
                    if (inFrame && i > 0) {
                        // 还没等到 EOI 就来了下一帧 SOI：当前帧已损坏，从下一帧重来
                        partialDrops++
                        if (partialDrops <= PARTIAL_LOG_LIMIT) {
                            onLog(">>> [MJPEG] 丢弃不完整 JPEG（未见 EOI 就来了下一帧 SOI，累计 $i 字节）\n")
                        }
                    }
                    // SOI 之前的垃圾（半截残帧/乱序字节）直接丢：consume 使 SOI 顶到 0 号位
                    if (i > 0) consume(i)
                    inFrame = true
                    scanPos = 2
                    shifted = true
                    break
                }
                if (lo == 0xD9 && inFrame) {
                    // 提交 [0, i+2) 一帧完整 JPEG
                    val jpegLen = i + 2
                    val jpeg = jpegBuf.copyOfRange(0, jpegLen)
                    consume(jpegLen)
                    inFrame = false
                    scanPos = 0
                    shifted = true
                    commitFrame(jpeg, "SOI/EOI 扫描")
                    break
                }
                i++
            }
            if (shifted) continue

            // 扫到当前缓冲末尾：记下续扫位置。末字节悬空 0xFF 可能是跨段标记的前半，留下次配对
            val danglingFf = filled > 0 && (jpegBuf[filled - 1].toInt() and 0xFF) == 0xFF
            scanPos = if (danglingFf) filled - 1 else filled

            if (!inFrame) {
                // 帧外垃圾没有价值：只留末尾悬空 0xFF（可能是跨段 SOI 前半）
                val keep = if (danglingFf) 1 else 0
                if (keep == 1) jpegBuf[0] = 0xFF.toByte()
                filled = keep
                scanPos = 0
                return
            }
            if (filled >= jpegCapacity) {
                // 在帧中但缓冲顶满仍没有完整帧：整块丢弃
                partialDrops++
                if (partialDrops <= PARTIAL_LOG_LIMIT) {
                    onLog(">>> [MJPEG] 丢弃不完整 JPEG（缓冲满 $filled 字节仍无 EOI）\n")
                }
                filled = 0
                inFrame = false
                scanPos = 0
                framePts = -1L
            }
            return
        }
    }

    /** 把缓冲前 n 个字节消费掉（后面的字节前移） */
    private fun consume(n: Int) {
        val rest = filled - n
        if (rest > 0) {
            System.arraycopy(jpegBuf, n, jpegBuf, 0, rest)
        }
        filled = if (rest > 0) rest else 0
    }

    /** 提交一帧完整 JPEG 给解码线程 */
    private fun commitFrame(jpeg: ByteArray, reason: String) {
        val pts = framePts
        framePts = -1L

        // 采集 → 装配延迟：JPEG 边界确认后再算，锚点与 YUY2 装配器一致
        updateCaptureLatency(pts)
        if (frames == 0L && decodeFailures == 0L && droppedForDecode == 0L) {
            onLog(
                String.format(
                    Locale.US,
                    ">>> [MJPEG] 首帧已装配：JPEG %d 字节（%s）\n",
                    jpeg.size, reason
                )
            )
        }
        synchronized(decodeLock) {
            while (pendingQueue.size >= MAX_PENDING_FRAMES) {
                pendingQueue.removeFirst()
                droppedForDecode++
            }
            pendingQueue.addLast(PendingFrame(nextSeq++, jpeg))
            decodeLock.notifyAll()
        }
        progressLogged++
        if (progressLogged % STATS_EVERY_FRAMES == 0L) {
            onLog(
                String.format(
                    Locale.US,
                    ">>> [MJPEG] 已装配 %d 帧 ｜ 解码失败 %d ｜ 丢弃积压 %d ｜ 平均线上 %.0f B/帧\n",
                    progressLogged, decodeFailures, droppedForDecode,
                    if (progressLogged > 0) totalReceived.toDouble() / progressLogged else 0.0
                )
            )
        }
    }

    // ==================== 解码（线程池，每线程私有位图与输出缓冲） ====================

    private fun decodeLoop() {
        // 每线程私有：inBitmap 复用位图（避免每帧 33MB 的分配风暴）+ RGBA 直接缓冲
        var reusableBitmap: Bitmap? = null
        val rgbaBuf: ByteBuffer = ByteBuffer.allocateDirect(rgbaBytes)
        while (decoding) {
            // 取待解码帧：synchronized 块返回取出的帧（null = 已停止且没帧了）
            val frame: PendingFrame? = synchronized(decodeLock) {
                while (decoding && pendingQueue.isEmpty()) {
                    try {
                        decodeLock.wait(200)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (pendingQueue.isEmpty()) null else pendingQueue.removeFirst()
            }
            if (frame == null) continue
            try {
                reusableBitmap = decodeOne(frame.jpeg, frame.seq, reusableBitmap, rgbaBuf)
            } catch (t: Throwable) {
                decodeFailures++
                if (decodeFailures <= PARTIAL_LOG_LIMIT) {
                    onLog("!! [MJPEG] 解码异常: $t\n")
                }
            }
        }
    }

    /** 解码一帧并按序号把关上屏；返回可复用位图（失败路径原样返回传入的） */
    private fun decodeOne(
        jpeg: ByteArray,
        seq: Long,
        reusableBitmap: Bitmap?,
        rgbaBuf: ByteBuffer
    ): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        reusableBitmap?.let { opts.inBitmap = it }
        var bmp: Bitmap? = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (t: Throwable) {
            // inBitmap 复用被拒（尺寸/配置不符）→ 退回全新解码，下次再复用新位图
            null
        }
        if (bmp == null) {
            bmp = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                })
            } catch (t: Throwable) {
                null
            }
        }
        val b = bmp
        if (b == null) {
            decodeFailures++
            if (decodeFailures <= PARTIAL_LOG_LIMIT) {
                onLog(String.format(Locale.US, "!! [MJPEG] JPEG 解码失败（%d 字节）\n", jpeg.size))
            }
            return reusableBitmap
        }
        if (b.width != width || b.height != height) {
            decodeFailures++
            if (decodeFailures <= PARTIAL_LOG_LIMIT) {
                onLog(
                    String.format(
                        Locale.US,
                        "!! [MJPEG] 解码出 %dx%d，与档位 %dx%d 不符，本帧丢弃\n",
                        b.width, b.height, width, height
                    )
                )
            }
            return b
        }

        // 像素先拷进本线程私有缓冲（锁外，33MB 拷贝不挡别人），进锁只做序号把关 + 同步消费
        rgbaBuf.clear()
        b.copyPixelsToBuffer(rgbaBuf)
        rgbaBuf.position(0)
        rgbaBuf.limit(rgbaBytes)
        synchronized(outputLock) {
            if (seq <= lastOutputSeq) {
                // 并行解码的乱序旧帧：比它新的帧已上屏，本帧丢弃（画面绝不回跳）
                droppedForDecode++
                return b
            }
            lastOutputSeq = seq
            updateQuickStats(rgbaBuf)
            frames++
            onFrame(rgbaBuf)
        }
        return b
    }

    // ==================== 统计 ====================

    /**
     * 解码后像素的亮度快速抽样（步长 [QUICK_STRIDE] 字节，落在整像素上）。
     * 亮度近似 Y ≈ 0.299R + 0.587G + 0.114B，口径与 YUY2 装配器的 Y 统计一致，
     * 上层的黑场判定（Y 均值 ≤ 18 且中部行极差 ≤ 2）可以原样复用。
     */
    private fun updateQuickStats(buf: ByteBuffer) {
        var minY = 255
        var maxY = 0
        var sum = 0L
        var n = 0L
        var i = 0
        while (i < rgbaBytes) {
            val y = luminance(buf, i)
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sum += y
            n++
            i += QUICK_STRIDE
        }
        yAvg = if (n > 0) sum.toDouble() / n else 0.0
        yRange = maxY - minY

        val rowStart = rowRgbaBytes * (height / 2)
        val rowEnd = minOf(rowStart + rowRgbaBytes, rgbaBytes)
        var rMin = 255
        var rMax = 0
        var j = rowStart
        while (j < rowEnd) {
            val y = luminance(buf, j)
            if (y < rMin) rMin = y
            if (y > rMax) rMax = y
            j += QUICK_STRIDE
        }
        midRowRange = rMax - rMin
    }

    private fun luminance(buf: ByteBuffer, offset: Int): Int {
        val r = buf.get(offset).toInt() and 0xFF
        val g = buf.get(offset + 1).toInt() and 0xFF
        val b = buf.get(offset + 2).toInt() and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** 采集 → 装配延迟（与 YUY2 装配器同一套 PTS/SCR 换算） */
    private fun updateCaptureLatency(pts: Long) {
        if (pts < 0 || scrHostNs == 0L) return
        val deviceNow = scrTick + (System.nanoTime() - scrHostNs) * TICKS_PER_NS
        var diff = deviceNow - pts
        diff = ((diff % TWO_POW_32) + TWO_POW_32) % TWO_POW_32
        if (diff > TWO_POW_31) diff -= TWO_POW_32
        val ms = diff / TICKS_PER_MS
        if (ms in 0.0..5000.0) captureLatencyMs = ms
    }

    override fun logSummary() {
        val c = counters
        onLog("")
        onLog("---- MJPEG 装配/解码小结 ----\n")
        onLog(String.format(Locale.US, "解码完整帧数    : %d（%dx%d RGBA）\n", c.frames, width, height))
        onLog(String.format(Locale.US, "带头的传输段数  : %d\n", c.headerTransfers))
        onLog(String.format(Locale.US, "无头的传输段数  : %d\n", c.headerlessTransfers))
        onLog(String.format(Locale.US, "丢弃的半帧数    : %d（SOI/EOI 不完整）\n", c.partialDrops))
        onLog(String.format(Locale.US, "解码失败帧数    : %d\n", decodeFailures))
        onLog(String.format(Locale.US, "丢弃的积压帧    : %d（解码跟不上，丢最旧保最新）\n", droppedForDecode))
        onLog(String.format(Locale.US, "平均每帧段数    : %.2f 段\n", c.transfersPerFrame))
        onLog(String.format(Locale.US, "平均每帧线上字节: %.1f（含 12B/段 载荷头开销）\n", c.wireBytesPerFrame))
        if (captureLatencyMs >= 0) {
            onLog(String.format(Locale.US, "采集→装配延迟    : %.1f ms（PTS/SCR 换算）\n", captureLatencyMs))
        }
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

        /** 前几段传输打详细分段信息 */
        private const val DUMP_LIMIT = 12

        /** 异常日志（丢半帧/解码失败）最多打多少条，防止刷屏 */
        private const val PARTIAL_LOG_LIMIT = 16

        /** 并行软解线程数（v1.1.2）：1080p 单线程约 10-15ms/帧卡 120fps 边缘，3 线程稳够；
         *  4K 约 40-60ms/帧，3 线程吞吐约 50-70fps，跑不满 4K60 时按"丢最旧"自然降级 */
        private const val DECODE_THREADS = 3

        /** 待解码队列上限（线程数 + 1 缓冲；满了丢最旧） */
        private const val MAX_PENDING_FRAMES = DECODE_THREADS + 1

        /** 进度抽样日志的间隔（帧） */
        private const val STATS_EVERY_FRAMES = 120L

        /** 亮度抽样步长（字节，必须是 4 的倍数，落在整像素上） */
        private const val QUICK_STRIDE = 2048

        /** 设备时钟 125MHz：0.125 刻度/ns */
        private const val TICKS_PER_NS = 0.125

        /** 125 刻度/µs = 125000 刻度/ms */
        private const val TICKS_PER_MS = 125_000.0

        private const val TWO_POW_32 = 4294967296.0
        private const val TWO_POW_31 = 2147483648.0
    }
}
