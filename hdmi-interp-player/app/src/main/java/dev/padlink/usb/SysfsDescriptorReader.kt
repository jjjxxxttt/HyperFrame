package dev.padlink.usb

import android.hardware.usb.UsbDevice
import java.io.File
import java.util.Locale

/**
 * sysfs 免授权描述符通道。
 *
 * 系统在 /sys/bus/usb/devices/ 下为每个 USB 设备建目录（目录名是「总线-端口」，如 2-2），
 * 目录内的 idVendor / idProduct 是十六进制小写字符串（如 345f），descriptors 是该设备
 * 完整的配置描述符二进制流（与 UsbDeviceConnection.getRawDescriptors() 的内容一致）。
 *
 * 走这条路径完全不需要 UsbManager 授权，是 requestPermission() 弹窗失效时的备用取数通道。
 * 所有 IO 都做了 try/catch：任何失败都只是返回 [Reading] 里的失败分支，绝不抛异常。
 */
object SysfsDescriptorReader {

    /** sysfs 里的 USB 设备目录（只读） */
    const val SYSFS_USB_DEVICES = "/sys/bus/usb/devices"

    /** 读取结果 */
    sealed class Reading {

        /** 成功：dir 是匹配到的 sysfs 目录名（形如 2-2） */
        class Ok(val dir: String, val bytes: ByteArray) : Reading()

        /** /sys/bus/usb/devices 本身不可用 */
        class BaseUnreadable(val reason: String) : Reading()

        /**
         * 列到了目录但没有一个同时匹配 vid/pid。
         * @param scanned 遍历到的目录总数
         * @param readable 其中成功读出 idVendor 内容的目录数
         */
        class NoMatch(
            val vid: String,
            val pid: String,
            val scanned: Int,
            val readable: Int
        ) : Reading() {
            val reason: String
                get() = if (scanned > 0 && readable == 0) {
                    "未匹配到 idVendor=$vid idProduct=$pid；已扫描 $scanned 个目录，" +
                        "但成功读出 idVendor 的只有 0 个 —— 属性文件全部不可读，" +
                        "基本可以断定是 SELinux 拦截（App 无权读取 $SYSFS_USB_DEVICES/*/idVendor）"
                } else {
                    "未匹配到 idVendor=$vid idProduct=$pid；已扫描 $scanned 个目录，" +
                        "其中 $readable 个可读出 idVendor"
                }
        }

        /** 目录匹配上了，但 descriptors 文件读不到 */
        class DescriptorsUnreadable(val dir: String, val reason: String) : Reading()
    }

    /** 目录定位结果 */
    sealed class Located {
        class Found(val dir: String) : Located()
        class Failed(val reading: Reading) : Located()
    }

    /**
     * 尝试从 sysfs 读取该设备完整的配置描述符字节流。
     * 完全不需要 UsbManager 授权。
     */
    fun read(device: UsbDevice): Reading {
        return when (val located = locate(device)) {
            is Located.Failed -> located.reading

            is Located.Found -> {
                val file = File(File(SYSFS_USB_DEVICES, located.dir), "descriptors")
                val exists = file.exists()
                val isFile = file.isFile
                val canRead = file.canRead()
                if (!exists || !isFile || !canRead) {
                    return Reading.DescriptorsUnreadable(
                        located.dir,
                        "存在=$exists isFile=$isFile canRead=$canRead（目录已匹配，" +
                            "疑似 SELinux 仅放行 idVendor/idProduct/speed 而拒绝 descriptors）"
                    )
                }
                try {
                    // 二进制描述符流，必须用 readBytes，绝不能用 readText
                    val bytes = file.readBytes()
                    if (bytes.isEmpty()) {
                        Reading.DescriptorsUnreadable(
                            located.dir,
                            "readBytes() 成功但读到的长度为 0 字节"
                        )
                    } else {
                        Reading.Ok(located.dir, bytes)
                    }
                } catch (t: Throwable) {
                    Reading.DescriptorsUnreadable(
                        located.dir,
                        "readBytes() 抛异常: $t（存在=$exists isFile=$isFile canRead=$canRead）"
                    )
                }
            }
        }
    }

    /** 定位设备的 sysfs 目录（目录名形如 2-2）；失败时返回带诊断信息的 [Reading] 失败分支 */
    fun locate(device: UsbDevice): Located {
        val base = File(SYSFS_USB_DEVICES)
        val exists = try {
            base.exists()
        } catch (t: Throwable) {
            false
        }
        val isDir = try {
            base.isDirectory
        } catch (t: Throwable) {
            false
        }
        val canRead = try {
            base.canRead()
        } catch (t: Throwable) {
            false
        }
        if (!exists || !isDir || !canRead) {
            return Located.Failed(
                Reading.BaseUnreadable(
                    "$SYSFS_USB_DEVICES 不可用: exists=$exists isDirectory=$isDir canRead=$canRead"
                )
            )
        }

        val dirs = try {
            base.listFiles()
        } catch (t: Throwable) {
            return Located.Failed(
                Reading.BaseUnreadable(
                    "$SYSFS_USB_DEVICES 的 listFiles() 抛异常: $t" +
                        " (exists=$exists isDirectory=$isDir canRead=$canRead)"
                )
            )
        }
        if (dirs == null) {
            return Located.Failed(
                Reading.BaseUnreadable(
                    "$SYSFS_USB_DEVICES 的 listFiles() 返回 null" +
                        " (exists=$exists isDirectory=$isDir canRead=$canRead，" +
                        "疑似 SELinux 拒绝 readdir)"
                )
            )
        }

        val wantVid = String.format(Locale.US, "%04x", device.vendorId)
        val wantPid = String.format(Locale.US, "%04x", device.productId)
        var scanned = 0
        var readable = 0
        try {
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                scanned++
                // 接口目录（如 2-2:1.0）没有 idVendor/idProduct，读不到就跳过
                val vid = readText(File(dir, "idVendor")) ?: continue
                readable++
                val pid = readText(File(dir, "idProduct")) ?: continue
                if (!vid.equals(wantVid, ignoreCase = true)) continue
                if (!pid.equals(wantPid, ignoreCase = true)) continue
                return Located.Found(dir.name)
            }
        } catch (t: Throwable) {
            return Located.Failed(
                Reading.BaseUnreadable(
                    "遍历 $SYSFS_USB_DEVICES 异常: $t" +
                        " (已扫描 $scanned 个目录，可读 idVendor 的 $readable 个)"
                )
            )
        }
        return Located.Failed(Reading.NoMatch(wantVid, wantPid, scanned, readable))
    }

    /** 读取同目录的 speed 文件（值为 480 / 5000 这类 Mbps 数字）；失败返回 null */
    fun readSpeed(dir: String): String? = readText(File(File(SYSFS_USB_DEVICES, dir), "speed"))

    /** 把结果渲染成一行可直接打印的诊断文案 */
    fun describe(reading: Reading): String = when (reading) {
        is Reading.Ok -> "OK（目录 ${reading.dir}，${reading.bytes.size} 字节）"
        is Reading.BaseUnreadable -> reading.reason
        is Reading.NoMatch -> reading.reason
        is Reading.DescriptorsUnreadable -> "目录 ${reading.dir} 匹配成功，但 $SYSFS_USB_DEVICES/" +
            "${reading.dir}/descriptors 读取失败: ${reading.reason}"
    }

    /** 读取 sysfs 单行文本；任何失败都返回 null，不抛异常 */
    private fun readText(file: File): String? = try {
        if (!file.isFile || !file.canRead()) {
            null
        } else {
            val text = file.readText().trim()
            if (text.isEmpty()) null else text.lineSequence().firstOrNull()
        }
    } catch (t: Throwable) {
        null
    }
}