package dev.padlink.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import java.util.Locale

/**
 * Camera2 外接（external）摄像头探测。
 *
 * 目的：确认系统 Camera2 是否把 USB 采集卡暴露成一个可用的摄像头。
 * 如果能枚举到 LENS_FACING_EXTERNAL，就可以直接用 Camera2 采集；
 * 否则必须走 libusb / libuvc 自建 UVC 采集。
 */
object ExternalCameraProbe {

    fun dump(context: Context): String {
        val sb = StringBuilder()
        val externalIds = ArrayList<String>()
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            sb.append("Camera2 摄像头数量: ").append(ids.size).append('\n')
            if (ids.isEmpty()) {
                sb.append("(CameraManager.getCameraIdList() 返回空)\n")
            }
            for (id in ids) {
                appendCamera(sb, manager, id, externalIds)
            }
        } catch (t: Throwable) {
            sb.append("Camera2 探测异常: ").append(t).append('\n')
        }

        sb.append('\n')
        if (externalIds.isEmpty()) {
            sb.append(">>> 结论: Camera2 未暴露外接摄像头，必须走 libusb/libuvc 路径\n")
        } else {
            sb.append(">>> 结论: Camera2 暴露了外接摄像头 id=")
                .append(externalIds.joinToString(","))
                .append('\n')
        }
        return sb.toString()
    }

    private fun appendCamera(
        sb: StringBuilder,
        manager: CameraManager,
        id: String,
        externalIds: MutableList<String>
    ) {
        try {
            val ch = manager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            sb.append("\n[Camera ").append(id).append("]\n")
            sb.append("  LENS_FACING = ").append(facingName(facing))
                .append(" (").append(facing).append(")\n")
            sb.append("  INFO_SUPPORTED_HARDWARE_LEVEL = ").append(levelName(level))
                .append(" (").append(level).append(")\n")

            // 只对外接摄像头做详细能力探测
            if (facing != CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return
            }
            externalIds.add(id)

            appendStreamConfig(sb, ch)
            appendAeFpsRanges(sb, ch)
        } catch (t: Throwable) {
            sb.append("  摄像头 ").append(id).append(" 探测异常: ").append(t).append('\n')
        }
    }

    private fun appendStreamConfig(sb: StringBuilder, ch: CameraCharacteristics) {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            sb.append("  SCALER_STREAM_CONFIGURATION_MAP: null（无法获取输出尺寸）\n")
            return
        }
        val sizes: Array<Size>? = try {
            map.getOutputSizes(SurfaceTexture::class.java)
        } catch (t: Throwable) {
            sb.append("  getOutputSizes 异常: ").append(t).append('\n')
            null
        }
        sb.append("  SurfaceTexture 支持的输出尺寸: ").append(sizes?.size ?: 0).append(" 个\n")
        sizes
            ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height.toLong() })
            ?.forEach { size ->
                val frameDurationNanos = try {
                    map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                } catch (t: Throwable) {
                    0L
                }
                val maxFps = if (frameDurationNanos > 0L) 1e9 / frameDurationNanos.toDouble() else 0.0
                val tag = if (size.width == 1920 && size.height == 1080) "   <== 1080p" else ""
                sb.append(
                    String.format(
                        Locale.US,
                        "    - %dx%d  minFrameDuration=%dns  理论最大 %.2ffps%s\n",
                        size.width,
                        size.height,
                        frameDurationNanos,
                        maxFps,
                        tag
                    )
                )
            }
    }

    private fun appendAeFpsRanges(sb: StringBuilder, ch: CameraCharacteristics) {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (ranges == null) {
            sb.append("  CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES: (不存在)\n")
            return
        }
        sb.append("  CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES: ").append(ranges.size).append(" 项\n")
        for (r in ranges) {
            sb.append("    - ").append(r.lower).append(" - ").append(r.upper).append(" fps\n")
        }
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        null -> "NULL"
        else -> "UNKNOWN"
    }

    private fun levelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        null -> "NULL"
        else -> "UNKNOWN"
    }
}