package dev.padlink.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.Locale

/**
 * USB 设备清单：把 UsbManager 里能看到的设备 / 接口 / 端点全部打印出来，
 * 并标注哪些接口属于 UVC（USB Video Class）。
 */
object UsbInventory {

    /** USB 类代码：视频类（UVC） */
    private const val CLASS_VIDEO = 0x0E

    /** UVC 子类：VideoControl */
    private const val SUBCLASS_VIDEO_CONTROL = 0x01

    /** UVC 子类：VideoStreaming */
    private const val SUBCLASS_VIDEO_STREAMING = 0x02

    // context 目前用不到，保留参数以便后续扩展（如读取 UsbDevice 名称/日志落盘）
    @Suppress("UNUSED_PARAMETER")
    fun dump(context: Context, usbManager: UsbManager): String {
        val sb = StringBuilder()
        try {
            val devices = usbManager.deviceList
            sb.append("设备数量: ").append(devices.size).append('\n')
            if (devices.isEmpty()) {
                sb.append("(未检测到任何 USB 设备；请确认平板已开启 OTG/主机模式，且采集卡已插稳)\n")
                return sb.toString()
            }
            for (device in devices.values) {
                appendDevice(sb, usbManager, device)
            }
        } catch (t: Throwable) {
            // 容错：任何异常都不允许把 App 打崩
            sb.append("UsbInventory 异常: ").append(t).append('\n')
        }
        return sb.toString()
    }

    private fun appendDevice(sb: StringBuilder, usbManager: UsbManager, device: UsbDevice) {
        try {
            sb.append('\n').append('[').append(device.deviceName).append("]\n")
            sb.append("  deviceName     : ").append(device.deviceName).append('\n')
            sb.append(
                String.format(
                    Locale.US,
                    "  vid:pid        : %04x:%04x\n",
                    device.vendorId,
                    device.productId
                )
            )
            sb.append(
                String.format(
                    Locale.US,
                    "  deviceClass    : 0x%02x (%s)  sub=0x%02x  proto=0x%02x\n",
                    device.deviceClass,
                    classLabel(device.deviceClass),
                    device.deviceSubclass,
                    device.deviceProtocol
                )
            )
            sb.append("  interfaceCount : ").append(device.interfaceCount).append('\n')
            sb.append("  已授权(hasPermission): ").append(usbManager.hasPermission(device)).append('\n')
            appendSpeedLine(sb, device)

            var uvcInterfaceCount = 0
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i) ?: continue
                if (appendInterface(sb, intf)) uvcInterfaceCount++
            }
            sb.append("  >>> 该设备的 UVC 接口数量: ").append(uvcInterfaceCount).append('\n')
        } catch (t: Throwable) {
            sb.append("  设备 ").append(device.deviceName).append(" 解析异常: ").append(t).append('\n')
        }
    }

    /**
     * 轻量 USB 速率探测：复用 [SysfsDescriptorReader] 定位到 sysfs 目录，
     * 再读取同目录的 speed 文件（值为 480 / 5000 / 10000 这类 Mbps 数字）。
     * 任何一步失败都只打印一行说明（含精确原因），绝不抛异常。
     */
    private fun appendSpeedLine(sb: StringBuilder, device: UsbDevice) {
        val located = try {
            SysfsDescriptorReader.locate(device)
        } catch (t: Throwable) {
            sb.append("  USB 速率       : 未知（sysfs 定位异常: ").append(t).append("）\n")
            return
        }
        if (located is SysfsDescriptorReader.Located.Failed) {
            sb.append("  USB 速率       : 未知（")
                .append(SysfsDescriptorReader.describe(located.reading))
                .append("）\n")
            return
        }
        val dir = (located as SysfsDescriptorReader.Located.Found).dir
        val speed = SysfsDescriptorReader.readSpeed(dir)
        if (speed == null) {
            sb.append("  USB 速率       : 未知（").append(dir)
                .append("/speed 不可读）\n")
            return
        }
        sb.append("  USB 速率       : ").append(speed)
            .append(" Mbps (").append(speedLabel(speed)).append(")\n")
    }

    private fun speedLabel(speed: String): String = when (speed) {
        "1.5" -> "USB 1.0 Low-Speed"
        "12" -> "USB 1.1 Full-Speed"
        "480" -> "USB 2.0 High-Speed — 无法承载 1080p60 未压缩 YUV"
        "5000" -> "USB 3.2 Gen 1 / SuperSpeed"
        "10000" -> "USB 3.2 Gen 2 / SuperSpeed+"
        "20000" -> "USB 3.2 Gen 2x2"
        else -> "未知速率等级"
    }

    /** 返回该接口是否为 UVC 接口 */
    private fun appendInterface(sb: StringBuilder, intf: UsbInterface): Boolean {
        val isVideo = intf.interfaceClass == CLASS_VIDEO
        try {
            sb.append(
                String.format(
                    Locale.US,
                    "  [Interface %d] altSetting=%d class=0x%02x (%s) sub=0x%02x (%s) proto=0x%02x endpoints=%d%s\n",
                    intf.id,
                    intf.alternateSetting,
                    intf.interfaceClass,
                    classLabel(intf.interfaceClass),
                    intf.interfaceSubclass,
                    videoSubclassLabel(intf.interfaceClass, intf.interfaceSubclass),
                    intf.interfaceProtocol,
                    intf.endpointCount,
                    if (isVideo) "   <== UVC" else ""
                )
            )
            for (e in 0 until intf.endpointCount) {
                appendEndpoint(sb, intf.getEndpoint(e), e)
            }
        } catch (t: Throwable) {
            sb.append("    接口解析异常: ").append(t).append('\n')
        }
        return isVideo
    }

    private fun appendEndpoint(sb: StringBuilder, ep: UsbEndpoint?, index: Int) {
        if (ep == null) {
            sb.append("    [Endpoint #").append(index).append("] null\n")
            return
        }
        try {
            val rawAddress = ep.address
            sb.append(
                String.format(
                    Locale.US,
                    "    [Endpoint #%d] address=0x%02x dir=%s type=%s maxPacketSize=%d interval=%d\n",
                    index,
                    rawAddress,
                    directionLabel(ep.direction),
                    typeLabel(ep.type),
                    ep.maxPacketSize,
                    ep.interval
                )
            )
        } catch (t: Throwable) {
            sb.append("    Endpoint #").append(index).append(" 解析异常: ").append(t).append('\n')
        }
    }

    private fun classLabel(clazz: Int): String = when (clazz) {
        0x00 -> "PerInterface"
        0x01 -> "Audio"
        0x02 -> "CDC/Comm"
        0x03 -> "HID"
        0x05 -> "Physical"
        0x06 -> "Image"
        0x07 -> "Printer"
        0x08 -> "MassStorage"
        0x09 -> "Hub"
        0x0A -> "CDC-Data"
        0x0B -> "SmartCard"
        0x0E -> "Video(UVC)"
        0x0F -> "PersonalHealthcare"
        0x10 -> "AudioVideo"
        0xDC -> "Diagnostic"
        0xE0 -> "Wireless"
        0xEF -> "Miscellaneous"
        0xFE -> "AppSpecific"
        0xFF -> "VendorSpecific"
        else -> "Unknown"
    }

    private fun videoSubclassLabel(clazz: Int, subclass: Int): String {
        if (clazz != CLASS_VIDEO) return "-"
        return when (subclass) {
            SUBCLASS_VIDEO_CONTROL -> "VideoControl"
            SUBCLASS_VIDEO_STREAMING -> "VideoStreaming"
            0x03 -> "VideoInterfaceCollection"
            else -> "Unknown"
        }
    }

    private fun directionLabel(direction: Int): String = when (direction) {
        UsbConstants.USB_DIR_IN -> "IN"
        UsbConstants.USB_DIR_OUT -> "OUT"
        else -> "0x%02x".format(Locale.US, direction)
    }

    private fun typeLabel(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        else -> "Unknown($type)"
    }
}