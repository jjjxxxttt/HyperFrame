package dev.padlink.uvc

import java.util.Locale

/**
 * 一档可用的采集模式（从设备原始描述符结构化解析得到）。
 *
 * v0.18.0 之前整条链路硬编码 1080p60（[UvcConstants] 里的 FORMAT_INDEX_YUY2 /
 * FRAME_INDEX_1080P 等），HDMI 源一换分辨率（典型场景：进游戏后主机切时序），
 * 采集卡输出与协商模式对不上，就只剩黑场。本类把"分辨率 + 帧率"从描述符里
 * 解析成结构化档位，协商、装配、渲染全部按档位参数化，界面才能给出真正的
 * 分辨率选项、断流后也才能自动换档重协商。
 *
 * 帧间隔单位是 100ns（UVC 约定），fps = 10_000_000 / interval。
 */
data class StreamProfile(
    /** bFormatIndex（probe/commit 的 bFormatIndex） */
    val formatIndex: Int,
    /** bFrameIndex（probe/commit 的 bFrameIndex） */
    val frameIndex: Int,
    /** 格式类别："Uncompressed" / "MJPEG" 等 */
    val kind: String,
    /** FourCC，如 "YUY2" */
    val fourcc: String,
    val width: Int,
    val height: Int,
    /** dwFrameInterval（100ns） */
    val interval: Long,
    /** 描述符上报的 dwMaxVideoFrameBufferSize */
    val bufferSize: Long,
    /** 该模式支持的全部帧间隔（100ns，升序） */
    val allIntervals: List<Long> = emptyList()
) {
    /** 本档帧率 */
    val fps: Double
        get() = if (interval > 0) 10_000_000.0 / interval else 0.0

    /** 是否 MJPEG 压缩流（v0.22.0 起支持：1080p120 / 4K60 只能走 MJPEG） */
    val isMjpeg: Boolean
        get() = kind == "MJPEG" || fourcc.startsWith("MJ", ignoreCase = true)

    /**
     * 一帧字节数：
     * - YUY2：宽 × 高 × 2（定长像素流）；
     * - MJPEG：取描述符 dwMaxVideoFrameBufferSize（JPEG 帧上限），统计按上限折算。
     */
    val frameBytes: Int
        get() = if (isMjpeg) {
            if (bufferSize > 0) bufferSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else width * height * 2
        } else {
            width * height * 2
        }

    /** 理论带宽（字节/秒）；MJPEG 为压缩流，这里给出的是按帧上限折算的带宽上限 */
    val targetBytesPerSec: Long
        get() = if (interval > 0) frameBytes.toLong() * 10_000_000L / interval else 0L

    /**
     * 是否超出 USB 3.x 实测可持续带宽（~380-420 MB/s）。
     *
     * v0.21.0 实测教训：4K30 YUY2（497.7 MB/s）每个会话只能活 2-5 秒就 queue 全灭，
     * 看门狗反复重协商形成"连 5 秒就断连"的死循环。这类档位 UI 要标警告、
     * 自动恢复轮询时排到最后（甚至跳过），请改用同分辨率的 MJPEG 档。
     */
    val overBandwidth: Boolean
        get() = !isMjpeg && targetBytesPerSec > USABLE_BANDWIDTH_BPS

    /** probe/commit 里 dwMaxVideoFrameSize 该填的值：优先取描述符上报值 */
    val maxVideoFrameSize: Long
        get() = if (bufferSize > 0) bufferSize else frameBytes.toLong()

    /** 按钮/状态栏上的短标签，如 "1920x1080@60" / "3840x2160@60(MJPEG)" */
    val label: String
        get() = String.format(
            Locale.US, "%dx%d@%.0f%s",
            width, height, fps,
            if (isMjpeg) "(MJPEG)" else if (overBandwidth) "(超带宽)" else ""
        )

    /** 全部支持帧率的短列表（日志用），如 "60,30,25" */
    val fpsList: String
        get() = allIntervals.joinToString(",") {
            String.format(Locale.US, "%.0f", if (it > 0) 10_000_000.0 / it else 0.0)
        }

    companion object {
        /**
         * USB 3.x 实测可持续带宽上限（字节/秒）：实测撑死 ~380-420 MB/s。
         * YUY2 理论带宽超过它的档位物理上跑不满，标注"(超带宽)"并排到列表末尾。
         */
        const val USABLE_BANDWIDTH_BPS = 400_000_000L

        /**
         * 兜底档位：描述符解析不到时用的 1080p60 YUY2，
         * 与 v0.17.0 及以前硬编码的参数完全一致（格式索引 2 / 帧索引 1）。
         */
        val DEFAULT_1080P60 = StreamProfile(
            formatIndex = UvcConstants.FORMAT_INDEX_YUY2,
            frameIndex = UvcConstants.FRAME_INDEX_1080P,
            kind = "Uncompressed",
            fourcc = "YUY2",
            width = UvcConstants.FRAME_WIDTH,
            height = UvcConstants.FRAME_HEIGHT,
            interval = UvcConstants.FRAME_INTERVAL_60FPS.toLong(),
            bufferSize = UvcConstants.FRAME_BYTES_YUY2.toLong(),
            allIntervals = listOf(UvcConstants.FRAME_INTERVAL_60FPS.toLong())
        )
    }
}
