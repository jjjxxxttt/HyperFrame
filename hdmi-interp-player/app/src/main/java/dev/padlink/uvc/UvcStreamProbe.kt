package dev.padlink.uvc

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import dev.padlink.uvc.UvcConstants.BULK_ENDPOINT_ADDRESS
import dev.padlink.uvc.UvcConstants.VC_CLOCK_FREQUENCY
import dev.padlink.uvc.UvcConstants.VS_COMMIT_CONTROL
import dev.padlink.uvc.UvcConstants.VS_INTERFACE_NUMBER
import dev.padlink.uvc.UvcConstants.VS_PROBE_CONTROL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeoutException

/**
 * UVC 取流原型：设备协商（probe/commit）+ bulk 异步取流测速，与界面完全无关。
 *
 * 目标只有一个：回答「纯 Kotlin 的 USB API 能不能稳定跑到 248 MB/s（1080p60 YUY2）」。
 *
 * **为什么必须用 UsbRequest 异步并发排队，而不是同步 bulkTransfer**：
 * Android 的 usbfs 通道对单次传输有 16384 字节的内核上限（MAX_USBFS_BUFFER_SIZE，
 * 见 AOSP system/core/libusbhost 与 UsbDeviceConnection.bulkTransfer 的长度校验）。
 * 1080p60 YUY2 一帧 1920*1080*2 = 4147200 字节，一帧就要 254 次 16KB 传输，
 * 每秒需要约 15200 次传输；同步单发（一次一个 URB、发完再发下一个）根本堆不出这个吞吐。
 * 只有同时把大量小 URB 排进内核队列、由内核流水线化，才能逼近设备带宽上限。
 *
 * 所有回调都可能在取流线程上触发，调用方自行 runOnUiThread。
 */
class UvcStreamProbe(
    private val context: Context,
    private val device: UsbDevice,
    private val listener: Listener,
    /** 本次协商/取流的目标档位（v0.18.0 起不再硬编码 1080p60） */
    private val profile: StreamProfile = StreamProfile.DEFAULT_1080P60
) {

    /** 一次统计快照 */
    data class Stats(
        val elapsedMs: Long,
        val totalBytes: Long,
        /** 最近一个统计窗口的瞬时速率（十进制 MB/s） */
        val instantMBps: Double,
        /** 整个会话的平均速率（十进制 MB/s） */
        val avgMBps: Double,
        /** 按字节数换算的等效帧率（总字节 / 每帧字节数 / 秒） */
        val equivalentFps: Double,
        /** 本次档位的一帧字节数 */
        val frameBytes: Int,
        /** 本次档位的理论带宽（字节/秒） */
        val targetBytesPerSec: Long,
        /** FID 翻转次数（近似，见类注释与日志里的说明） */
        val fidFlips: Int,
        /** 由 FID 翻转次数换算的累计实际帧率 */
        val fidFps: Double,
        /** 最近一个统计窗口由 FID 换算的瞬时帧率 */
        val instantFidFps: Double,
        val completed: Int,
        val queueFailures: Int,
        val requestErrors: Int
    ) {
        /** 相对本档理论带宽的达成率 */
        val achievedRatio: Double
            get() = if (targetBytesPerSec <= 0) 0.0 else avgMBps / (targetBytesPerSec / 1e6)
    }

    interface Listener {
        /** 日志行（自动补换行），可能在任意线程回调 */
        fun onLog(line: String)

        /**
         * 周期统计（约每秒一次），可能在任意线程回调。
         * 取流停摆（没有任何传输完成）时也照常回调 —— 这是看门狗判"取流停滞"的心跳来源。
         */
        fun onStats(stats: Stats)

        /** 本次会话结束，description 为结束原因 */
        fun onFinished(description: String)
    }

    /**
     * 取流数据的旁路消费者（实时预览用）。
     *
     * 每个 bulk 传输完成时回调一次，[buffer] 的 position 停在本次实际收到的字节数上。
     * 回调里**不能改动 buffer 的 position/limit**（框架随后要 clear() 复用），只允许
     * 用绝对下标或 duplicate() 读。回调在取流线程上执行，必须尽快返回。
     */
    fun interface TransferSink {
        fun onTransfer(buffer: ByteBuffer, length: Int)
    }

    /**
     * 旁路取流数据。为 null（默认）时只测速、把数据丢掉；设为装配器即进入预览模式。
     * 只能在 [start] 之前设置。
     */
    var transferSink: TransferSink? = null

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var connectionRef: UsbDeviceConnection? = null

    /** 已创建的 request 池，stop() 时用来取消，加速阻塞中的 requestWait 返回 */
    private val pooled = ArrayList<UsbRequest>()

    private val stateLock = Any()

    /** 连接是否已经关闭过（避免重复 close，native 侧 double close 会踩内存） */
    private var connectionClosed = false

    /**
     * 最近从载荷头解析到的 PTS（设备时钟 125MHz，32 位环回；-1 = 还没读到）。
     *
     * 与"帧计数"互补：HDMI 源换分辨率后，有些采集卡固件会卡住输出但 USB 流不停
     * （帧/FID 照涨、画面却冻在最后一帧）—— PTS 不再推进就是这种"输出冻结"的铁证。
     */
    @Volatile
    var lastPts: Long = -1L
        private set

    /** PTS 值最后一次变化的主机时刻（0 = 还没读到 PTS，冻结检测自动跳过） */
    @Volatile
    var lastPtsChangeNs: Long = 0L
        private set

    val isRunning: Boolean
        get() = running

    /**
     * 启动一次会话：[poolSize] 为 request 池大小，[durationMs] 为自动停止时长（<= 0 表示不限时，靠 [stop] 停）。
     *
     * [hardReset] = 硬复位（v0.24.0）：openDevice 之后先重新 SET_CONFIGURATION 再 claim，
     * 等效"重新插拔采集卡"。实测（用户 v0.23.0 反馈）HDMI 源切换分辨率后采集卡固件会卡死
     * （bulk 端点整批排队失败），close/重开救不回来，**拔插一次就好** —— 拔插的实质就是
     * 设备重新枚举 + SET_CONFIGURATION，固件和内核驱动（含 snd-usb-audio）全部重建。
     */
    fun start(poolSize: Int, durationMs: Long, hardReset: Boolean = false) {
        if (running) {
            log(">>> 已有取流会话在进行中，忽略本次启动")
            return
        }
        running = true
        val t = Thread({ runSession(poolSize, durationMs, hardReset) }, "uvc-stream-probe")
        synchronized(stateLock) { worker = t }
        t.start()
    }

    /**
     * 停止取流。
     *
     * 退出机制（为什么这样能退出）：
     * 1. 主循环用的是 `UsbDeviceConnection.requestWait(timeout)`（API 26+，本工程 minSdk 29），
     *    它最长阻塞 200ms 就抛 TimeoutException，主循环捕获它并把本轮当成"没拿到数据"，
     *    随即重新检查 [running] 标志 —— 这是保证能退出的主要手段，不依赖任何强制中断；
     * 2. 顺带对所有 request 调 `cancel()`，让内核里尚未完成的 URB 立刻回流，加速本次返回；
     * 3. 兜底：万一某个 ROM 的 usbfs 实现没按超时返回（曾见过 requestWait 长阻塞的机型），
     *    1.5 秒后直接 `connection.close()`。close() 会关掉 usbfs 的 fd，阻塞在 poll() 上的
     *    usb_request_wait 会立即带错误返回（libusbhost 里 poll 报错即返回 NULL），
     *    线程从而看到 running=false 退出，不会留下卡死的线程。
     */
    fun stop() {
        if (!running) return
        running = false
        log(">>> 收到停止请求，正在退出取流循环…")
        val snapshot = synchronized(stateLock) { ArrayList(pooled) }
        for (r in snapshot) {
            try {
                r.cancel()
            } catch (t: Throwable) {
                // 已完成/已取消的 request 再取消会失败，忽略
            }
        }
        val w = worker ?: return
        Thread {
            try {
                w.join(STOP_GRACE_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (w.isAlive) {
                log(">>> 停止超时：强制关闭 USB 连接以中断 requestWait")
                closeConnection()
            }
        }.start()
    }

    /** 只关一次连接：native 侧 UsbDeviceConnection.close() 不是幂等的 */
    private fun closeConnection() {
        val c = connectionRef ?: return
        synchronized(stateLock) {
            if (connectionClosed) return
            connectionClosed = true
        }
        try {
            c.close()
        } catch (t: Throwable) {
            // 忽略
        }
    }

    // ==================== 会话主流程 ====================

    private fun runSession(poolSize: Int, durationMs: Long, hardReset: Boolean) {
        var reason = "结束"
        var connection: UsbDeviceConnection? = null
        var intf: UsbInterface? = null
        // VC（接口 0）也一起 claim，退出时和 VS 一起 release
        var vcIntfRef: UsbInterface? = null
        try {
            val usbManager = try {
                context.getSystemService(Context.USB_SERVICE) as? UsbManager
            } catch (t: Throwable) {
                null
            }
            if (usbManager == null) {
                reason = "UsbManager 不可用"
                return
            }

            log("")
            log("==== UVC 取流原型 ====")
            log(
                String.format(
                    Locale.US,
                    "设备: %s (%04x:%04x)  接口数=%d  已授权=%s",
                    device.deviceName,
                    device.vendorId,
                    device.productId,
                    device.interfaceCount,
                    try {
                        usbManager.hasPermission(device)
                    } catch (t: Throwable) {
                        false
                    }
                )
            )
            log(
                String.format(
                    Locale.US,
                    "目标: 格式索引 %d (%s) / 帧索引 %d (%dx%d) / dwFrameInterval=%d (%.2ffps) / 每帧 %d 字节",
                    profile.formatIndex,
                    profile.fourcc,
                    profile.frameIndex,
                    profile.width,
                    profile.height,
                    profile.interval,
                    profile.fps,
                    profile.frameBytes
                )
            )
            log(
                String.format(
                    Locale.US,
                    "目标带宽%s: %d B/s (%.1f MB/s 十进制, %.1f Mbps)",
                    if (profile.isMjpeg) "（MJPEG 压缩流，按 dwMaxVideoFrameBufferSize 上限折算，实际远低于此）" else "",
                    profile.targetBytesPerSec,
                    profile.targetBytesPerSec / 1e6,
                    profile.targetBytesPerSec * 8 / 1e6
                )
            )

            // ---- 步骤 1: openDevice ----
            connection = try {
                usbManager.openDevice(device)
            } catch (t: Throwable) {
                log("[步骤1] openDevice 抛异常: $t")
                null
            }
            if (connection == null) {
                log("[步骤1] openDevice 返回 null —— 设备未授权，或已被 Camera2 / 其它进程独占")
                reason = "步骤1 失败：openDevice 返回 null"
                return
            }
            synchronized(stateLock) { connectionClosed = false }
            connectionRef = connection
            log("[步骤1] openDevice OK")

            // ---- 硬复位（v0.24.0，等效"重新插拔采集卡"）----
            // 拔插的实质 = 设备重新枚举后由主机重新 SET_CONFIGURATION：固件按"刚上电"重建
            // 采集管线，内核把所有接口驱动（含 snd-usb-audio）拆掉重绑。HDMI 源切换分辨率后
            // 采集卡固件卡死（bulk 端点整批排队失败）时，close/重开救不回来，只有这一步能救
            if (hardReset) {
                log(">>> [硬复位] 重新 SET_CONFIGURATION（等效重新插拔）：固件采集管线 + 内核驱动（含 UAC 音频）全部重建")
                val cfg = try {
                    device.getConfiguration(0)
                } catch (t: Throwable) {
                    null
                }
                val resetOk = try {
                    if (cfg != null) connection.setConfiguration(cfg) else false
                } catch (t: Throwable) {
                    log("!! [硬复位] setConfiguration 异常: $t")
                    false
                }
                log(">>> [硬复位] setConfiguration -> $resetOk，等待设备重建…")
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            // ---- 步骤 2: Interface 1 + bulk IN 0x83 ----
            logInterfaces()
            intf = findInterface(device, VS_INTERFACE_NUMBER)
            if (intf == null) {
                log("[步骤2] 未找到 Interface $VS_INTERFACE_NUMBER")
                reason = "步骤2 失败：未找到 VS 接口"
                return
            }
            log(
                String.format(
                    Locale.US,
                    "[步骤2] Interface %d: alt=%d class=0x%02x sub=0x%02x proto=0x%02x endpoints=%d",
                    intf.id,
                    intf.alternateSetting,
                    intf.interfaceClass,
                    intf.interfaceSubclass,
                    intf.interfaceProtocol,
                    intf.endpointCount
                )
            )
            val endpoint = findBulkInEndpoint(intf)
            if (endpoint == null) {
                log("[步骤2] 未找到 addr=0x%02x 的 BULK IN 端点".format(Locale.US, BULK_ENDPOINT_ADDRESS))
                reason = "步骤2 失败：未找到 bulk IN 端点"
                return
            }

            if (!running) {
                reason = "协商前被停止"
                return
            }

            val vcIntf = findInterface(device, VC_INTERFACE_NUMBER)
            vcIntfRef = vcIntf

            // ---- 步骤 3: claim 之前先跑一遍控制传输诊断 ----
            // 目的是把「UVC 参数写错」和「控制通道根本不通」分开：标准请求（GET_DESCRIPTOR 等）
            // 与 UVC 语义完全无关，如果连它们都失败，说明问题在平台/驱动层，而不是我们的参数。
            val preDiag = diagnose(
                connection, "① claim 之前",
                standard = true, full = false,
                vsIntf = intf, vcIntf = vcIntf, probeStruct = null
            )

            if (!running) {
                reason = "协商前被停止"
                return
            }

            // ---- 步骤 4: 先把接口所有权拿过来（顺序很关键）----
            // Linux uvcvideo / libuvc 都是先 claim 再 probe/commit；而且 claimInterface(force=true)
            // 走的是 USBDEVFS_DISCONNECT_CLAIM，会把已经绑定的内核驱动（uvcvideo）踢掉。
            // 若此前是内核驱动占着设备，class 控制传输就可能被拒 —— 这就是把 claim 提前的原因。
            val claimedVs = claimInterface(connection, intf)
            val claimedVc = if (vcIntf != null) claimInterface(connection, vcIntf) else false
            log(
                String.format(
                    Locale.US,
                    "[步骤4] claimInterface(force=true): VS(%d) -> %s ｜ VC(%s) -> %s",
                    intf.id,
                    claimedVs,
                    vcIntf?.id ?: "?",
                    claimedVc
                )
            )

            if (!running) {
                reason = "协商前被停止"
                return
            }

            // ---- 步骤 5: claim 之后再做一次同样的诊断（含 SET_CUR 的 26 / 34 两种长度）----
            val probe = buildProbeCommit(maxPayload = 0L)
            val postDiag = diagnose(
                connection, "② claim 之后",
                standard = false, full = true,
                vsIntf = intf, vcIntf = vcIntf, probeStruct = probe
            )

            // ---- 步骤 6: 定下 probe/commit 结构长度 ----
            // 本机 bcdUVC=0x0100（UVC 1.00），probe/commit 结构就该是 26 字节，34 字节是 1.1 才有的。
            // 诊断阶段两种长度都已经真发过一次，这里优先采用"确实成功过"的那个。
            var effectiveLen = PROBE_COMMIT_LENGTH_MIN
            when {
                postDiag.setCur26 >= 0 -> effectiveLen = PROBE_COMMIT_LENGTH_MIN
                postDiag.setCur34 >= 0 -> effectiveLen = PROBE_COMMIT_LENGTH
                postDiag.deviceLen in PROBE_COMMIT_LENGTH_MIN..PROBE_COMMIT_LENGTH ->
                    effectiveLen = postDiag.deviceLen
                preDiag.deviceLen in PROBE_COMMIT_LENGTH_MIN..PROBE_COMMIT_LENGTH ->
                    effectiveLen = preDiag.deviceLen
            }
            log(
                String.format(
                    Locale.US,
                    "[步骤6] GET_LEN 报告的结构长度: claim 前 %d / claim 后 %d；本次发送 %d 字节",
                    preDiag.deviceLen,
                    postDiag.deviceLen,
                    effectiveLen
                )
            )

            // ---- 步骤 7: SET_CUR(VS_PROBE_CONTROL) ----
            var setRes = setCur(connection, VS_PROBE_CONTROL, probe, effectiveLen)
            log("[步骤7] SET_CUR VS_PROBE_CONTROL（发送 $effectiveLen 字节）返回 $setRes")
            if (setRes < 0) {
                // 长度猜错是最常见的失败原因：UVC 1.0 只认 26 字节结构，1.1 才认 34 字节，
                // 而有些设备 GET_LEN 本身不可靠。因此这里把另一个长度也试一遍，取能成功的那个。
                val altLen = if (effectiveLen == PROBE_COMMIT_LENGTH) {
                    PROBE_COMMIT_LENGTH_MIN
                } else {
                    PROBE_COMMIT_LENGTH
                }
                val altRes = setCur(connection, VS_PROBE_CONTROL, probe, altLen)
                log("!!!! SET_CUR 失败，改用 $altLen 字节重试，返回 $altRes")
                if (altRes >= 0) effectiveLen = altLen
                setRes = altRes
            }

            // ---- 步骤 8: GET_CUR(VS_PROBE_CONTROL) 读回设备实际接受的值 ----
            var devicePayload = 0L
            val resp = ByteArray(effectiveLen)
            val getRes = try {
                connection.controlTransfer(
                    UvcConstants.REQ_TYPE_GET_CUR,
                    UvcConstants.UVC_GET_CUR,
                    VS_PROBE_CONTROL shl 8,
                    VS_INTERFACE_NUMBER,
                    resp,
                    effectiveLen,
                    CONTROL_TIMEOUT_MS
                )
            } catch (t: Throwable) {
                log("[步骤5] GET_CUR 抛异常: $t")
                -1
            }
            if (getRes >= 0) {
                log("[步骤5] GET_CUR VS_PROBE_CONTROL 返回 $getRes 字节（请求 $effectiveLen）")
                // dwMaxVideoFrameSize 在偏移 18、dwMaxPayloadTransferSize 在偏移 22，
                // 这两个偏移在 26 字节的 UVC 1.0 结构里也存在，所以 26/34 都能解析。
                if (getRes >= 26) {
                    val frameBytes = u32le(resp, 18)
                    val payloadBytes = u32le(resp, 22)
                    devicePayload = payloadBytes
                    val gotFormat = u8(resp, 2)
                    val gotFrame = u8(resp, 3)
                    val gotInterval = u32le(resp, 4)
                    log(
                        String.format(
                            Locale.US,
                            "  回读: bFormatIndex=%d bFrameIndex=%d dwFrameInterval=%d",
                            gotFormat,
                            gotFrame,
                            gotInterval
                        )
                    )
                    if (gotFormat != profile.formatIndex || gotFrame != profile.frameIndex || gotInterval != profile.interval) {
                        log(
                            String.format(
                                Locale.US,
                                "  !! 设备实际接受的档位与请求不一致（请求 格式 %d / 帧 %d / 间隔 %d）—— 设备做了就近适配，实际帧率可能与档位标注不同",
                                profile.formatIndex,
                                profile.frameIndex,
                                profile.interval
                            )
                        )
                    }
                    log(
                        String.format(
                            Locale.US,
                            "  dwMaxVideoFrameSize=%d 字节 (%.2f MB)",
                            frameBytes,
                            frameBytes / 1024.0 / 1024.0
                        )
                    )
                    log(
                        String.format(
                            Locale.US,
                            "  dwMaxPayloadTransferSize=%d 字节 (%.2f KB) —— 决定单次传输该开多大",
                            payloadBytes,
                            payloadBytes / 1024.0
                        )
                    )
                    when {
                        payloadBytes <= 0L -> log("  !! 设备没给出单载荷上限，本原型固定按 $TRANSFER_BUFFER_SIZE 字节排队")
                        payloadBytes > TRANSFER_BUFFER_SIZE -> log("  !! 设备给出的单载荷上限 > $TRANSFER_BUFFER_SIZE 字节，而本原型每次只排 $TRANSFER_BUFFER_SIZE 字节，载荷会被拆到多次传输里")
                        else -> log("  -> 本原型单次排队 $TRANSFER_BUFFER_SIZE 字节，与设备上限匹配")
                    }
                } else {
                    log("  !! 返回长度不足 26 字节，无法解析 dwMaxVideoFrameSize / dwMaxPayloadTransferSize")
                }
            } else {
                log("[步骤5] GET_CUR 失败（返回 $getRes）")
            }

            // ---- 步骤 9: SET_CUR(VS_COMMIT_CONTROL) ----
            // 结构与 probe 一致；dwMaxPayloadTransferSize 用设备在 GET_CUR 里给出的值回填
            // （驱动/libuvc 的做法都是"probe 的响应即 commit 的内容"；设备没给值时才用 0）。
            val commitPayload = if (devicePayload > 0L) devicePayload else 0L
            val commit = buildProbeCommit(maxPayload = commitPayload)
            val commitRes = setCur(connection, VS_COMMIT_CONTROL, commit, effectiveLen)
            log("[步骤9] SET_CUR VS_COMMIT_CONTROL（$effectiveLen 字节，dwMaxPayloadTransferSize=$commitPayload）返回 $commitRes")
            if (commitRes < 0) {
                log("  !! COMMIT 被拒：设备不会切到 ${profile.label} ${profile.fourcc}，下面的取流数据只能反映设备当前的默认格式")
            }
            if (!claimedVs) {
                log("  !! 没拿到 VS 接口所有权，bulk 传输大概率失败（接口可能已被内核驱动或 Camera2 占用）")
            }

            if (!running) {
                reason = "取流前被停止"
                return
            }

            // ---- 步骤 10: 取流 ----
            reason = stream(connection, endpoint, poolSize, durationMs)
        } catch (t: Throwable) {
            log("!! 会话异常: $t")
            reason = "会话异常: $t"
        } finally {
            val c = connection
            val i = intf
            val v = vcIntfRef
            if (c != null) {
                for (target in listOfNotNull(i, v)) {
                    try {
                        c.releaseInterface(target)
                    } catch (t: Throwable) {
                        // 忽略
                    }
                }
            }
            closeConnection()
            connectionRef = null
            running = false
            synchronized(stateLock) { worker = null }
            listener.onFinished(reason)
        }
    }

    // ==================== 取流 + 测速 ====================

    private fun stream(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        poolSize: Int,
        durationMs: Long
    ): String {
        val requests = ArrayList<UsbRequest>(poolSize)
        val buffers = HashMap<UsbRequest, ByteBuffer>(poolSize)
        // 预览旁路：为 null 表示只测速。回调出错就摘掉它，避免每段传输都抛一次
        var sink = transferSink
        for (i in 0 until poolSize) {
            val req = UsbRequest()
            val ok = try {
                req.initialize(connection, endpoint)
            } catch (t: Throwable) {
                log("!! 第 ${i + 1} 个 UsbRequest.initialize 抛异常: $t")
                false
            }
            if (!ok) {
                log("!! 第 ${i + 1} 个 UsbRequest.initialize 失败，池大小缩减为 ${requests.size}")
                break
            }
            requests.add(req)
            // 必须是 direct buffer：UsbRequest 直接把它交给内核做 DMA，堆上数组会被拒
            buffers[req] = ByteBuffer.allocateDirect(TRANSFER_BUFFER_SIZE)
        }
        if (requests.isEmpty()) {
            log("!! request 池为空，无法取流")
            return "request 池创建失败"
        }
        synchronized(stateLock) {
            pooled.clear()
            pooled.addAll(requests)
        }
        if (!running) return "取流启动前被停止"

        var queued = 0
        for (req in requests) {
            val buf = buffers[req]
            if (buf != null && queue(req, buf)) queued++
        }
        log(
            String.format(
                Locale.US,
                "[取流] request 池 %d 个 x %d 字节（direct buffer，合计 %d KB），首次排队成功 %d 个",
                requests.size,
                TRANSFER_BUFFER_SIZE,
                requests.size * TRANSFER_BUFFER_SIZE / 1024,
                queued
            )
        )
        if (queued <= 0) {
            log("!! 首次排队全部失败，无法取流")
            return "request 初始排队全部失败"
        }
        var inFlight = queued

        var totalBytes = 0L
        var windowBytes = 0L
        var completed = 0
        var queueFailures = 0
        var requestErrors = 0
        var fidFlips = 0
        var windowFidFlips = 0
        var lastFid = -1
        var firstDumpDone = false
        var lastStats: Stats? = null

        val startNs = System.nanoTime()
        var lastSampleNs = startNs
        var reason = if (durationMs > 0) "跑满 ${durationMs / 1000} 秒，自动停止" else "已停止"

        // 统计节拍（约每秒一次）：必须独立于"有没有传输完成"。
        // v0.19.0 实测教训：进游戏 HDMI 源换分辨率后采集卡 bulk 端点整个停摆，requestWait
        // 只剩 200ms 空超时、没有任何传输完成 —— 旧代码把统计检查挂在"传输完成"路径上，
        // 于是 onStats 跟着停发，看门狗（挂在 onStats 节拍上）失去心跳，"取流停滞"永远检不出来，
        // 画面冻结到天荒地老。停摆时更要持续发统计（瞬时速率 0），下游才判得出停滞。
        val emitStatsIfDue = {
            val sampleNs = System.nanoTime()
            if (sampleNs - lastSampleNs >= SAMPLE_INTERVAL_NS) {
                val stats = snapshot(
                    startNs, sampleNs, sampleNs - lastSampleNs, totalBytes, windowBytes,
                    fidFlips, windowFidFlips, completed, queueFailures, requestErrors
                )
                lastStats = stats
                windowBytes = 0L
                windowFidFlips = 0
                lastSampleNs = sampleNs
                // MJPEG 是压缩流：frameBytes 只是帧上限，「达成率/等效帧率(按字节)」没有意义，
                // 这里改以 FID 帧率为主口径（FID 帧率 ≈ 真实出帧率）
                log(
                    if (profile.isMjpeg) {
                        String.format(
                            Locale.US,
                            "[t=%.1fs] 瞬时 %.1f MB/s ｜ 平均 %.1f MB/s ｜ FID %.2ffps (累计 %.2ffps) ｜ 收割 %d ｜ queue失败 %d ｜ 错误 %d",
                            stats.elapsedMs / 1000.0,
                            stats.instantMBps,
                            stats.avgMBps,
                            stats.instantFidFps,
                            stats.fidFps,
                            stats.completed,
                            stats.queueFailures,
                            stats.requestErrors
                        )
                    } else {
                        String.format(
                            Locale.US,
                            "[t=%.1fs] 瞬时 %.1f MB/s ｜ 平均 %.1f MB/s (达成 %.1f%%) ｜ 等效帧率 %.2ffps ｜ FID %.2ffps (累计 %.2ffps) ｜ 收割 %d ｜ queue失败 %d ｜ 错误 %d",
                            stats.elapsedMs / 1000.0,
                            stats.instantMBps,
                            stats.avgMBps,
                            stats.achievedRatio * 100,
                            stats.equivalentFps,
                            stats.instantFidFps,
                            stats.fidFps,
                            stats.completed,
                            stats.queueFailures,
                            stats.requestErrors
                        )
                    }
                )
                listener.onStats(stats)
            }
        }

        while (running) {
            val nowNs = System.nanoTime()
            if (durationMs > 0 && nowNs - startNs >= durationMs * 1_000_000L) break

            val done = try {
                connection.requestWait(REQUEST_WAIT_TIMEOUT_MS)
            } catch (t: TimeoutException) {
                // 正常超时（见 UsbDeviceConnection.requestWait(long) 的 throws 声明）：
                // 回到循环顶部重新检查 running 标志，这也是本循环能可靠退出的原因
                null
            } catch (t: Throwable) {
                log("!! requestWait 异常: $t")
                reason = "requestWait 异常: $t"
                break
            }
            // null = 本次等待超时，或连接已被兜底逻辑关闭。
            // 超时路径也要走统计节拍：取流停摆时这是唯一的"心跳"来源
            if (done == null) {
                emitStatsIfDue()
                continue
            }

            val buf = buffers[done]
            if (buf == null) {
                // 不在本会话池里的 request（理论上不会发生），丢弃并计数
                completed++
                queueFailures++
                continue
            }
            // 取本次实际传输的字节数：
            // UsbRequest.getActualLength() 是 @hide（API 35 的 android.jar 里没有该方法），
            // 公开 API 只能靠 queue(ByteBuffer) 的新语义 —— 传输完成后框架把 buffer.position
            // 前进"实际传输字节数"（AOSP UsbRequest.queue(ByteBuffer) javadoc：
            // "the position of the buffer is incremented by the number of bytes sent/received"，
            // dequeue() 里是 mBuffer.position(mBuffer.position() + bytesTransferred)）。
            // 传输失败时 position 不变；因为我们每次排队前都 clear()，此时 position 就是 0，
            // 所以 len<=0 记为一次错误。必须在 clear() 之前读，且每个 request 独占一个 buffer。
            val len = try {
                buf.position()
            } catch (t: Throwable) {
                -1
            }
            completed++
            if (len > 0) {
                totalBytes += len
                windowBytes += len
            } else {
                requestErrors++
            }

            // 旁路给预览用：必须在 buf.clear() 之前、且不能改动它的 position/limit
            if (len > 0 && sink != null) {
                try {
                    sink.onTransfer(buf, len)
                } catch (t: Throwable) {
                    log("!! 预览数据回调异常，已停止向渲染器供帧: $t")
                    sink = null
                }
            }

            if (len >= 2) {
                // 首个传输的头部 16 字节：用来判断设备当前到底在发什么格式
                if (!firstDumpDone) {
                    firstDumpDone = true
                    val n = minOf(len, 16)
                    val hex = StringBuilder()
                    for (i in 0 until n) {
                        hex.append(String.format(Locale.US, "%02x ", buf.get(i).toInt() and 0xFF))
                    }
                    log(">>> 首包前 $n 字节: $hex")
                    log(">>> （UVC 载荷头 = bHeaderLength + bBitmap；MJPEG/JPEG 帧数据以 ff d8 开头）")
                }
                // UVC bulk 载荷头：bHeaderLength(1) + bBitmap(1)，bitmap bit0 = FID。
                // 近似判定：把传输首字节落在 2..12（合法头长度范围）的传输当成"载荷起始"。
                // 若载荷被 16384 字节边界切开，或设备把多个载荷塞进一次传输，这个统计会偏离。
                val headerLen = buf.get(0).toInt() and 0xFF
                if (headerLen in 2..12) {
                    val bm = (buf.get(1).toInt() and 0xFF)
                    val fid = bm and 0x01
                    if (lastFid >= 0 && fid != lastFid) {
                        fidFlips++
                        windowFidFlips++
                    }
                    lastFid = fid
                    // PTS（bmHeaderInfo bit2，载荷头 ≥ 6 字节）："输出冻结"检测的依据 ——
                    // 画面冻在最后一帧但 USB 流不停的故障里，帧计数照涨、只有 PTS 停滞
                    if ((bm and 0x04) != 0 && headerLen >= 6 && len >= 6) {
                        val pts = u32leBuf(buf, 2)
                        if (pts != lastPts) {
                            lastPts = pts
                            lastPtsChangeNs = System.nanoTime()
                        }
                    }
                }
            }
            // 复位读写位置后重新排队（position 不归零，下次排队就发不出/收不满数据）。
            // queue() 失败不代表这一路没救了：HDMI 换分辨率瞬间 usbfs 队列会整体出错，
            // 把该 request 重建后重排往往能救回来；重建也失败才丢弃这一路。
            buf.clear()
            if (!requeueOrReinit(connection, endpoint, done, buf, buffers)) {
                queueFailures++
                inFlight--
                log("!! 重新排队失败（重建 request 也失败）：丢弃 1 路，仍在飞 $inFlight 路")
                if (inFlight <= 0) {
                    reason = "全部 request 排队失败，取流中断"
                    break
                }
            }

            emitStatsIfDue()
        }

        val endNs = System.nanoTime()
        running = false
        val finalStats = snapshot(
            startNs, endNs, endNs - lastSampleNs, totalBytes, windowBytes,
            fidFlips, windowFidFlips, completed, queueFailures, requestErrors
        )
        logSummary(finalStats, lastStats)
        listener.onStats(finalStats)

        // 清理：正常路径下主动 close() 掉池里的 request（每个 UsbRequest 在内核侧占一对 socket），
        // 避免每次测试都漏 fd。若连接已经被 stop() 的兜底逻辑强制关闭，说明走了异常路径，
        // 这时不再碰这些 request，交给 GC/连接回收，免得和 requestWait 抢同一个 native 结构。
        synchronized(stateLock) {
            pooled.clear()
            if (!connectionClosed) {
                for (req in requests) {
                    try {
                        req.close()
                    } catch (t: Throwable) {
                        // 忽略
                    }
                }
            }
        }
        return reason
    }

    private fun snapshot(
        startNs: Long,
        nowNs: Long,
        windowNs: Long,
        totalBytes: Long,
        windowBytes: Long,
        fidFlips: Int,
        windowFidFlips: Int,
        completed: Int,
        queueFailures: Int,
        requestErrors: Int
    ): Stats {
        val runNs = nowNs - startNs
        return Stats(
            elapsedMs = runNs / 1_000_000,
            totalBytes = totalBytes,
            instantMBps = if (windowNs > 0) windowBytes.toDouble() / (windowNs / 1e9) / 1e6 else 0.0,
            avgMBps = if (runNs > 0) totalBytes.toDouble() / (runNs / 1e9) / 1e6 else 0.0,
            equivalentFps = if (runNs > 0) totalBytes.toDouble() / profile.frameBytes / (runNs / 1e9) else 0.0,
            frameBytes = profile.frameBytes,
            targetBytesPerSec = profile.targetBytesPerSec,
            fidFlips = fidFlips,
            fidFps = if (runNs > 0) fidFlips.toDouble() / (runNs / 1e9) else 0.0,
            instantFidFps = if (windowNs > 0) windowFidFlips.toDouble() / (windowNs / 1e9) else 0.0,
            completed = completed,
            queueFailures = queueFailures,
            requestErrors = requestErrors
        )
    }

    private fun logSummary(finalStats: Stats, lastStats: Stats?) {
        val avgBytesPerSec = if (finalStats.elapsedMs > 0) finalStats.totalBytes * 1000.0 / finalStats.elapsedMs else 0.0
        log("")
        log("---- 取流结果汇总 ----")
        log(String.format(Locale.US, "运行时长          : %.2f s", finalStats.elapsedMs / 1000.0))
        log(String.format(Locale.US, "总字节            : %d B = %.1f MB(十进制) = %.1f MiB", finalStats.totalBytes, finalStats.totalBytes / 1e6, finalStats.totalBytes / 1024.0 / 1024.0))
        if (profile.isMjpeg) {
            // MJPEG 压缩流：帧字节数只是上限，达成率/等效帧率按上限折算没有意义
            log(String.format(Locale.US, "平均速率          : %.1f MB/s (十进制) = %.1f Mbps（MJPEG 压缩流，帧上限折算值 %.1f MB/s 仅供参考）", finalStats.avgMBps, avgBytesPerSec * 8 / 1e6, profile.targetBytesPerSec / 1e6))
            log(String.format(Locale.US, "最后一秒瞬时速率  : %.1f MB/s", lastStats?.instantMBps ?: finalStats.instantMBps))
            log(String.format(Locale.US, "每帧 JPEG 上限    : %d 字节（dwMaxVideoFrameBufferSize），实际压缩帧更小", profile.frameBytes))
        } else {
            log(String.format(Locale.US, "平均速率          : %.1f MB/s (十进制) = %.1f Mbps；达成率 %.1f%%（目标 %.1f MB/s）", finalStats.avgMBps, avgBytesPerSec * 8 / 1e6, finalStats.achievedRatio * 100, profile.targetBytesPerSec / 1e6))
            log(String.format(Locale.US, "最后一秒瞬时速率  : %.1f MB/s", lastStats?.instantMBps ?: finalStats.instantMBps))
            log(String.format(Locale.US, "等效帧率(按字节)  : %.2f fps（每帧 %d 字节）", finalStats.equivalentFps, profile.frameBytes))
        }
        log(String.format(Locale.US, "实际帧率(FID)     : %.2f fps（FID 翻转 %d 次，近似值）", finalStats.fidFps, finalStats.fidFlips))
        log(String.format(Locale.US, "成功收割次数      : %d", finalStats.completed))
        log(String.format(Locale.US, "queue 失败次数    : %d", finalStats.queueFailures))
        log(String.format(Locale.US, "request 错误次数  : %d", finalStats.requestErrors))
        log(">>> FID 说明: bulk 型 UVC 的每次载荷以 bHeaderLength(1)+bBitmap(1) 开头，bBitmap bit0 是 FID，")
        log(">>> 每帧翻转一次，翻转次数即帧数。本统计按「传输首字节在 2..12（合法头长范围）就当作载荷起始」")
        log(">>> 做近似；若载荷被 $TRANSFER_BUFFER_SIZE 字节的传输边界切断、或一次传输里塞了多个载荷，")
        log(">>> 计数会偏多或偏少，因此 FID 帧率只是量级参考，不等同于逐帧精确计数。")
    }

    // ==================== 控制传输诊断 ====================

    /** 一次控制传输的结果 */
    private class CtrlCall(val res: Int, val elapsedMs: Long)

    /** 一轮诊断的汇总 */
    private class Diag(
        /** GET_LEN 的返回值（2 = 成功） */
        val getLenRes: Int,
        /** 设备报告的 probe/commit 结构长度（失败为 -1） */
        val deviceLen: Int,
        /** SET_CUR(PROBE) 发 26 字节的返回值 */
        val setCur26: Int,
        /** SET_CUR(PROBE) 发 34 字节的返回值 */
        val setCur34: Int
    )

    /**
     * 发一次控制传输并打印「返回值 + 耗时」。
     *
     * 耗时是这里信息量最大的字段：
     * - 返回 -1 且耗时 ≈ 超时上限 → 设备在整个超时窗口内根本没应答（设备侧无响应 / 被内核驱动占用）
     * - 返回 -1 但耗时很短 → 立刻被拒，即 STALL（参数或长度不被接受）
     * - >= 0 → 这条控制通道可用
     */
    private fun ctrl(
        connection: UsbDeviceConnection,
        label: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int
    ): CtrlCall {
        val t0 = System.nanoTime()
        val res = try {
            connection.controlTransfer(
                requestType, request, value, index, buffer, length, DIAG_TIMEOUT_MS
            )
        } catch (t: Throwable) {
            log("      $label 抛异常: $t")
            -1
        }
        val ms = (System.nanoTime() - t0) / 1_000_000L
        val verdict = when {
            res >= 0 -> "OK"
            ms >= DIAG_TIMEOUT_MS - 80 -> "超时（设备在 ${DIAG_TIMEOUT_MS}ms 内无应答）"
            else -> "立刻被拒（STALL：参数或长度不被接受）"
        }
        log(String.format(Locale.US, "      %s -> res=%d, %dms, %s", label, res, ms, verdict))
        return CtrlCall(res, ms)
    }

    /**
     * 控制传输诊断阶梯。
     *
     * [standard] 为 true 时先发三条与 UVC 无关的标准请求（GET_DESCRIPTOR / GET_CONFIGURATION /
     * GET_STATUS）—— 它们是判断「EP0 通道本身是否可用」的基线。
     * [full] 为 true 时把 UVC 类请求铺开（含 SET_CUR 的两种长度）。
     */
    private fun diagnose(
        connection: UsbDeviceConnection,
        phase: String,
        standard: Boolean,
        full: Boolean,
        vsIntf: UsbInterface,
        vcIntf: UsbInterface?,
        probeStruct: ByteArray?
    ): Diag {
        log("")
        log("---- 控制传输诊断（$phase）----")
        if (standard) {
            ctrl(connection, "标准 GET_DESCRIPTOR(DEVICE) 18B", 0x80, 0x06, 0x0100, 0, ByteArray(18), 18)
            ctrl(connection, "标准 GET_CONFIGURATION 1B", 0x80, 0x08, 0, 0, ByteArray(1), 1)
            ctrl(connection, "标准 GET_STATUS(device) 2B", 0x80, 0x00, 0, 0, ByteArray(2), 2)
        }

        val lenBytes = ByteArray(2)
        val lenRes = ctrl(
            connection,
            "UVC GET_LEN(PROBE) wIndex=${vsIntf.id}",
            UvcConstants.REQ_TYPE_GET_CUR,
            UvcConstants.UVC_GET_LEN,
            VS_PROBE_CONTROL shl 8,
            vsIntf.id,
            lenBytes,
            2
        ).res
        val deviceLen = if (lenRes == 2) u16le(lenBytes, 0) else -1
        if (lenRes == 2) {
            log("          -> 设备报告 probe/commit 结构长度 = $deviceLen 字节")
        }

        if (full) {
            // 另一种 wIndex 约定：有些廉价采集卡把接口号放在高字节（UVC 规范文本与 Linux 实现不一致）
            ctrl(
                connection,
                "UVC GET_LEN(PROBE) wIndex=0x" + (vsIntf.id shl 8).toString(16) + "（另一种约定）",
                UvcConstants.REQ_TYPE_GET_CUR,
                UvcConstants.UVC_GET_LEN,
                VS_PROBE_CONTROL shl 8,
                vsIntf.id shl 8,
                ByteArray(2),
                2
            )
            ctrl(
                connection,
                "UVC GET_INFO(PROBE) wIndex=${vsIntf.id}",
                UvcConstants.REQ_TYPE_GET_CUR,
                UvcConstants.UVC_GET_INFO,
                VS_PROBE_CONTROL shl 8,
                vsIntf.id,
                ByteArray(1),
                1
            )
            ctrl(
                connection,
                "UVC GET_DEF(PROBE) 34B",
                UvcConstants.REQ_TYPE_GET_CUR,
                UvcConstants.UVC_GET_DEF,
                VS_PROBE_CONTROL shl 8,
                vsIntf.id,
                ByteArray(PROBE_COMMIT_LENGTH),
                PROBE_COMMIT_LENGTH
            )
            ctrl(
                connection,
                "UVC GET_CUR(PROBE) 34B",
                UvcConstants.REQ_TYPE_GET_CUR,
                UvcConstants.UVC_GET_CUR,
                VS_PROBE_CONTROL shl 8,
                vsIntf.id,
                ByteArray(PROBE_COMMIT_LENGTH),
                PROBE_COMMIT_LENGTH
            )
            if (vcIntf != null) {
                // 对 VC（接口 0）上的处理单元发一条请求（PU_BRIGHTNESS，entity id=2）：
                // 用来分辨「整个设备的 EP0 都不通」还是「只有 VS 接口的请求不通」
                ctrl(
                    connection,
                    "UVC GET_CUR(PU_BRIGHTNESS) CS=0x0200 wIndex=0x0200 接口=${vcIntf.id}",
                    UvcConstants.REQ_TYPE_GET_CUR,
                    UvcConstants.UVC_GET_CUR,
                    0x0200,
                    (2 shl 8) or vcIntf.id,
                    ByteArray(2),
                    2
                )
            }
        }

        var set26 = -1
        var set34 = -1
        if (probeStruct != null) {
            set26 = ctrl(
                connection,
                "UVC SET_CUR(PROBE) 26B（UVC 1.0 的长度）",
                UvcConstants.REQ_TYPE_SET_CUR,
                UvcConstants.UVC_SET_CUR,
                VS_PROBE_CONTROL shl 8,
                vsIntf.id,
                probeStruct,
                PROBE_COMMIT_LENGTH_MIN
            ).res
            set34 = ctrl(
                connection,
                "UVC SET_CUR(PROBE) 34B（UVC 1.1 的长度）",
                UvcConstants.REQ_TYPE_SET_CUR,
                UvcConstants.UVC_SET_CUR,
                VS_PROBE_CONTROL shl 8,
                vsIntf.id,
                probeStruct,
                PROBE_COMMIT_LENGTH
            ).res
        }
        return Diag(lenRes, deviceLen, set26, set34)
    }

    /**
     * claim 接口。force = true 走 USBDEVFS_DISCONNECT_CLAIM，
     * 会把已经绑定在内核驱动（uvcvideo）上的接口抢过来。
     */
    private fun claimInterface(connection: UsbDeviceConnection, intf: UsbInterface): Boolean = try {
        connection.claimInterface(intf, true)
    } catch (t: Throwable) {
        log("!! claimInterface(${intf.id}) 抛异常: $t")
        false
    }

    // ==================== 协议细节 ====================

    /**
     * 构造 VS probe/commit 控制结构（34 字节，小端）。字段布局（UVC 1.1 / VS_PROBE_COMMIT_CONTROL）：
     * 0 bmHint(2) / 2 bFormatIndex(1) / 3 bFrameIndex(1) / 4 dwFrameInterval(4) /
     * 8 wKeyFrameRate(2) / 10 wPFrameRate(2) / 12 wCompQuality(2) / 14 wCompWindowSize(2) /
     * 16 wDelay(2) / 18 dwMaxVideoFrameSize(4) / 22 dwMaxPayloadTransferSize(4) /
     * 26 dwClockFrequency(4) / 30 bmFramingInfo(1) / 31 bPreferredVersion(1) /
     * 32 bMinVersion(1) / 33 bMaxVersion(1)。
     * 前 26 字节即 UVC 1.0 的结构（偏移 26 之后是 1.1 追加的字段）。
     */
    private fun buildProbeCommit(maxPayload: Long): ByteArray {
        val b = ByteArray(PROBE_COMMIT_LENGTH)
        putU16(b, 0, 0)                       // bmHint
        b[2] = profile.formatIndex.toByte()   // bFormatIndex
        b[3] = profile.frameIndex.toByte()    // bFrameIndex
        putU32(b, 4, profile.interval)        // dwFrameInterval
        putU16(b, 8, 0)                       // wKeyFrameRate
        putU16(b, 10, 0)                      // wPFrameRate
        putU16(b, 12, 0)                      // wCompQuality
        putU16(b, 14, 0)                      // wCompWindowSize
        putU16(b, 16, 0)                      // wDelay
        putU32(b, 18, profile.maxVideoFrameSize)  // dwMaxVideoFrameSize
        putU32(b, 22, maxPayload)             // dwMaxPayloadTransferSize（先 0，以设备返回值为准）
        putU32(b, 26, VC_CLOCK_FREQUENCY)     // dwClockFrequency = VC_HEADER 的上报值
        b[30] = 0                             // bmFramingInfo
        b[31] = 0                             // bPreferredVersion
        b[32] = 0                             // bMinVersion
        b[33] = 4                             // bMaxVersion（支持 UVC 1.1）
        return b
    }

    private fun setCur(
        connection: UsbDeviceConnection,
        selector: Int,
        struct: ByteArray,
        length: Int
    ): Int = try {
        connection.controlTransfer(
            UvcConstants.REQ_TYPE_SET_CUR,
            UvcConstants.UVC_SET_CUR,
            selector shl 8,
            VS_INTERFACE_NUMBER,
            struct,
            length,
            CONTROL_TIMEOUT_MS
        )
    } catch (t: Throwable) {
        log("!! controlTransfer(SET_CUR, selector=0x${selector.toString(16)}) 异常: $t")
        -1
    }

    private fun queue(req: UsbRequest, buf: ByteBuffer): Boolean = try {
        req.queue(buf)
    } catch (t: Throwable) {
        false
    }

    /**
     * 重新排队一个刚收割完的 request；直接排队失败时把该 request **重建**后重排一次。
     *
     * v0.21.0 的实测故障：HDMI 源换分辨率瞬间采集卡 bulk 端点整个出错，32 路 request
     * 的重排一次性全灭（"queue失败 32 ｜ 错误 28"），此后 requestWait 只剩空超时、
     * 会话僵死。usbfs 的 request 一旦排队失败往往就废了，但**换一个新 request 只是
     * 重新提交 URB**，多数瞬时错误下能直接恢复；确实不行才丢弃这一路（由调用方计数）。
     */
    private fun requeueOrReinit(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        req: UsbRequest,
        buf: ByteBuffer,
        buffers: HashMap<UsbRequest, ByteBuffer>
    ): Boolean {
        if (queue(req, buf)) return true
        try {
            req.close()
        } catch (t: Throwable) {
            // 忽略
        }
        val newReq = UsbRequest()
        val ok = try {
            newReq.initialize(connection, endpoint)
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            try {
                newReq.close()
            } catch (t: Throwable) {
                // 忽略
            }
            return false
        }
        buffers.remove(req)
        buffers[newReq] = buf
        synchronized(stateLock) {
            pooled.remove(req)
            pooled.add(newReq)
        }
        return queue(newReq, buf)
    }

    private fun logInterfaces() {
        for (i in 0 until device.interfaceCount) {
            val intf = try {
                device.getInterface(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            log(
                String.format(
                    Locale.US,
                    "[步骤2] 接口总览 [%d] id=%d alt=%d class=0x%02x sub=0x%02x endpoints=%d",
                    i,
                    intf.id,
                    intf.alternateSetting,
                    intf.interfaceClass,
                    intf.interfaceSubclass,
                    intf.endpointCount
                )
            )
        }
    }

    private fun findInterface(device: UsbDevice, number: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = try {
                device.getInterface(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (intf.id == number) return intf
        }
        return null
    }

    private fun findBulkInEndpoint(intf: UsbInterface): UsbEndpoint? {
        var found: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = try {
                intf.getEndpoint(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            val match = ep.address == BULK_ENDPOINT_ADDRESS &&
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_IN
            log(
                String.format(
                    Locale.US,
                    "[步骤2] endpoint #%d addr=0x%02x type=%s dir=%s maxPacketSize=%d interval=%d%s",
                    i,
                    ep.address,
                    xferName(ep.type),
                    if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                    ep.maxPacketSize,
                    ep.interval,
                    if (match) "   <== 本次使用" else ""
                )
            )
            if (match && found == null) found = ep
        }
        return found
    }

    private fun xferName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        else -> "UNKNOWN($type)"
    }

    private fun log(line: String) {
        listener.onLog(if (line.endsWith("\n")) line else line + "\n")
    }

    // ==================== 小端读写 ====================

    private fun u8(d: ByteArray, o: Int): Int = d[o].toInt() and 0xFF

    private fun u16le(d: ByteArray, o: Int): Int = u8(d, o) or (u8(d, o + 1) shl 8)

    private fun u32le(d: ByteArray, o: Int): Long =
        u8(d, o).toLong() or
            (u8(d, o + 1).toLong() shl 8) or
            (u8(d, o + 2).toLong() shl 16) or
            (u8(d, o + 3).toLong() shl 24)

    private fun u32leBuf(d: ByteBuffer, o: Int): Long =
        (d.get(o).toLong() and 0xFF) or
            ((d.get(o + 1).toLong() and 0xFF) shl 8) or
            ((d.get(o + 2).toLong() and 0xFF) shl 16) or
            ((d.get(o + 3).toLong() and 0xFF) shl 24)

    private fun putU16(d: ByteArray, o: Int, value: Int) {
        d[o] = (value and 0xFF).toByte()
        d[o + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putU32(d: ByteArray, o: Int, value: Long) {
        d[o] = (value and 0xFF).toByte()
        d[o + 1] = ((value shr 8) and 0xFF).toByte()
        d[o + 2] = ((value shr 16) and 0xFF).toByte()
        d[o + 3] = ((value shr 24) and 0xFF).toByte()
    }

    companion object {
        /** probe/commit 完整结构长度（UVC 1.1） */
        private const val PROBE_COMMIT_LENGTH = 34

        /** UVC 1.0 的结构长度（前 26 字节） */
        private const val PROBE_COMMIT_LENGTH_MIN = 26

        /** VC（VideoControl）接口号：本机接口 0 */
        private const val VC_INTERFACE_NUMBER = 0

        /** 诊断阶梯用的控制传输超时：比正式协商短，避免十几条诊断把时间拖太长 */
        private const val DIAG_TIMEOUT_MS = 800

        /** 单次 bulk 传输的缓冲区大小：usbfs 内核单次传输上限就是 16384 */
        private const val TRANSFER_BUFFER_SIZE = 16384

        /** requestWait 超时：保证主循环每 200ms 能重新检查一次停止标志 */
        private const val REQUEST_WAIT_TIMEOUT_MS = 200L

        /** 控制传输超时 */
        private const val CONTROL_TIMEOUT_MS = 2000

        /** 统计窗口 */
        private const val SAMPLE_INTERVAL_NS = 1_000_000_000L

        /** 强制关闭连接前留给线程正常退出的时间 */
        private const val STOP_GRACE_MS = 1500L
    }
}