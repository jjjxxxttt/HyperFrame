package dev.padlink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.Locale
import kotlin.math.sqrt

/**
 * HDMI 音频桥：采集卡的 UAC 音频 → 平板扬声器，纯 Kotlin、零第三方依赖。
 *
 * **为什么不能像视频那样直接从 USB 取**：采集卡音频端点是 0x82 IN **等时传输（ISO）**
 * （UAC1：PCM / 2ch / 16bit / 48000Hz），而 Android 公开的 UsbRequest 只支持
 * bulk/interrupt，等时 URB 根本发不出去。唯一纯 Kotlin 通路是：
 * **内核 snd-usb-audio 驱动挂载 UAC 设备 → 系统暴露 USB 音频输入设备 → AudioRecord 读取
 * → AudioTrack 转放**。这条路要求：
 * 1. App 持有 RECORD_AUDIO 运行时权限（AudioRecord 前置条件）；
 * 2. 内核确实把这台复合设备的音频接口挂成了系统输入设备（若没有，见 start() 里的诊断）。
 *
 * 若内核没挂载（start() 返回 false 且日志说明"未检测到 USB 音频输入"），后续可以走
 * NDK + libusb_wrap_sys_device 提交 ISO URB 的路线（v0.20+ 的候选后手）。
 *
 * 线程模型：一条读写线程做 20ms 分片的「读 AudioRecord → 写 AudioTrack」转放，
 * 顺手统计 RMS/峰值周期性写日志 —— 声音有没有出来，导出日志本身就能给出结论。
 */
class AudioBridge(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    /** 采集转放线程是否在跑 */
    @Volatile
    var isRunning = false
        private set

    /** 静音开关：静音时仍继续采集（排空输入），只向扬声器写静音保持队列喂满 */
    @Volatile
    var muted = false

    /**
     * 毛刺抑制截止时刻：此刻之前的输入只采集不转放。
     * 分辨率切换/重协商瞬间，采集卡 UAC 音频流会重同步，内核缓冲里混进不连续样本，
     * 直接播出就是"故障音"；这段时间的数据丢掉才干净。
     */
    @Volatile
    private var dampUntilNs = 0L

    /** 转放线程看到此标志后清空 AudioTrack 队列（track 操作只在转放线程上做，避免并发） */
    @Volatile
    private var flushRequested = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /**
     * 启动音频桥。成功返回 true；失败时返回 false 并把根因写进日志
     * （未检测到 USB 音频输入 / 格式不支持 / 权限缺失 / 设备被占用等）。
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        onLog(
            ">>> [声音] 系统输入设备共 " + inputs.size + " 个: " +
                inputs.joinToString("  ") { "${it.productName}(${deviceTypeName(it.type)})" } + "\n"
        )
        val usbIn = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (usbIn == null) {
            onLog(
                "!! [声音] 未检测到 USB 音频输入设备。\n" +
                    "!! [声音] 采集卡音频是等时传输（ISO），Android 公开 UsbRequest 只支持 bulk/interrupt，" +
                    "无法直接取流；唯一纯 Kotlin 通路是内核 snd-usb-audio 驱动挂载 UAC 设备后经 AudioRecord 读取。\n" +
                    "!! [声音] 请重新插拔采集卡（或重启 HyperFrame）后重试；若始终没有，说明内核没挂载该 UAC 设备。\n"
            )
            return false
        }
        onLog(">>> [声音] 选用 USB 音频输入: ${usbIn.productName}  id=${usbIn.id}\n")

        // UAC 描述符（padlink-v0.9.0 报告）：FORMAT_TYPE = Type I / 2ch / 16bit / 48000Hz
        val sampleRate = 48000
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minRec = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encoding)
        val minTrack = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        if (minRec <= 0 || minTrack <= 0) {
            onLog(String.format(Locale.US, "!! [声音] 48kHz/2ch/16bit 不被支持: getMinBufferSize record=%d track=%d\n", minRec, minTrack))
            return false
        }

        val recBufBytes = maxOf(minRec * 2, PERIOD_BYTES * 2)
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(recBufBytes)
                .build()
        } catch (t: Throwable) {
            onLog("!! [声音] AudioRecord 创建失败: $t（RECORD_AUDIO 权限未授予时也会走到这）\n")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            onLog("!! [声音] AudioRecord 未初始化（state=${rec.state}），常见原因：录音权限缺失 / 输入设备被占用\n")
            try {
                rec.release()
            } catch (t: Throwable) {
                // 忽略释放异常
            }
            return false
        }
        val preferred = try {
            rec.setPreferredDevice(usbIn)
        } catch (t: Throwable) {
            onLog("!! [声音] setPreferredDevice 异常: $t\n")
            false
        }
        onLog(">>> [声音] setPreferredDevice(USB 输入) = $preferred\n")

        // 输出缓冲比 v1 放大：重协商/换档瞬间读写会被挤占一下，缓冲厚一点才不会欠载
        // 发出"卡顿音"；代价只是音频多滞后约 80ms（视频延迟基线不走 AudioTrack，不受影响）
        val trackBufBytes = maxOf(minTrack * 4, PERIOD_BYTES * 6)
        val trk = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBufBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            onLog("!! [声音] AudioTrack 创建失败: $t\n")
            try {
                rec.release()
            } catch (t2: Throwable) {
                // 忽略释放异常
            }
            return false
        }

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            onLog("!! [声音] startRecording 失败: $t\n")
            try {
                rec.release()
            } catch (t2: Throwable) {
                // 忽略释放异常
            }
            try {
                trk.release()
            } catch (t2: Throwable) {
                // 忽略释放异常
            }
            return false
        }
        trk.play()

        record = rec
        track = trk
        isRunning = true
        val routed = try {
            rec.routedDevice
        } catch (t: Throwable) {
            null
        }
        onLog(
            String.format(
                Locale.US,
                ">>> [声音] 已启动: %dHz / 2ch / 16bit ｜ 缓冲 record=%dB track=%dB ｜ 实际路由: %s\n",
                sampleRate, recBufBytes, trackBufBytes, routed?.productName ?: "未知"
            )
        )
        thread = Thread({ relayLoop(rec, trk) }, "padlink-audio").also { it.start() }
        return true
    }

    /**
     * 压掉分辨率切换/重协商瞬间的声音毛刺（任意线程可调）：
     * 清空 AudioTrack 里排队的不连续样本（"故障音"来源），并丢弃接下来 [DAMP_NS] 的输入
     * （设备重同步期的垃圾数据不进扬声器）。抑制期间**向扬声器写静音**把队列喂满 ——
     * 直接停写会让 AudioTrack 欠载，恢复瞬间"啪"一声，这本身就是报错音的一部分。
     * 实际 pause/flush/play 由转放线程执行，与 write 串行，不会踩 AudioTrack 的并发。
     */
    fun dampGlitch() {
        flushRequested = true
        dampUntilNs = System.nanoTime() + DAMP_NS
    }

    /** 20ms 分片读写转放；每 2 秒把 RMS/峰值写进日志（声音是否真的在流，日志直接给结论） */
    private fun relayLoop(rec: AudioRecord, trk: AudioTrack) {
        // 转放线程提到最高音频优先级：取流是 248MB/s 的 USB 大户，重协商瞬间会挤占 CPU，
        // 普通优先级的转放线程一旦被调度饿到就欠载（"卡顿音"）
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
            // 忽略：个别设备不允许提权，不影响功能
        }
        val buf = ShortArray(PERIOD_BYTES / 2)
        val silence = ShortArray(PERIOD_BYTES / 2)
        var totalBytes = 0L
        var lastLogNs = System.nanoTime()
        var sumSquares = 0.0
        var samples = 0L
        var peak = 0
        var fullReadStreak = 0
        var fadeChunks = 0
        while (isRunning) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (t: Throwable) {
                -1
            }
            if (n <= 0) {
                if (isRunning) {
                    onLog("!! [声音] AudioRecord.read 返回 $n，音频采集中止\n")
                }
                break
            }
            if (n < buf.size) {
                // 短读 = 输入侧出现了空洞（HDMI 源换分辨率时 UAC 流重同步的典型表现），
                // 洞后常跟着不连续样本 —— 按毛刺自愈抑制一小段。
                // 只在近期一直满读（正常态）时才抑，避免设备习惯性短读被误判、越抑越哑。
                if (fullReadStreak >= 25) {
                    dampUntilNs = System.nanoTime() + INPUT_GAP_DAMP_NS
                    flushRequested = true
                }
                fullReadStreak = 0
            } else {
                fullReadStreak++
            }
            if (flushRequested) {
                flushRequested = false
                try {
                    trk.pause()
                    trk.flush()
                    trk.play()
                } catch (t: Throwable) {
                    // 忽略：flush 期间状态异常不影响后续转放
                }
            }
            val damping = System.nanoTime() < dampUntilNs
            if (damping) {
                fadeChunks = FADE_CHUNKS // 抑制结束后从淡入恢复
            }
            if (!muted && !damping) {
                if (fadeChunks > 0) {
                    // 毛刺抑制结束后的淡入（4 片 ≈ 80ms）：突然恢复满音量会"啪"一声
                    val vol = (FADE_CHUNKS - fadeChunks + 1).toFloat() / FADE_CHUNKS
                    try {
                        trk.setVolume(vol)
                    } catch (t: Throwable) {
                        // 忽略
                    }
                    fadeChunks--
                }
                writeAll(trk, buf, n)
            } else {
                // 抑制/静音期间写**静音**而不是停写：AudioTrack 一旦欠载，恢复播放瞬间的
                // "啪"声就是用户听到的报错音的一部分。写零样本把队列喂满，声音才是干净的哑
                writeAll(trk, silence, n)
            }
            for (i in 0 until n) {
                val v = buf[i].toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                sumSquares += v.toDouble() * v
            }
            samples += n
            totalBytes += n * 2L
            val now = System.nanoTime()
            if (now - lastLogNs >= STATS_LOG_NS) {
                val rms = if (samples > 0) sqrt(sumSquares / samples) else 0.0
                val note = when {
                    damping -> "毛刺抑制中（重连瞬间短暂丢弃）"
                    muted -> "已静音（仍采集排空）"
                    rms < 50 -> "近似静音（检查 HDMI 源是否带音频）"
                    else -> "转放中"
                }
                onLog(
                    String.format(
                        Locale.US,
                        ">>> [声音] %s ｜ RMS %.0f ｜ 峰值 %d ｜ 累计 %.1f KB\n",
                        note, rms, peak, totalBytes / 1024.0
                    )
                )
                sumSquares = 0.0
                samples = 0
                peak = 0
                lastLogNs = now
            }
        }
        onLog(String.format(Locale.US, ">>> [声音] 采集线程退出，累计转放 %.1f KB\n", totalBytes / 1024.0))
    }

    /** 把一片样本完整写入 AudioTrack（短写就续写），失败只记日志不中断转放 */
    private fun writeAll(trk: AudioTrack, data: ShortArray, n: Int) {
        var off = 0
        while (off < n && isRunning) {
            val w = trk.write(data, off, n - off)
            if (w <= 0) {
                onLog("!! [声音] AudioTrack.write 返回 $w\n")
                break
            }
            off += w
        }
    }

    /** 停止并释放音频设备。可重复调用，任意线程可调 */
    @Synchronized
    fun stop() {
        val wasRunning = isRunning || record != null
        isRunning = false
        val rec = record
        val trk = track
        val t = thread
        record = null
        track = null
        thread = null
        try {
            rec?.stop()
        } catch (t2: Throwable) {
            // 忽略：未初始化时 stop 会抛异常
        }
        if (t != null) {
            try {
                t.join(JOIN_GRACE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            trk?.pause()
            trk?.flush()
        } catch (t2: Throwable) {
            // 忽略
        }
        try {
            trk?.release()
        } catch (t2: Throwable) {
            // 忽略
        }
        try {
            rec?.release()
        } catch (t2: Throwable) {
            // 忽略
        }
        if (wasRunning) {
            onLog(">>> [声音] 已停止\n")
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳麦"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "type=$type"
    }

    companion object {
        /** 每片 20ms：48000Hz × 2ch × 2B × 0.02s = 3840 字节 */
        private const val PERIOD_BYTES = 3840

        /** RMS/峰值统计多久写一次日志 */
        private const val STATS_LOG_NS = 2_000_000_000L

        /** 毛刺抑制窗口：丢弃这段时间的输入（重协商/UAC 重同步的垃圾样本不进扬声器）。
         *  v0.24.0 加长到 2.5s：HDMI 源换分辨率后 UAC 重同步 + 会话重建整个过程都有毛刺，
         *  600ms 窗口盖不住，恢复瞬间照样漏一段"报错音"出去 */
        private const val DAMP_NS = 2_500_000_000L

        /** 输入侧"短读"（空洞）后的自愈抑制窗口 */
        private const val INPUT_GAP_DAMP_NS = 500_000_000L

        /** 抑制结束后的淡入分片数（每片 20ms，共 80ms，消掉恢复播放瞬间的"啪"声） */
        private const val FADE_CHUNKS = 4

        /** 停止时留给读写线程退出的时间 */
        private const val JOIN_GRACE_MS = 600L
    }
}
