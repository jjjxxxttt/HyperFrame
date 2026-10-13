package dev.padlink.uvc

/**
 * UVC 取流原型用到的常量（集中定义，便于核对）。
 *
 * **wIndex 约定**：接口级（class-specific）控制请求的 wIndex 采用
 * `wIndex = (unit << 8) | interfaceNumber`：
 * - Linux uvcvideo 驱动在 `uvc_query_ctrl()` 里就是这么拼的（`(unit << 8) | intfnum`），
 *   其 uapi 头 `include/uapi/linux/uvcvideo.h` 的注释同样写明
 *   "bRequest: ..., wIndex: (entity << 8) | interface"；
 * - libuvc 的 `uvc_get_ctrl()` / `uvc_set_ctrl()` 也是
 *   `wIndex = (unit->id << 8) | unit->chain[0]->intf->cur_altsetting->desc.bInterfaceNumber`。
 *
 * 对 VS（VideoStreaming）接口的 PROBE/CONTROL / COMMIT_CONTROL 来说，它不属于任何
 * unit（entity），约定 entity = 0，因此 **wIndex 直接等于 VS 接口号 = 1**。
 */
object UvcConstants {

    // ---------- bmRequestType ----------
    /** Host->Device, Class, Interface（SET 类请求） */
    const val REQ_TYPE_SET_CUR = 0x21

    /** Device->Host, Class, Interface（GET 类请求） */
    const val REQ_TYPE_GET_CUR = 0xA1

    // ---------- bRequest ----------
    const val UVC_SET_CUR = 0x01
    const val UVC_GET_CUR = 0x81
    const val UVC_GET_MIN = 0x82
    const val UVC_GET_MAX = 0x83
    const val UVC_GET_RES = 0x84
    const val UVC_GET_LEN = 0x85
    const val UVC_GET_INFO = 0x86
    const val UVC_GET_DEF = 0x87

    // ---------- VideoStreaming 接口级控制选择器（放在 wValue 高字节） ----------
    const val VS_PROBE_CONTROL = 0x01
    const val VS_COMMIT_CONTROL = 0x02

    // ---------- 目标模式 ----------
    /** VS 接口号（本机 UVC Streaming 接口 = Interface 1） */
    const val VS_INTERFACE_NUMBER = 1

    /** bulk IN 端点地址（Interface 1 上唯一的端点） */
    const val BULK_ENDPOINT_ADDRESS = 0x83

    /** 格式索引 2 = Uncompressed YUY2 */
    const val FORMAT_INDEX_YUY2 = 2

    /** 帧索引 1 = 1920x1080 */
    const val FRAME_INDEX_1080P = 1

    /** 帧间隔单位 100ns：166666 => 60fps */
    const val FRAME_INTERVAL_60FPS = 166666

    const val FRAME_WIDTH = 1920
    const val FRAME_HEIGHT = 1080

    /** 一帧 YUY2 的字节数：1920*1080*2 = 4147200 */
    const val FRAME_BYTES_YUY2 = FRAME_WIDTH * FRAME_HEIGHT * 2

    /** 1080p60 YUY2 的理论带宽上限（字节/秒）：1990.656 Mbps / 8 ≈ 248.8 MB/s（十进制） */
    const val TARGET_BYTES_PER_SEC = 248_832_000L

    /** 设备 VC_HEADER 里上报的 dwClockFrequency = 125000000，probe/commit 结构里要原样回填 */
    const val VC_CLOCK_FREQUENCY = 125_000_000L
}