package dev.padlink.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import dev.padlink.uvc.StreamProfile
import java.util.Locale

/**
 * 原始 USB 描述符解析器。
 *
 * 用 [UsbDeviceConnection.getRawDescriptors] 拿到完整的配置描述符字节流，
 * 自己走一遍描述符链表（首字节 bLength、第二字节 bDescriptorType，bLength==0 结束），
 * 并完整解析 UVC 的类特定描述符（CS_INTERFACE / CS_ENDPOINT），
 * 输出格式 / 分辨率 / 帧率，重点判断是否存在真实的 1080p60 模式。
 *
 * 所有多字节字段均为小端；帧间隔单位是 100ns，fps = 10000000 / interval。
 */
object RawDescriptorParser {

    // ---------- 标准描述符类型 ----------
    private const val DT_DEVICE = 0x01
    private const val DT_CONFIGURATION = 0x02
    private const val DT_STRING = 0x03
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val DT_INTERFACE_ASSOCIATION = 0x0B

    // ---------- 类特定描述符类型 ----------
    private const val CS_INTERFACE = 0x24
    private const val CS_ENDPOINT = 0x25

    // ---------- UVC 类 / 子类 ----------
    private const val CLASS_VIDEO = 0x0E
    private const val SUBCLASS_VIDEO_CONTROL = 0x01
    private const val SUBCLASS_VIDEO_STREAMING = 0x02

    // ---------- VS 接口描述符子类型 ----------
    private const val VS_INPUT_HEADER = 0x01
    private const val VS_STILL_IMAGE_FRAME = 0x03
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07
    private const val VS_FORMAT_FRAME_BASED = 0x0B
    private const val VS_FRAME_FRAME_BASED = 0x0C
    private const val VS_FORMAT_H264 = 0x0F
    private const val VS_FRAME_H264 = 0x10

    /** 帧间隔单位为 100ns：fps = 10_000_000 / interval */
    private const val HNS_PER_SECOND = 10_000_000.0

    /**
     * 描述符解析状态标记行：调用方（MainActivity）用它区分「确实没有 1080p60」和「没读到描述符」。
     */
    const val STATUS_OK = ">>> 描述符解析状态: OK"
    const val STATUS_UNAVAILABLE = ">>> 描述符解析状态: UNAVAILABLE"

    // 当前 VS 接口正在解析的格式（帧描述符本身不带格式索引，靠前一个格式推断）
    private class FormatInfo(
        val kind: String,
        val formatIndex: Int,
        val numFrames: Int,
        val fourcc: String?,
        /** 其余附加字段的已格式化文本，便于直接打印 */
        val extra: String
    )

    private class Highlight(
        val kind: String,
        val width: Int,
        val height: Int,
        val fps: Double,
        val interval: Long,
        val bufferSize: Long,
        val maxBitRate: Long
    )

    /**
     * 走 [UsbDeviceConnection.getRawDescriptors] 取描述符字节流后交给 [parseBytes] 解析。
     */
    fun parse(device: UsbDevice, connection: UsbDeviceConnection): String {
        val data: ByteArray = try {
            connection.rawDescriptors ?: ByteArray(0)
        } catch (t: Throwable) {
            return buildString {
                append("读取 rawDescriptors 失败: ").append(t).append('\n')
                append(STATUS_UNAVAILABLE).append('\n')
                append(">>> 原因: getRawDescriptors() 抛异常\n")
            }
        }
        return parseBytes(data, labelOf(device))
    }

    /**
     * 直接解析一段完整的配置描述符字节流（不依赖 UsbDeviceConnection）。
     * @param raw  描述符原始字节
     * @param label 打印在报告里的设备标识（例如 "2-2" 或 "/dev/bus/usb/002/002"）
     */
    fun parseBytes(raw: ByteArray, label: String): String {
        val sb = StringBuilder()
        val data = raw

        sb.append(
            String.format(
                Locale.US,
                "设备 %s 原始描述符长度: %d 字节\n",
                label,
                data.size
            )
        )
        if (data.isEmpty()) {
            sb.append("(描述符字节流为空，无法解析)\n")
            sb.append(STATUS_UNAVAILABLE).append('\n')
            sb.append(">>> 原因: 描述符字节流为空\n")
            return sb.toString()
        }

        // 遍历过程中的上下文
        var currentInterfaceNumber = -1
        var currentInterfaceClass = 0
        var currentInterfaceSubclass = 0
        var currentFormat: FormatInfo? = null
        // 设备描述符里的 bcdUSB：决定 bMaxPower 的单位（>= 0x0300 时为 8mA，否则 2mA）
        var bcdUsb = 0
        val highlights = ArrayList<Highlight>()
        // 同一个 UVC 接口的不同 alt setting 会重复出现，这里按接口号去重
        val uvcInterfaceNumbers = LinkedHashSet<Int>()

        var offset = 0
        var guard = 0
        // 描述符链是否损坏（越界）——损坏时状态标记为 UNAVAILABLE
        var bufferCorrupt = false
        while (offset + 2 <= data.size) {
            guard++
            if (guard > 4096) {
                sb.append("-- 描述符数量超过 4096，停止解析以防死循环 --\n")
                break
            }

            val bLength = u8(data, offset)
            if (bLength == 0) {
                sb.append("-- 遇到 bLength=0，描述符链结束 (offset=$offset) --\n")
                break
            }
            if (offset + bLength > data.size) {
                sb.append(
                    "-- bLength=$bLength 越界（offset=$offset，剩余 ${data.size - offset} 字节），链结束 --\n"
                )
                bufferCorrupt = true
                break
            }

            val bType = u8(data, offset + 1)
            try {
                when (bType) {
                    DT_DEVICE -> bcdUsb = appendDeviceDescriptor(sb, data, offset, bLength)

                    DT_CONFIGURATION -> appendConfigurationDescriptor(sb, data, offset, bLength, bcdUsb)

                    DT_INTERFACE -> {
                        currentInterfaceNumber = u8(data, offset + 2)
                        currentInterfaceClass = u8(data, offset + 5)
                        currentInterfaceSubclass = u8(data, offset + 6)
                        // 进入新接口后，之前累积的"当前格式"作废
                        currentFormat = null
                        if (currentInterfaceClass == CLASS_VIDEO) uvcInterfaceNumbers.add(currentInterfaceNumber)
                        appendInterfaceDescriptor(sb, data, offset, bLength)
                    }

                    DT_ENDPOINT -> appendEndpointDescriptor(sb, data, offset, bLength)

                    DT_INTERFACE_ASSOCIATION -> sb.append(
                        String.format(
                            Locale.US,
                            "  [IAD] firstInterface=%d interfaceCount=%d class=0x%02x sub=0x%02x proto=0x%02x\n",
                            u8(data, offset + 2),
                            u8(data, offset + 3),
                            u8(data, offset + 4),
                            u8(data, offset + 5),
                            u8(data, offset + 6)
                        )
                    )

                    DT_STRING -> sb.append(
                        String.format(
                            Locale.US,
                            "  [String Descriptor] index=%d len=%d (内容需 ioctl 读取，此处跳过)\n",
                            u8(data, offset + 2),
                            bLength
                        )
                    )

                    CS_INTERFACE -> {
                        val sub = u8(data, offset + 2)
                        if (currentInterfaceClass == CLASS_VIDEO &&
                            currentInterfaceSubclass == SUBCLASS_VIDEO_STREAMING
                        ) {
                            currentFormat = appendVsCsInterface(
                                sb, data, offset, bLength, sub, currentFormat, highlights
                            )
                        } else {
                            appendNonVsCsInterface(
                                sb, data, offset, bLength, sub,
                                currentInterfaceClass, currentInterfaceSubclass, currentInterfaceNumber
                            )
                        }
                    }

                    CS_ENDPOINT -> sb.append(
                        String.format(
                            Locale.US,
                            "  [CS_ENDPOINT] 接口 %d len=%d hex=%s\n",
                            currentInterfaceNumber,
                            bLength,
                            hex(data, offset, bLength)
                        )
                    )

                    else -> sb.append(
                        String.format(
                            Locale.US,
                            "  [未知描述符] type=0x%02x len=%d hex=%s\n",
                            bType,
                            bLength,
                            hex(data, offset, bLength)
                        )
                    )
                }
            } catch (t: Throwable) {
                // 容错：单个描述符解析失败不影响后续
                sb.append(
                    String.format(
                        Locale.US,
                        "  !! 解析异常(offset=%d, type=0x%02x, len=%d): %s ; hex=%s\n",
                        offset,
                        bType,
                        bLength,
                        t.toString(),
                        hex(data, offset, bLength)
                    )
                )
            }

            offset += bLength
        }

        // 收尾结论
        sb.append(if (bufferCorrupt) STATUS_UNAVAILABLE else STATUS_OK).append('\n')
        if (bufferCorrupt) {
            sb.append(">>> 原因: 描述符链越界/损坏，解析提前终止，以下为已解析部分\n")
        }
        sb.append("\n>>> UVC 接口(去重后)共 ").append(uvcInterfaceNumbers.size).append(" 个")
        if (uvcInterfaceNumbers.isNotEmpty()) {
            sb.append(": ").append(uvcInterfaceNumbers.joinToString(",") { "接口$it" })
        }
        sb.append('\n')
        sb.append(">>> 1080p60 检测:\n")
        if (highlights.isEmpty()) {
            sb.append(">>> 未检测到 1920x1080 且最大帧率 >= 59fps 的帧描述符\n")
        } else {
            for (h in highlights) {
                sb.append(
                    String.format(
                        Locale.US,
                        ">>> 1080p60 可用: %s %dx%d @ %.2ffps  (dwFrameInterval=%d, buffer=%.2fMB, maxBitRate=%.2fMbps)\n",
                        h.kind,
                        h.width,
                        h.height,
                        h.fps,
                        h.interval,
                        h.bufferSize / 1024.0 / 1024.0,
                        h.maxBitRate / 1_000_000.0
                    )
                )
            }
        }

        return sb.toString()
    }

    // ==================== 结构化提取（分辨率选项用） ====================

    /**
     * 走 [UsbDeviceConnection.getRawDescriptors] 取字节流后交给 [extractProfiles]。
     */
    fun extractProfiles(device: UsbDevice, connection: UsbDeviceConnection): List<StreamProfile> {
        val raw: ByteArray = try {
            connection.rawDescriptors ?: ByteArray(0)
        } catch (t: Throwable) {
            ByteArray(0)
        }
        return extractProfiles(raw)
    }

    /**
     * 从描述符字节流里结构化提取全部模式（格式索引 / 帧索引 / 分辨率 / 帧率），
     * 供预览页生成分辨率按钮与断流自动换档；与 [parseBytes] 的文本报告互不影响。
     *
     * 每档取该分辨率的**最大帧率**（最小帧间隔）作为代表；全部帧间隔存在
     * [StreamProfile.allIntervals] 里。只做结构提取，不做可用性判断。
     */
    fun extractProfiles(raw: ByteArray): List<StreamProfile> {
        val out = ArrayList<StreamProfile>()
        var vsSubclass = false
        var formatIndex = -1
        var fourcc = ""
        var kind = ""
        var offset = 0
        var guard = 0
        while (offset + 2 <= raw.size) {
            guard++
            if (guard > 4096) break
            val bLength = u8(raw, offset)
            if (bLength == 0 || offset + bLength > raw.size) break
            val bType = u8(raw, offset + 1)
            try {
                when {
                    bType == DT_INTERFACE -> {
                        // 新接口后"当前格式"作废；只有 VS 接口下的格式/帧描述符才有效
                        vsSubclass = u8(raw, offset + 5) == CLASS_VIDEO &&
                            u8(raw, offset + 6) == SUBCLASS_VIDEO_STREAMING
                        formatIndex = -1
                    }

                    bType == CS_INTERFACE && vsSubclass && bLength >= 3 -> when (u8(raw, offset + 2)) {
                        VS_FORMAT_UNCOMPRESSED -> {
                            formatIndex = u8(raw, offset + 3)
                            fourcc = fourccRaw(raw, offset + 5)
                            kind = "Uncompressed"
                        }

                        VS_FORMAT_MJPEG -> {
                            formatIndex = u8(raw, offset + 3)
                            fourcc = "MJPG"
                            kind = "MJPEG"
                        }

                        VS_FORMAT_FRAME_BASED -> {
                            formatIndex = u8(raw, offset + 3)
                            fourcc = fourccRaw(raw, offset + 5)
                            kind = "FrameBased"
                        }

                        VS_FORMAT_H264 -> {
                            formatIndex = u8(raw, offset + 3)
                            fourcc = "H264"
                            kind = "H264"
                        }

                        VS_FRAME_UNCOMPRESSED,
                        VS_FRAME_MJPEG,
                        VS_FRAME_FRAME_BASED,
                        VS_FRAME_H264 -> {
                            if (formatIndex > 0 && bLength >= 26) {
                                val frameIndex = u8(raw, offset + 3)
                                val width = u16(raw, offset + 5)
                                val height = u16(raw, offset + 7)
                                val bufferSize = u32(raw, offset + 17)
                                val defaultInterval = u32(raw, offset + 21)
                                val intervalType = u8(raw, offset + 25)
                                val intervals = ArrayList<Long>()
                                if (intervalType == 0) {
                                    // 连续模式：dwMin/dwMax/dwStep，展开成离散列表（上限 32 档防呆）
                                    if (bLength >= 38) {
                                        val minIv = u32(raw, offset + 26)
                                        val maxIv = u32(raw, offset + 30)
                                        val stepIv = u32(raw, offset + 34)
                                        if (minIv > 0 && stepIv > 0) {
                                            var iv = minIv
                                            while (iv <= maxIv && intervals.size < 32) {
                                                intervals.add(iv)
                                                iv += stepIv
                                            }
                                        } else if (minIv > 0) {
                                            intervals.add(minIv)
                                        }
                                    }
                                } else {
                                    // 离散模式：bFrameIntervalType 个 uint32
                                    for (i in 0 until intervalType) {
                                        if (bLength >= 26 + (i + 1) * 4) {
                                            intervals.add(u32(raw, offset + 26 + i * 4))
                                        }
                                    }
                                }
                                if (intervals.isEmpty()) intervals.add(defaultInterval)
                                intervals.sort()
                                out.add(
                                    StreamProfile(
                                        formatIndex = formatIndex,
                                        frameIndex = frameIndex,
                                        kind = kind,
                                        fourcc = fourcc,
                                        width = width,
                                        height = height,
                                        // 代表帧率取最大帧率（最小帧间隔）
                                        interval = intervals.first(),
                                        bufferSize = bufferSize,
                                        allIntervals = intervals
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // 单个描述符解析失败不影响后续
            }
            offset += bLength
        }
        return out
    }

    /** 取 guidFormat 前 4 字节的可读 FourCC（不可打印则返回空串） */
    private fun fourccRaw(d: ByteArray, o: Int): String {
        if (o + 4 > d.size) return ""
        val bytes = ByteArray(4)
        for (i in 0 until 4) bytes[i] = u8(d, o + i).toByte()
        if (!bytes.all { (it.toInt() and 0xFF) in 32..126 }) return ""
        return String(bytes, Charsets.US_ASCII)
    }

    // ==================== 标准描述符 ====================

    /** 返回该设备描述符里的 bcdUSB，供配置描述符换算 bMaxPower 单位 */
    private fun appendDeviceDescriptor(sb: StringBuilder, d: ByteArray, o: Int, len: Int): Int {
        val bcdUsb = u16(d, o + 2)
        sb.append(
            String.format(
                Locale.US,
                "[DEVICE] bcdUSB=0x%04x class=0x%02x sub=0x%02x proto=0x%02x bMaxPacketSize0=%d vid=%04x pid=%04x bcdDevice=0x%04x\n",
                bcdUsb,
                u8(d, o + 4),
                u8(d, o + 5),
                u8(d, o + 6),
                u8(d, o + 7),
                u16(d, o + 8),
                u16(d, o + 10),
                u16(d, o + 12)
            )
        )
        return bcdUsb
    }

    private fun appendConfigurationDescriptor(
        sb: StringBuilder,
        d: ByteArray,
        o: Int,
        len: Int,
        bcdUsb: Int
    ) {
        val total = u16(d, o + 2)
        val rawPower = u8(d, o + 8)
        // bMaxPower 的单位随 USB 版本变化：USB 3.x（bcdUSB >= 0x0300）为 8mA，其余为 2mA
        val unitMa = if (bcdUsb >= 0x0300) 8 else 2
        sb.append(
            String.format(
                Locale.US,
                "[CONFIGURATION] bLength=%d wTotalLength=%d bNumInterfaces=%d bConfigurationValue=%d bmAttributes=0x%02x bMaxPower=%d (x%dmA => %dmA) 配置描述符块共 %d 字节\n",
                len,
                total,
                u8(d, o + 4),
                u8(d, o + 5),
                u8(d, o + 7),
                rawPower,
                unitMa,
                rawPower * unitMa,
                len
            )
        )
    }

    private fun appendInterfaceDescriptor(sb: StringBuilder, d: ByteArray, o: Int, len: Int) {
        val number = u8(d, o + 2)
        val alt = u8(d, o + 3)
        val eps = u8(d, o + 4)
        val clazz = u8(d, o + 5)
        val sub = u8(d, o + 6)
        val proto = u8(d, o + 7)
        val uvcTag = when {
            clazz == CLASS_VIDEO && sub == SUBCLASS_VIDEO_CONTROL -> "   <== UVC VideoControl"
            clazz == CLASS_VIDEO && sub == SUBCLASS_VIDEO_STREAMING -> "   <== UVC VideoStreaming"
            clazz == CLASS_VIDEO -> "   <== UVC (其他子类)"
            else -> ""
        }
        sb.append(
            String.format(
                Locale.US,
                "\n[INTERFACE %d alt=%d] class=0x%02x sub=0x%02x proto=0x%02x endpoints=%d%s\n",
                number, alt, clazz, sub, proto, eps, uvcTag
            )
        )
    }

    private fun appendEndpointDescriptor(sb: StringBuilder, d: ByteArray, o: Int, len: Int) {
        val addr = u8(d, o + 2)
        val attr = u8(d, o + 3)
        val maxPacket = u16(d, o + 4)
        val interval = u8(d, o + 6)
        sb.append(
            String.format(
                Locale.US,
                "    [ENDPOINT] addr=0x%02x dir=%s type=%s maxPacketSize=%d interval=%d bmAttributes=0x%02x len=%d\n",
                addr,
                if (addr and 0x80 != 0) "IN" else "OUT",
                xferTypeName(attr and 0x03),
                maxPacket,
                interval,
                attr,
                len
            )
        )
    }

    // ==================== UVC 类特定描述符 ====================

    /**
     * 处理 VS 接口下的 CS_INTERFACE 描述符；返回"当前的格式上下文"（若本次描述符是格式描述符）。
     */
    private fun appendVsCsInterface(
        sb: StringBuilder,
        d: ByteArray,
        o: Int,
        len: Int,
        sub: Int,
        prevFormat: FormatInfo?,
        highlights: MutableList<Highlight>
    ): FormatInfo? {
        return when (sub) {
            VS_INPUT_HEADER -> {
                // 字段顺序（bLength=13+，末尾 bmaControls 为 bNumFormats 个字节）：
                // bNumFormats(1) → wTotalLength(2) → bEndpointAddress(1) → bmInfo(1) →
                // bTerminalLink(1) → bStillCaptureMethod(1) → bTriggerSupport(1) →
                // bTriggerUsage(1) → bControlSize(1) → bmaControls(n)
                // 之前漏读了中间的 wTotalLength 两个字节，导致 bEndpointAddress/bTerminalLink 全部错位。
                sb.append(
                    String.format(
                        Locale.US,
                        "  [CS] VS_INPUT_HEADER bNumFormats=%d wTotalLength=%d bEndpointAddress=0x%02x bmInfo=%d bTerminalLink=%d bStillCaptureMethod=%d bTriggerSupport=%d bTriggerUsage=%d bControlSize=%d\n",
                        if (len > 3) u8(d, o + 3) else -1,
                        if (len > 5) u16(d, o + 4) else -1,
                        if (len > 6) u8(d, o + 6) else -1,
                        if (len > 7) u8(d, o + 7) else -1,
                        if (len > 8) u8(d, o + 8) else -1,
                        if (len > 9) u8(d, o + 9) else -1,
                        if (len > 10) u8(d, o + 10) else -1,
                        if (len > 11) u8(d, o + 11) else -1,
                        if (len > 12) u8(d, o + 12) else -1
                    )
                )
                null
            }

            VS_STILL_IMAGE_FRAME -> {
                sb.append(
                    String.format(
                        Locale.US,
                        "  [CS] VS_STILL_IMAGE_FRAME len=%d hex=%s\n",
                        len,
                        hex(d, o, len)
                    )
                )
                null
            }

            VS_FORMAT_UNCOMPRESSED -> {
                val f = parseFormatUncompressedLike(d, o, len, "Uncompressed", false)
                sb.append(formatHeaderLine(f))
                f
            }

            VS_FORMAT_MJPEG -> {
                val f = parseFormatMjpeg(d, o, len)
                sb.append(formatHeaderLine(f))
                f
            }

            VS_FORMAT_FRAME_BASED -> {
                val f = parseFormatUncompressedLike(d, o, len, "FrameBased", true)
                sb.append(formatHeaderLine(f))
                f
            }

            VS_FORMAT_H264 -> {
                val f = parseFormatUncompressedLike(d, o, len, "H264", true)
                sb.append(formatHeaderLine(f))
                f
            }

            VS_FRAME_UNCOMPRESSED,
            VS_FRAME_MJPEG,
            VS_FRAME_FRAME_BASED,
            VS_FRAME_H264 -> {
                val kindFromSubtype = when (sub) {
                    VS_FRAME_UNCOMPRESSED -> "Uncompressed"
                    VS_FRAME_MJPEG -> "MJPEG"
                    VS_FRAME_FRAME_BASED -> "FrameBased"
                    else -> "H264"
                }
                val kind = prevFormat?.kind ?: kindFromSubtype
                sb.append(frameLines(d, o, kind, highlights))
                prevFormat
            }

            else -> {
                sb.append(
                    String.format(
                        Locale.US,
                        "  [CS] 未知 VS 子类型 0x%02x len=%d hex=%s\n",
                        sub,
                        len,
                        hex(d, o, len)
                    )
                )
                null
            }
        }
    }

    /** VC 接口或其他非 VS 场景下的 CS_INTERFACE */
    private fun appendNonVsCsInterface(
        sb: StringBuilder,
        d: ByteArray,
        o: Int,
        len: Int,
        sub: Int,
        clazz: Int,
        subClass: Int,
        interfaceNumber: Int
    ) {
        val name = if (clazz == CLASS_VIDEO && subClass == SUBCLASS_VIDEO_CONTROL) {
            vcSubtypeName(sub)
        } else {
            "Unknown"
        }
        sb.append(
            String.format(
                Locale.US,
                "  [CS_INTERFACE] 接口 %d class=0x%02x sub=0x%02x 子类型=0x%02x (%s) len=%d hex=%s\n",
                interfaceNumber,
                clazz,
                subClass,
                sub,
                name,
                len,
                hex(d, o, len)
            )
        )
    }

    // ==================== 格式描述符 ====================

    /**
     * VS_FORMAT_UNCOMPRESSED(0x04) / VS_FORMAT_FRAME_BASED(0x0B) / VS_FORMAT_H264(0x0F) 同构。
     * 末尾 frameBased=true 时多一个 bVariableSize。
     */
    private fun parseFormatUncompressedLike(
        d: ByteArray,
        o: Int,
        len: Int,
        kind: String,
        hasVariableSize: Boolean
    ): FormatInfo {
        val formatIndex = u8(d, o + 3)
        val numFrames = u8(d, o + 4)
        val fourcc = fourccFromGuid(d, o + 5)
        val bitsPerPixel = u8(d, o + 21)
        val defaultFrameIndex = u8(d, o + 22)
        val aspectX = u8(d, o + 23)
        val aspectY = u8(d, o + 24)
        val interlaceText = if (len > 25) String.format(Locale.US, "0x%02x", u8(d, o + 25)) else "N/A"
        val copyProtectText = if (len > 26) u8(d, o + 26).toString() else "N/A"
        val variableSize = if (hasVariableSize && len > 27) u8(d, o + 27) else null

        val extra = String.format(
            Locale.US,
            "bpp=%d default=%d aspect=%d:%d interlace=%s copyProtect=%s%s",
            bitsPerPixel,
            defaultFrameIndex,
            aspectX,
            aspectY,
            interlaceText,
            copyProtectText,
            if (variableSize != null) " variableSize=$variableSize" else ""
        )

        return FormatInfo(
            kind = kind,
            formatIndex = formatIndex,
            numFrames = numFrames,
            fourcc = fourcc,
            extra = extra
        )
    }

    /** VS_FORMAT_MJPEG(0x06) */
    private fun parseFormatMjpeg(d: ByteArray, o: Int, len: Int): FormatInfo {
        val formatIndex = u8(d, o + 3)
        val numFrames = u8(d, o + 4)
        val flags = u8(d, o + 5)
        val defaultFrameIndex = u8(d, o + 6)
        val aspectX = u8(d, o + 7)
        val aspectY = u8(d, o + 8)
        val extra = String.format(
            Locale.US,
            "bmFlags=0x%02x default=%d aspect=%d:%d",
            flags,
            defaultFrameIndex,
            aspectX,
            aspectY
        )
        return FormatInfo(
            kind = "MJPEG",
            formatIndex = formatIndex,
            numFrames = numFrames,
            fourcc = "MJPG",
            extra = extra
        )
    }

    private fun formatHeaderLine(f: FormatInfo): String = String.format(
        Locale.US,
        "  [格式 %d] %s  frameDescriptors=%d fourcc=%s  %s\n",
        f.formatIndex,
        f.kind,
        f.numFrames,
        f.fourcc ?: "N/A",
        f.extra
    )

    // ==================== 帧描述符 ====================

    /**
     * VS_FRAME_UNCOMPRESSED(0x05) / VS_FRAME_MJPEG(0x07) / VS_FRAME_FRAME_BASED(0x0C) / VS_FRAME_H264(0x10)
     * 字段：bFrameIndex(1) bmCapabilities(1) wWidth(2) wHeight(2)
     *      dwMinBitRate(4) dwMaxBitRate(4) dwMaxVideoFrameBufferSize(4)
     *      dwDefaultFrameInterval(4) bFrameIntervalType(1) [帧间隔数组]
     */
    private fun frameLines(
        d: ByteArray,
        o: Int,
        kind: String,
        highlights: MutableList<Highlight>
    ): String {
        val frameIndex = u8(d, o + 3)
        val capabilities = u8(d, o + 4)
        val width = u16(d, o + 5)
        val height = u16(d, o + 7)
        val minBitRate = u32(d, o + 9)
        val maxBitRate = u32(d, o + 13)
        val bufferSize = u32(d, o + 17)
        val defaultInterval = u32(d, o + 21)
        val intervalType = u8(d, o + 25)

        val out = StringBuilder()
        out.append(
            String.format(
                Locale.US,
                "    - 帧 %d [%s] %dx%d  bmCapabilities=0x%02x  buffer=%.2fMB  bitrate=%.2f-%.2fMbps  default=%.2ffps(dwFrameInterval=%d)\n",
                frameIndex,
                kind,
                width,
                height,
                capabilities,
                bufferSize / 1024.0 / 1024.0,
                minBitRate / 1_000_000.0,
                maxBitRate / 1_000_000.0,
                fpsOf(defaultInterval),
                defaultInterval
            )
        )

        var maxFps = fpsOf(defaultInterval)
        var maxFpsInterval = defaultInterval

        if (intervalType == 0) {
            // 连续模式：dwMinFrameInterval, dwMaxFrameInterval, dwFrameIntervalStep
            val minIv = u32(d, o + 26)
            val maxIv = u32(d, o + 30)
            val stepIv = u32(d, o + 34)
            out.append(
                String.format(
                    Locale.US,
                    "        连续帧间隔: min=%d (%.2ffps) - max=%d (%.2ffps) step=%d (%.2ffps)\n",
                    minIv,
                    fpsOf(minIv),
                    maxIv,
                    fpsOf(maxIv),
                    stepIv,
                    fpsOf(stepIv)
                )
            )
            if (fpsOf(minIv) > maxFps) {
                maxFps = fpsOf(minIv)
                maxFpsInterval = minIv
            }
        } else {
            // 离散模式：bFrameIntervalType 个 uint32
            val count = intervalType
            val values = ArrayList<Long>(count)
            for (i in 0 until count) {
                values.add(u32(d, o + 26 + i * 4))
            }
            out.append("        离散帧间隔 ").append(count).append(" 个: ")
            for ((i, v) in values.withIndex()) {
                if (i > 0) out.append(", ")
                out.append(String.format(Locale.US, "%d(%.2ffps)", v, fpsOf(v)))
            }
            out.append('\n')
            for (v in values) {
                if (fpsOf(v) > maxFps) {
                    maxFps = fpsOf(v)
                    maxFpsInterval = v
                }
            }
        }

        if (width == 1920 && height == 1080 && maxFps >= 59.0) {
            highlights.add(
                Highlight(
                    kind = kind,
                    width = width,
                    height = height,
                    fps = maxFps,
                    interval = maxFpsInterval,
                    bufferSize = bufferSize,
                    maxBitRate = maxBitRate
                )
            )
        }

        return out.toString()
    }

    // ==================== 工具方法 ====================

    /** 报告头里的设备标识：保持与旧版 "设备 <deviceName> (vid:pid)" 完全一致的输出 */
    private fun labelOf(device: UsbDevice): String = String.format(
        Locale.US,
        "%s (%04x:%04x)",
        device.deviceName,
        device.vendorId,
        device.productId
    )

    private fun u8(d: ByteArray, o: Int): Int = d[o].toInt() and 0xFF

    private fun u16(d: ByteArray, o: Int): Int = u8(d, o) or (u8(d, o + 1) shl 8)

    private fun u32(d: ByteArray, o: Int): Long =
        u8(d, o).toLong() or
            (u8(d, o + 1).toLong() shl 8) or
            (u8(d, o + 2).toLong() shl 16) or
            (u8(d, o + 3).toLong() shl 24)

    private fun fpsOf(interval: Long): Double =
        if (interval <= 0L) 0.0 else HNS_PER_SECOND / interval.toDouble()

    private fun hex(d: ByteArray, start: Int, len: Int): String {
        val end = minOf(start + len, d.size)
        if (start >= end) return ""
        val sb = StringBuilder((end - start) * 3)
        for (i in start until end) {
            sb.append(String.format(Locale.US, "%02x", u8(d, i)))
            if (i < end - 1) sb.append(' ')
        }
        return sb.toString()
    }

    /**
     * UVC guidFormat[16] 是标准 GUID 的小端存放；前 4 字节按顺序即为可读 FourCC
     * （例如 YUY2 GUID 的字节序列为 59 55 59 32 ... => "YUY2"）。
     */
    private fun fourccFromGuid(d: ByteArray, o: Int): String {
        val bytes = ByteArray(4)
        for (i in 0 until 4) bytes[i] = u8(d, o + i).toByte()
        val printable = bytes.all { (it.toInt() and 0xFF) in 32..126 }
        if (!printable) {
            return "0x" + bytes.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
        }
        val fourcc = String(bytes, Charsets.US_ASCII)
        val friendly = when (fourcc) {
            "YUY2", "YUYV" -> "$fourcc(未压缩 YUV422)"
            "UYVY" -> "$fourcc(未压缩 YUV422)"
            "NV12" -> "$fourcc(YUV420SP)"
            "I420", "YV12" -> "$fourcc(YUV420P)"
            "MJPG" -> "$fourcc(MJPEG)"
            "H264" -> "$fourcc"
            "RGB3", "RGBP" -> "$fourcc"
            "GREY" -> "$fourcc"
            else -> fourcc
        }
        return friendly
    }

    private fun xferTypeName(attr: Int): String = when (attr and 0x03) {
        0x00 -> "CONTROL"
        0x01 -> "ISOC"
        0x02 -> "BULK"
        0x03 -> "INTERRUPT"
        else -> "?"
    }

    private fun vcSubtypeName(sub: Int): String = when (sub) {
        0x01 -> "VC_HEADER"
        0x02 -> "VC_INPUT_TERMINAL"
        0x03 -> "VC_OUTPUT_TERMINAL"
        0x04 -> "VC_SELECTOR_UNIT"
        0x05 -> "VC_PROCESSING_UNIT"
        0x06 -> "VC_EXTENSION_UNIT"
        0x07 -> "VC_ENCODING_UNIT"
        else -> "VC_UNKNOWN"
    }
}