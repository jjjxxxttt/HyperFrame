package dev.padlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.padlink.camera.ExternalCameraProbe
import dev.padlink.ota.OtaUpdater
import dev.padlink.ui.DebugMode
import dev.padlink.ui.GlassStyle
import dev.padlink.usb.RawDescriptorParser
import dev.padlink.usb.SysfsDescriptorReader
import dev.padlink.usb.UsbInventory
import dev.padlink.util.LogExporter
import dev.padlink.uvc.CaptureTestActivity
import dev.padlink.uvc.PreviewActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 首页（v0.26.0，盖世模拟器风格重包装）：状态卡片 + 主入口大卡 + 隐藏调试层。
 *
 * 普通界面只看到：品牌头部、设备状态卡、「开始播放 / 实时预览」主入口。
 * 取流测试 / 重新扫描 / 请求 USB 授权 / 导出日志 / 检查更新 / 诊断日志全部收进
 * **调试层**（默认 GONE）——长按顶部版本徽标才切换，外人看不到任何调试按钮。
 *
 * 点「开始播放」自动走完 CAMERA/录音运行时权限 + USB 授权，授权确认后自动进播放。
 * 状态卡片回答"现在能不能播"：采集卡是否在位、USB 授权、CAMERA/录音权限、USB 音频输入。
 *
 * 界面全部用代码构建（不写 XML layout），零第三方依赖。
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scanButton: Button
    private lateinit var permissionButton: Button

    // ---- 设备状态卡片行 ----
    private lateinit var cardDevice: TextView
    private lateinit var cardUsbAuth: TextView
    private lateinit var cardCamera: TextView
    private lateinit var cardRecord: TextView
    private lateinit var cardAudioIn: TextView

    /** 广播是否已注册，避免重复注册/注销 */
    private var permissionReceiverRegistered = false
    private var eventReceiverRegistered = false

    /** 本次进程是否由 USB 插入事件（USB_DEVICE_ATTACHED intent-filter）启动 */
    private var launchedFromUsbAttach = false

    /** 首次 onResume 只发起一次扫描（见 onResume 的说明） */
    private var initialRefreshStarted = false

    // ---- 局域网 OTA（v0.24.0）----
    /** 启动自动检查只做一次 */
    private var otaAutoChecked = false

    /** UDP 推送监听线程是否在跑 */
    private var otaPushRunning = false

    /** 是否有下载/安装任务进行中（防重复点击） */
    private var otaBusy = false

    /** 已弹过提示的推送版本号（同一版不反复弹；手动检查不受限） */
    private var lastOtaPromptedCode = -1

    /** onCreate 时若由插入事件启动，先记下说明文字，等 onResume 时再一起显示 */
    private var attachPrefixLog = ""

    /** 等运行时权限结果回来之后要继续执行的动作（见 ensureRuntimePermissions） */
    private var actionAfterPermissions: (() -> Unit)? = null

    /** 调试模式（长按版本徽标切换）：关闭时调试层整体隐藏，普通用户看不到调试按钮 */
    private var debugMode = false

    /** 调试层的全部视图（按钮组/日志卡等），随调试模式整体显隐 */
    private val debugViews = mutableListOf<View>()

    /**
     * 点「开始播放」后发现 USB 未授权：先记住用户想进的模式（true=全屏），
     * 等授权弹窗确认后自动继续进播放，不用用户再点一次。
     */
    private var playAfterUsbGrant: Boolean? = null

    /**
     * USB 授权结果接收器。
     *
     * 该广播由系统 UsbManager 通过我们提供的 PendingIntent 发出；在部分 ROM（含 HyperOS）上
     * 以 RECEIVER_NOT_EXPORTED 注册会收不到，因此这里必须用 RECEIVER_EXPORTED。
     * 代价是任何应用都能向它发广播，所以必须校验来源（见 onReceive 内的 deviceList 校验）。
     */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            try {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device: UsbDevice = getUsbDeviceExtra(intent) ?: run {
                    appendLog(">>> 忽略来源不明的授权广播: device=null\n")
                    return
                }
                // 来源校验：必须是当前真实存在于 deviceList 的设备，避免被外部伪造广播诱骗去打开任意设备
                if (usbManager.deviceList.values.none { it.deviceId == device.deviceId }) {
                    appendLog(">>> 忽略来源不明的授权广播: device=${device.deviceName}\n")
                    return
                }
                val resultLine = String.format(
                    Locale.US,
                    ">>> 收到授权结果: granted=%s  device=%s (%04x:%04x)\n",
                    granted,
                    device.deviceName,
                    device.vendorId,
                    device.productId
                )
                // 这行通过 prefixLog 进入报告顶部，不再单独 append（否则会被 refresh 的整体覆盖擦掉）
                refresh(askPermission = false, prefixLog = resultLine)
                // 「开始播放」入口触发的授权：确认后自动继续进播放，不用用户再点一次
                val pendingPlay = playAfterUsbGrant
                if (pendingPlay != null) {
                    playAfterUsbGrant = null
                    if (granted) {
                        openPreview(pendingPlay)
                    } else {
                        statusView.text = "USB 授权被拒绝：可长按版本徽标开启调试模式后手动授权"
                    }
                }
            } catch (t: Throwable) {
                appendLog(">>> 处理授权广播异常: $t\n")
            }
        }
    }

    /** 设备插拔接收器：系统广播，NOT_EXPORTED 即可 */
    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog(">>> USB 设备变化: ${intent.action}\n")
                    refresh()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("HyperFrame v" + appVersionName())
        // 调试模式状态先取出，buildContentView 里按它决定调试层显隐
        debugMode = DebugMode.isEnabled(this)
        setContentView(buildContentView())
        // v1.0.1 起首页全出血深色：系统栏透明、深色背景画到状态栏后面，
        // 状态栏图标必须改浅色（默认深色图标在深底上不可见）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val eventFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // targetSdk 35 下动态注册广播必须显式声明导出标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 授权结果广播必须 EXPORTED，否则收不到
            registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_EXPORTED)
            permissionReceiverRegistered = true
            registerReceiver(usbEventReceiver, eventFilter, Context.RECEIVER_NOT_EXPORTED)
            eventReceiverRegistered = true
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, permissionFilter)
            permissionReceiverRegistered = true
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbEventReceiver, eventFilter)
            eventReceiverRegistered = true
        }

        // 「由 USB 插入事件启动」只记录事实，扫描推迟到 onResume（见下方说明）
        val attachLine = buildAttachLine(intent)
        if (attachLine.isNotEmpty()) {
            launchedFromUsbAttach = true
            attachPrefixLog = attachLine
        }
        refreshStatusCard()
    }

    /**
     * 首次进入可见状态后才发起扫描与 USB 权限请求。
     *
     * 关键：USB 权限弹窗是由系统弹出的，而 Android 14/15 在发起方 Activity 还不可见时
     * 会直接把该弹窗丢弃。前几版在 onCreate 里起线程立即调 requestPermission()，
     * 正好命中这个时机 —— 请求发出去了，弹窗却从未显示。
     * 因此这里等到窗口完成布局（decorView.post）之后再请求。
     */
    override fun onResume() {
        super.onResume()
        // 局域网 OTA 推送监听：PC 端 ota-server.ps1 一放新包就广播，这里收到即弹更新提示
        startOtaPushListener()
        if (initialRefreshStarted) return
        initialRefreshStarted = true
        window.decorView.post {
            ensureRuntimePermissions {
                refresh(askPermission = true, prefixLog = attachPrefixLog)
                attachPrefixLog = ""
            }
            // 启动时静默查一次内网更新（找不到服务器只写日志，不打扰）
            startOtaAutoCheck()
        }
    }

    /**
     * 确保拿到运行时权限后再执行 [action]。
     *
     * CAMERA 是 USB 授权能否成功的前提：UVC 采集卡属于 USB_CLASS_VIDEO 设备，
     * targetSdk >= 28 时系统要求 App 必须持有 CAMERA 权限，否则
     * UsbUserPermissionManager.requestPermission() 会故意不弹授权框、直接回传拒绝
     * （见 AOSP UsbUserPermissionManager#requestPermission），hasPermission() 对这类
     * 设备也强制要求 CAMERA —— 现象就是"请求发出去了但永远没有弹窗"。
     *
     * RECORD_AUDIO 是声音 v1 的前提：AudioRecord 读取内核挂载的 USB 音频输入。
     */
    private fun ensureRuntimePermissions(action: () -> Unit) {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
            return
        }
        actionAfterPermissions = action
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val action = actionAfterPermissions
        actionAfterPermissions = null
        // 无论授予与否都继续扫描：状态卡片里会写出各权限状态，便于定位
        refreshStatusCard()
        action?.invoke()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 此时 Activity 已可见，可以直接扫描。
        // 插入事件同样必须走一次 requestPermission：USB_DEVICE_ATTACHED 的 intent-filter
        // 通道只负责把设备交给 App，并不授予 UsbManager 访问权限（实测 hasPermission 仍为 false）。
        val line = buildAttachLine(intent)
        if (line.isEmpty()) return
        launchedFromUsbAttach = true
        refresh(askPermission = true, prefixLog = line)
    }

    /** intent 携带 USB 设备时返回写进报告顶部的一行说明，否则返回空串 */
    private fun buildAttachLine(intent: Intent?): String {
        val device = try {
            getUsbDeviceExtra(intent)
        } catch (t: Throwable) {
            null
        } ?: return ""
        return String.format(
            Locale.US,
            ">>> 由 USB 插入事件启动: %s (%04x:%04x)" +
                "  —— 该通道只交付设备、不授予权限，仍需 requestPermission\n",
            device.deviceName,
            device.vendorId,
            device.productId
        )
    }

    override fun onDestroy() {
        otaPushRunning = false
        if (permissionReceiverRegistered) {
            try {
                unregisterReceiver(permissionReceiver)
            } catch (t: Throwable) {
                // 忽略：极端情况下系统已经回收了注册
            }
            permissionReceiverRegistered = false
        }
        if (eventReceiverRegistered) {
            try {
                unregisterReceiver(usbEventReceiver)
            } catch (t: Throwable) {
                // 忽略
            }
            eventReceiverRegistered = false
        }
        super.onDestroy()
    }

    // ==================== 界面（纯代码） ====================

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GlassStyle.windowBackground(this@MainActivity)
        }
        // Android 15（targetSdk 35）强制边到边显示，不处理 insets 时顶部内容会被状态栏压住
        applyWindowInsetsPadding(root)

        // 头部品牌行（盖世主页风格）：Logo 左、版本徽标右。
        // 任何一张截图都能立刻确认装的是哪一版；长按版本徽标 = 调试模式开关。
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "HyperFrame"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
            })
            // 中文名跟牌（v1.1.0 品牌更名）：图标缩写 HF、英文名 HyperFrame、中文名超帧，三处对齐
            addView(TextView(this@MainActivity).apply {
                text = "超帧"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
                setPadding(dp(8), dp(9), 0, 0)
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "v" + appVersionName() + " · code " + appVersionCode()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GlassStyle.pill(this@MainActivity, false)
                setOnLongClickListener {
                    toggleDebugMode()
                    true
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // ---- Hero 大卡（盖世主页风格）：渐变封面 + 主副标题 + 双入口 ----
        val playButton = Button(this).apply {
            text = "开始播放"
            configureEntryButton(this)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setOnClickListener { onPlayClicked(fullscreen = true) }
        }
        val previewButton = Button(this).apply {
            text = "实时预览"
            configureButton(this)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener { onPlayClicked(fullscreen = false) }
        }
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(TextView(this@MainActivity).apply {
                text = "HDMI 采集 · 低延迟直呈"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(GlassStyle.color(GlassStyle.ACCENT))
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = GlassStyle.chip(this@MainActivity, false)
            })
            addView(TextView(this@MainActivity).apply {
                text = "接上采集卡，就是大屏游戏机"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "即插即玩 · 运动补偿插帧 · 4K 高刷档位"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
                setPadding(0, dp(6), 0, dp(16))
            })
            addView(
                playButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                previewButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }
        val playCard = FrameLayout(this).apply {
            background = GlassStyle.heroCard(this@MainActivity)
            addView(View(this@MainActivity).apply {
                background = GlassStyle.heroGlow(this@MainActivity)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(heroContent, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // ---- 功能小卡横排（盖世主页风格）：三张等宽小卡填满首页 ----
        val featuresRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                miniCard(GlassStyle.ACCENT, "直连直呈", "采集帧直接上屏，无串流绕路"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                miniCard(GlassStyle.OK, "插帧引擎", "LSFG / GSFG 运动补偿"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            )
            addView(
                miniCard(GlassStyle.WARN, "高刷档位", "4K · 120/144Hz 自适应"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            )
        }

        statusView = TextView(this).apply {
            text = "就绪"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ---- 调试层（默认隐藏）：日常界面看不到任何调试按钮 ----
        // UVC 取流原型入口（只跳转，不影响本页的诊断流程与日志内容）
        val captureTestButton = Button(this).apply {
            text = "取流测试"
            configureButton(this)
            setOnClickListener {
                try {
                    startActivity(Intent(this@MainActivity, CaptureTestActivity::class.java))
                } catch (t: Throwable) {
                    appendLog(">>> 打开取流测试页失败: $t\n")
                }
            }
        }
        scanButton = Button(this).apply {
            text = "重新扫描"
            configureButton(this)
            setOnClickListener { refresh() }
        }
        permissionButton = Button(this).apply {
            text = "请求 USB 授权"
            configureButton(this)
            setOnClickListener { ensureRuntimePermissions { onRequestPermissionClicked() } }
        }
        // 日志导出常驻主界面（v0.28.0 恢复）：截图会截断/遮挡正文，导出成文件才能拿到
        // 完整内容 —— 卡顿/果冻/断连的定位全靠导出日志，不该藏进调试层
        val exportLogButton = Button(this).apply {
            text = "导出日志"
            configureButton(this)
            setOnClickListener { exportLog() }
        }
        // 局域网 OTA（v0.24.0）：发现 PC 端 ota-server.ps1，有新版本就下载安装；
        // 推送监听会自动弹更新窗，手动检查只是兜底入口
        val updateButton = Button(this).apply {
            text = "检查更新（内网）"
            configureButton(this)
            setOnClickListener { onCheckUpdateClicked() }
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // 日志包进玻璃卡片：与状态卡同质感，整页风格统一
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassStyle.glassCard(this@MainActivity)
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(
                logCard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 按钮排成「等宽」网格：宽度按 weight 平分，绝不会被挤出屏幕右侧
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        val debugGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(buttonRow(captureTestButton, scanButton), rowParams)
            addView(buttonRow(permissionButton, updateButton), rowParams)
            addView(scroll)
        }
        debugViews.add(debugGroup)

        root.addView(banner)
        root.addView(
            playCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(6), dp(16), dp(10)) }
        )
        root.addView(buildStatusCard())
        root.addView(
            featuresRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) }
        )
        root.addView(statusView)
        // 导出日志常驻（不进调试层）：独立一行，永远点得到
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
                addView(buttonRow(exportLogButton, null), rowParams)
            }
        )
        root.addView(debugGroup)
        applyDebugVisibility()
        return root
    }

    /** 播放入口：补完运行时权限与 USB 授权后进预览（授权弹窗确认后自动继续） */
    private fun onPlayClicked(fullscreen: Boolean) {
        ensureRuntimePermissions {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val unauthorized = usbManager.deviceList.values.filter { !usbManager.hasPermission(it) }
            if (unauthorized.isNotEmpty()) {
                playAfterUsbGrant = fullscreen
                statusView.text = "请在系统弹窗里允许 USB 授权，之后自动进入播放"
                onRequestPermissionClicked()
            } else {
                openPreview(fullscreen)
            }
        }
    }

    /** 长按版本徽标：切换调试模式（调试层显隐） */
    private fun toggleDebugMode() {
        debugMode = DebugMode.toggle(this)
        applyDebugVisibility()
        Toast.makeText(
            this,
            if (debugMode) "调试模式已开启：调试按钮与日志已显示" else "调试模式已关闭",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 调试层按调试模式显隐 */
    private fun applyDebugVisibility() {
        for (v in debugViews) {
            v.visibility = if (debugMode) View.VISIBLE else View.GONE
        }
    }

    /** 打开预览/全屏播放页（全屏模式经 EXTRA_FULLSCREEN 传给 PreviewActivity） */
    private fun openPreview(fullscreen: Boolean) {
        try {
            val intent = Intent(this, PreviewActivity::class.java)
                .putExtra(PreviewActivity.EXTRA_FULLSCREEN, fullscreen)
            startActivity(intent)
        } catch (t: Throwable) {
            appendLog(">>> 打开播放页失败: $t\n")
        }
    }

    /** 两个按钮并排、等宽平分整行；right 传 null 表示这一行只放一个全宽按钮 */
    private fun buttonRow(left: Button, right: Button?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) {
                addView(
                    right,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(6) }
                )
            }
        }

    /** 统一按钮外观：玻璃胶囊，去掉系统默认最小宽度，避免文字撑出屏幕 */
    private fun configureButton(button: Button) {
        button.minWidth = 0
        button.minimumWidth = 0
        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        button.maxLines = 1
        button.setPadding(dp(4), dp(10), dp(4), dp(10))
        button.setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
        button.background = GlassStyle.pill(this, false)
    }

    /** 主功能入口按钮：蓝色渐变实心胶囊，比工具按钮大一号 */
    private fun configureEntryButton(button: Button) {
        configureButton(button)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        button.setPadding(dp(4), dp(13), dp(4), dp(13))
        button.setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
        button.background = GlassStyle.pill(this, true)
    }

    /** 按钮可用态视觉：禁用时整体变淡（Button 的默认 disabled 深色在玻璃底上很脏） */
    private fun setEnabledVisual(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    /** 首页功能小卡（盖世主页风格横排三张）：圆点 + 标题 + 一行说明 */
    private fun miniCard(dotHex: String, title: String, desc: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassStyle.glassCard(this@MainActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@MainActivity).apply {
                    background = GlassStyle.dot(this@MainActivity, dotHex)
                }, LinearLayout.LayoutParams(dp(8), dp(8)))
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) })
            })
            addView(TextView(this@MainActivity).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
                setPadding(0, dp(6), 0, 0)
            })
        }

    // ==================== 设备状态卡片 ====================

    /** 设备状态卡片：一眼看清"现在能不能播"（设备 / 授权 / 权限 / 音频输入） */
    private fun buildStatusCard(): LinearLayout {
        cardDevice = cardRow()
        cardUsbAuth = cardRow()
        cardCamera = cardRow()
        cardRecord = cardRow()
        cardAudioIn = cardRow()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GlassStyle.glassCard(this@MainActivity)
        }
        box.addView(
            TextView(this).apply {
                text = "设备状态"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
                setPadding(0, 0, 0, dp(6))
            }
        )
        box.addView(cardDevice)
        box.addView(cardUsbAuth)
        box.addView(cardCamera)
        box.addView(cardRecord)
        box.addView(cardAudioIn)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(
                box,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun cardRow(): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
        setPadding(0, dp(3), 0, dp(3))
    }

    /**
     * 状态行（带彩色圆点的着色文本）："●  标签: 值"。
     * 圆点按状态着色（正常绿 / 异常红 / 无设备灰 / 警示橙），标签白色、值次级灰。
     */
    private fun statusRow(dotHex: String, label: String, value: String): CharSequence {
        val text = "●  $label: $value"
        val span = SpannableString(text)
        span.setSpan(
            ForegroundColorSpan(GlassStyle.color(dotHex)),
            0, 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 标签段（含冒号）用主文字色突出
        span.setSpan(
            ForegroundColorSpan(GlassStyle.color(GlassStyle.TEXT_PRIMARY)),
            3, 3 + label.length + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    /** 刷新状态卡片（授权/权限/插拔/扫描后都要调；必须在 UI 线程） */
    private fun refreshStatusCard() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val uvc = usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }
            cardDevice.text = if (uvc != null) {
                statusRow(
                    GlassStyle.OK, "采集卡",
                    String.format(Locale.US, "%s  (%04x:%04x)", uvc.deviceName, uvc.vendorId, uvc.productId)
                )
            } else {
                statusRow(GlassStyle.TEXT_TERTIARY, "采集卡", "未检测到（插好采集卡后自动刷新）")
            }
            cardUsbAuth.text = when {
                uvc == null -> statusRow(GlassStyle.TEXT_TERTIARY, "USB 授权", "—（无设备）")
                usbManager.hasPermission(uvc) -> statusRow(GlassStyle.OK, "USB 授权", "已授权")
                else -> statusRow(GlassStyle.ERROR, "USB 授权", "未授权（点「请求 USB 授权」）")
            }
        } catch (t: Throwable) {
            cardDevice.text = statusRow(GlassStyle.WARN, "采集卡", "状态未知: $t")
            cardUsbAuth.text = statusRow(GlassStyle.WARN, "USB 授权", "未知")
        }
        val camLabel = runtimePermissionLabel(Manifest.permission.CAMERA)
        cardCamera.text = statusRow(
            if (camLabel == "已授予") GlassStyle.OK else GlassStyle.WARN,
            "CAMERA 权限", "$camLabel（UVC USB 授权的前置条件）"
        )
        val recLabel = runtimePermissionLabel(Manifest.permission.RECORD_AUDIO)
        cardRecord.text = statusRow(
            if (recLabel == "已授予") GlassStyle.OK else GlassStyle.WARN,
            "录音权限", "$recLabel（声音的前置条件）"
        )
        val audioIn = findUsbAudioInput()
        cardAudioIn.text = if (audioIn != null) {
            statusRow(GlassStyle.OK, "USB 音频输入", "可用（${audioIn.productName}）")
        } else {
            statusRow(GlassStyle.WARN, "USB 音频输入", "未检测到（声音需要内核 USB 音频驱动挂载，详见日志）")
        }
    }

    private fun runtimePermissionLabel(permission: String): String = try {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "已授予"
        } else {
            "未授予"
        }
    } catch (t: Throwable) {
        "未知: $t"
    }

    /** 系统里的 USB 音频输入设备（采集卡 UAC 经内核 snd-usb-audio 挂载后才会出现） */
    private fun findUsbAudioInput(): AudioDeviceInfo? = try {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 给根布局加上系统栏/刘海的内边距。
     *
     * targetSdk 35 在 Android 15 上会被强制边到边显示，此时系统不会自动让开状态栏，
     * 顶部那一行按钮会被状态栏压住（这也是之前"顶栏遮挡正文首行"的原因）。
     */
    private fun applyWindowInsetsPadding(root: LinearLayout) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ==================== 局域网 OTA（v0.24.0）====================

    /**
     * 「检查更新」：UDP 广播发现 PC 端 ota-server.ps1 → 拉 version.json →
     * 有新版本弹「下载安装」，没有就明说已是最新；连不上服务器引导手动填地址。
     */
    private fun onCheckUpdateClicked() {
        if (otaBusy) {
            appendLog(">>> [更新] 已有更新任务在进行中\n")
            return
        }
        appendLog(">>> [更新] 正在发现局域网更新服务器（UDP 广播 ${OtaUpdater.DISCOVER_PORT} 端口）…\n")
        statusView.text = "正在检查更新…"
        Thread {
            var base = OtaUpdater.discoverServer()
            if (base != null) {
                OtaUpdater.rememberServer(this, base)
            } else {
                base = OtaUpdater.savedServer(this)
            }
            val b = base
            val info = if (b != null) OtaUpdater.fetchServerInfo(b) else null
            runOnUiThread {
                statusView.text = "就绪"
                if (b != null && info != null) {
                    if (info.versionCode > appVersionCode()) {
                        promptUpdate(b, info, "检查更新")
                    } else {
                        appendLog(
                            ">>> [更新] 已是最新：本机 v${appVersionName()}（code ${appVersionCode()}），" +
                                "服务器 v${info.versionName}\n"
                        )
                    }
                } else {
                    appendLog(
                        "!! [更新] 没找到可用的更新服务器。请在 PC 上运行 ota-server.ps1，" +
                            "并与平板连同一个局域网；广播被路由禁掉时可手动填地址\n"
                    )
                    promptManualServer()
                }
            }
        }.start()
    }

    /** 启动时静默查一次内网更新：有新版就弹，没有/找不到服务器只写日志，不打扰 */
    private fun startOtaAutoCheck() {
        if (otaAutoChecked) return
        otaAutoChecked = true
        Thread {
            var base = OtaUpdater.discoverServer()
            if (base != null) {
                OtaUpdater.rememberServer(this, base)
            } else {
                base = OtaUpdater.savedServer(this)
            }
            val b = base ?: return@Thread
            val info = OtaUpdater.fetchServerInfo(b) ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                appendLog(">>> [更新] 启动自动检查：服务器 $b ｜ 服务器版本 v${info.versionName}（code ${info.versionCode}）\n")
                if (info.versionCode > appVersionCode()) {
                    promptUpdate(b, info, "内网推送")
                }
            }
        }.start()
    }

    /** UDP 推送监听：PC 端 dist 里出现新 APK 时广播 HYPERFRAME-OTA-NEW，这里收到即弹更新提示 */
    private fun startOtaPushListener() {
        if (otaPushRunning) return
        otaPushRunning = true
        Thread {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(OtaUpdater.PUSH_PORT))
                sock.soTimeout = 1000
                val buf = ByteArray(256)
                while (otaPushRunning) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val code = OtaUpdater.parsePushVersion(text) ?: continue
                        handleOtaPush(code, "http://${pkt.address.hostAddress}:${OtaUpdater.HTTP_PORT}")
                    } catch (t: SocketTimeoutException) {
                        // 心跳超时，回循环再看 otaPushRunning
                    }
                }
                sock.close()
            } catch (t: Throwable) {
                if (otaPushRunning) {
                    runOnUiThread { appendLog("!! [更新] 推送监听启动失败（不影响手动检查更新）: $t\n") }
                }
            }
            otaPushRunning = false
        }.start()
    }

    /** 收到推送广播：版本比本机新才提示（同版去重），拉一次服务器信息后弹窗 */
    private fun handleOtaPush(code: Int, base: String) {
        if (code <= appVersionCode() || code <= lastOtaPromptedCode) return
        Thread {
            val info = OtaUpdater.fetchServerInfo(base) ?: return@Thread
            if (info.versionCode <= appVersionCode()) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed || otaBusy) return@runOnUiThread
                appendLog(">>> [内网推送] PC 端推送了新版本 v${info.versionName}（code ${info.versionCode}）\n")
                promptUpdate(base, info, "内网推送")
            }
        }.start()
    }

    /** 手动填服务器地址（UDP 广播被网络禁掉时的兜底通道） */
    private fun promptManualServer() {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply {
            hint = "http://192.168.1.100:${OtaUpdater.HTTP_PORT}"
            setText(OtaUpdater.savedServer(this@MainActivity) ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("手动填写更新服务器")
            .setMessage("没收到 UDP 广播时，直接填 PC 端 ota-server.ps1 启动时显示的地址")
            .setView(input)
            .setPositiveButton("检查") { _, _ ->
                val base = input.text.toString().trim()
                if (base.isEmpty()) return@setPositiveButton
                OtaUpdater.rememberServer(this, base)
                Thread {
                    val info = OtaUpdater.fetchServerInfo(base)
                    runOnUiThread {
                        when {
                            info == null -> appendLog("!! [更新] 服务器 $base 无响应或 version.json 不合法\n")
                            info.versionCode > appVersionCode() -> promptUpdate(base, info, "手动检查")
                            else -> appendLog(">>> [更新] 已是最新：服务器 v${info.versionName}\n")
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 有新版本时的确认弹窗（手动检查永远弹；推送/自动检查同版去重） */
    private fun promptUpdate(base: String, info: OtaUpdater.OtaInfo, source: String) {
        if (isFinishing || isDestroyed) return
        if (source != "手动检查" && info.versionCode <= lastOtaPromptedCode) return
        lastOtaPromptedCode = info.versionCode
        val msg = StringBuilder()
            .append("当前版本 v").append(appVersionName()).append("（code ").append(appVersionCode()).append("）\n")
            .append("服务器 v").append(info.versionName).append("（code ").append(info.versionCode).append("）\n")
            .append("来源：").append(source)
        if (info.notes.isNotBlank()) {
            msg.append("\n\n").append(info.notes)
        }
        msg.append("\n\n是否下载并安装？")
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v" + info.versionName)
            .setMessage(msg.toString())
            .setPositiveButton("下载安装") { _, _ -> downloadAndInstall(base, info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 下载 APK（sha256 校验）→ 拉起系统安装器 */
    private fun downloadAndInstall(base: String, info: OtaUpdater.OtaInfo) {
        if (otaBusy) return
        otaBusy = true
        appendLog(">>> [更新] 开始下载 ${info.apk} …\n")
        statusView.text = "正在下载更新…"
        Thread {
            try {
                val apk = OtaUpdater.downloadApk(this, base, info) { pct ->
                    runOnUiThread { statusView.text = "正在下载更新… $pct%" }
                }
                runOnUiThread {
                    otaBusy = false
                    statusView.text = "下载完成，拉起系统安装器"
                    appendLog(
                        ">>> [更新] 下载完成: ${apk.absolutePath}\n" +
                            ">>> [更新] 若系统提示不允许安装，请在设置里允许 HyperFrame「安装未知应用」后重试\n"
                    )
                    try {
                        OtaUpdater.installApk(this, apk)
                    } catch (t: Throwable) {
                        appendLog("!! [更新] 拉起安装器失败: $t\n")
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    otaBusy = false
                    statusView.text = "就绪"
                    appendLog("!! [更新] 下载/校验失败: $t\n")
                }
            }
        }.start()
    }

    /** 当前 APK 的 versionName，读失败返回 "?" */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    /** 当前 APK 的 versionCode，读失败返回 -1 */
    private fun appVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (t: Throwable) {
        -1L
    }

    // ==================== 扫描 ====================

    private fun refresh(askPermission: Boolean = true, prefixLog: String = "") {
        statusView.text = "扫描中…"
        statusView.setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
        setEnabledVisual(scanButton, false)

        Thread {
            val text = try {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                // 扫描一开始就把"即将请求授权的设备"写进日志，便于判断弹窗是否真的触发
                val authLog = if (askPermission) requestUsbPermissions(usbManager) else ""
                authLog + buildReport(usbManager)
            } catch (t: Throwable) {
                "扫描失败: $t"
            }
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            runOnUiThread {
                // prefixLog 拼在正文最前面（授权结果/插入事件等即时信息不会被覆盖掉）
                logView.text = prefixLog + text
                updateStatus()
                logView.append(">>> 扫描完成 @ $stamp\n")
                setEnabledVisual(scanButton, true)
            }
        }.start()
    }

    /** 「请求 USB 授权」按钮：只追加日志，不打断已有报告 */
    private fun onRequestPermissionClicked() {
        setEnabledVisual(permissionButton, false)
        Thread {
            val lines = try {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                requestUsbPermissions(usbManager)
            } catch (t: Throwable) {
                ">>> 发起 USB 授权请求失败: $t\n"
            }
            runOnUiThread {
                logView.append(lines)
                updateStatus()
                setEnabledVisual(permissionButton, true)
            }
        }.start()
    }

    /**
     * 把当前日志导出成 txt 文档，落在「下载/HyperFrame/」下，并顺手弹出系统分享面板。
     *
     * MediaStore 写入逻辑已抽到 [LogExporter]（取流测试页也要用）：
     * API 29+ 不需要任何存储权限，而且 insert 返回的 content:// URI 可以直接当附件分享出去，
     * 不必自己搭 FileProvider。
     */
    private fun exportLog() {
        val text = logView.text?.toString().orEmpty()
        if (text.isBlank()) {
            statusView.text = "日志为空，请先重新扫描一次"
            return
        }

        val fileName = "hyperframe-v" + appVersionName() + "-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

        val uri = try {
            LogExporter.writeToDownloads(this, fileName, text)
        } catch (t: Throwable) {
            null
        }
        if (uri == null) {
            statusView.text = "导出失败：无法写入 Download/HyperFrame"
            return
        }

        statusView.text = "已导出 Download/HyperFrame/$fileName"
        // 分享面板打不开不影响导出结果，文件已经在下载目录里了
        if (!LogExporter.share(this, uri, fileName)) {
            statusView.text = "已导出 Download/HyperFrame/$fileName（分享面板不可用）"
        }
    }

    /**
     * 对每个未授权设备发起运行时授权（USB 访问不需要在 Manifest 里声明权限），
     * 同时返回写进日志的说明文字。
     */
    private fun requestUsbPermissions(usbManager: UsbManager): String {
        val sb = StringBuilder()
        val pending = usbManager.deviceList.values.filter { !usbManager.hasPermission(it) }
        if (pending.isEmpty()) {
            sb.append(">>> 所有 USB 设备均已授权，无需弹窗，直接解析\n")
            return sb.toString()
        }

        sb.append(">>> 即将请求 USB 授权设备: ")
        sb.append(
            pending.joinToString(", ") { d ->
                String.format(Locale.US, "%s (%04x:%04x)", d.deviceName, d.vendorId, d.productId)
            }
        )
        sb.append('\n')

        for (device in pending) {
            try {
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
                // requestCode 用 deviceId，保证每台设备一个独立的 PendingIntent；
                // 带 UPDATE_CURRENT 避免复用导致 extras 陈旧。
                //
                // 必须用 FLAG_MUTABLE：系统是在 UsbPermissionActivity 里通过
                // PendingIntent.send(context, code, fillInIntent) 的 fillInIntent 回传
                // EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED 的，而官方文档明确说 immutable 的
                // PendingIntent 会忽略 send() 传入的 fillInIntent —— extras 会被直接丢弃。
                // 若授权仍失败，该改动也不会让情况变差。
                val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val pendingIntent = PendingIntent.getBroadcast(this, device.deviceId, intent, flags)
                usbManager.requestPermission(device, pendingIntent)
            } catch (t: Throwable) {
                sb.append(">>> 请求授权失败: ")
                    .append(device.deviceName)
                    .append(" -> ")
                    .append(t)
                    .append('\n')
            }
        }
        sb.append(">>> 已发起 USB 授权请求，请在系统弹窗中点击「允许」\n")
        return sb.toString()
    }

    /** 顶部状态行：醒目展示授权进度，未授权时用红色 */
    private fun updateStatus() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values
            val total = devices.size
            val granted = devices.count { usbManager.hasPermission(it) }
            statusView.text = "USB 已授权 $granted/$total 台 ｜ 共 $total 台设备"
            statusView.setTextColor(
                if (total > 0 && granted < total) GlassStyle.color(GlassStyle.ERROR)
                else GlassStyle.color(GlassStyle.TEXT_SECONDARY)
            )
        } catch (t: Throwable) {
            statusView.text = "授权状态未知: $t"
            statusView.setTextColor(GlassStyle.color(GlassStyle.ERROR))
        }
        refreshStatusCard()
    }

    private fun appendLog(line: String) {
        runOnUiThread { logView.append(line) }
    }

    // ==================== 报告 ====================

    private fun buildReport(usbManager: UsbManager): String {
        val section1 = UsbInventory.dump(this, usbManager)
        val section2 = dumpRawDescriptors(usbManager)
        val section3 = ExternalCameraProbe.dump(this)
        val section4 = buildSummary(usbManager, section2, section3)

        return buildString {
            append("HyperFrame v").append(appVersionName())
                .append("   (versionCode ").append(appVersionCode()).append(")   ")
                .append(if (launchedFromUsbAttach) "[由USB插入事件启动]" else "[手动启动]")
                .append("\n\n")
            append("==== [1] USB 设备清单 ====\n")
            append(section1).append("\n\n")
            append("==== [2] UVC 原始描述符解析（格式 / 分辨率 / 帧率）====\n")
            append(section2).append("\n\n")
            append("==== [3] Camera2 外接摄像头探测 ====\n")
            append(section3).append("\n\n")
            append("==== [4] 结论摘要 ====\n")
            append(section4)
        }
    }

    /** 对每个已授权设备读取原始描述符并解析 */
    private fun dumpRawDescriptors(usbManager: UsbManager): String {
        val sb = StringBuilder()
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            sb.append("(无 USB 设备，跳过)\n")
            return sb.toString()
        }
        for (device in devices) {
            sb.append(
                String.format(
                    Locale.US,
                    "\n--- %s (%04x:%04x) ---\n",
                    device.deviceName,
                    device.vendorId,
                    device.productId
                )
            )
            if (!usbManager.hasPermission(device)) {
                sb.append("未授权，改走 sysfs 免授权通道\n")
                appendSysfsDescriptors(sb, device)
                continue
            }
            val connection = try {
                usbManager.openDevice(device)
            } catch (t: Throwable) {
                null
            }
            if (connection == null) {
                sb.append("openDevice 返回 null，无法读取原始描述符\n")
                continue
            }
            try {
                sb.append(RawDescriptorParser.parse(device, connection))
            } catch (t: Throwable) {
                sb.append("原始描述符解析异常: ").append(t).append('\n')
                sb.append(RawDescriptorParser.STATUS_UNAVAILABLE).append('\n')
                sb.append(">>> 原因: 解析抛出异常\n")
            } finally {
                try {
                    connection.close()
                } catch (t: Throwable) {
                    // 忽略关闭异常
                }
            }
        }
        return sb.toString()
    }

    /**
     * 免授权的备用通道：直接读 sysfs 的 descriptors 二进制文件（/sys/bus/usb/devices/2-2/descriptors），
     * 内容与 UsbDeviceConnection.getRawDescriptors() 一致，不需要 UsbManager 授权。
     */
    private fun appendSysfsDescriptors(sb: StringBuilder, device: UsbDevice) {
        val reading = try {
            SysfsDescriptorReader.read(device)
        } catch (t: Throwable) {
            sb.append(">>> sysfs 通道失败: 读取异常 ").append(t).append('\n')
            sb.append("未授权，无法读取原始描述符；请在系统弹窗中点「允许」，或用顶栏「请求 USB 授权」重试\n")
            return
        }
        when (reading) {
            is SysfsDescriptorReader.Reading.Ok -> {
                sb.append(">>> 描述符来源: sysfs ")
                    .append(SysfsDescriptorReader.SYSFS_USB_DEVICES).append('/')
                    .append(reading.dir).append("/descriptors (")
                    .append(reading.bytes.size).append(" 字节)\n")
                sb.append(RawDescriptorParser.parseBytes(reading.bytes, reading.dir))
            }

            else -> {
                sb.append(">>> sysfs 通道失败: ")
                    .append(SysfsDescriptorReader.describe(reading))
                    .append('\n')
                sb.append("未授权，无法读取原始描述符；请在系统弹窗中点「允许」，或用顶栏「请求 USB 授权」重试\n")
            }
        }
    }

    /** 第 4 段：给用户直接截图用的结论（区分「未知」与「否」） */
    private fun buildSummary(usbManager: UsbManager, rawText: String, cameraText: String): String {
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (t: Throwable) {
            emptyList<UsbDevice>()
        }
        val unauthorized = try {
            devices.filter { !usbManager.hasPermission(it) }
        } catch (t: Throwable) {
            emptyList<UsbDevice>()
        }
        val hasUvc = devices.any { isUvcDevice(it) }
        // 未授权的 UVC 设备意味着"关键数据没读到"，此时不能下"否"的结论
        val unauthorizedUvc = unauthorized.any { isUvcDevice(it) }

        // 描述符是否真的读到了（由 RawDescriptorParser 输出的状态标记决定，不靠内容反推）
        val descriptorOk = rawText.contains(RawDescriptorParser.STATUS_OK)
        val has1080p60 = rawText.contains("1080p60 可用")
        val camera2External = cameraText.contains(">>> 结论: Camera2 暴露了外接摄像头")

        // 描述符能从 sysfs 免授权通道读到，所以「已成功读取」的结论优先于授权状态：
        // 先看 descriptorOk，避免"确实没有 1080p60"被误报成"未知（存在未授权的 UVC 设备）"
        val mode1080p60 = when {
            descriptorOk && has1080p60 -> "是（详见第 2 节的 >>> 行）"
            descriptorOk -> "否（描述符已读取，确实未发现）"
            unauthorizedUvc -> "未知（存在未授权的 UVC 设备，描述符未读取；请先完成 USB 授权）"
            unauthorized.isNotEmpty() -> "未知（设备未授权，描述符未读取；请先完成 USB 授权）"
            else -> "未知（描述符读取失败，见第 2 节的解析状态行）"
        }

        return buildString {
            if (unauthorized.isNotEmpty()) {
                if (descriptorOk) {
                    append("!! 当前存在未授权设备，但已通过 sysfs 读到描述符，以下结论完整（未授权: ")
                } else {
                    append("!! 当前存在未授权设备，以下结论不完整（未授权: ")
                }
                append(
                    unauthorized.joinToString(", ") { d ->
                        String.format(Locale.US, "%s %04x:%04x", d.deviceName, d.vendorId, d.productId)
                    }
                )
                append("）\n")
            }
            append("CAMERA 权限: ").append(cameraPermissionLabel()).append('\n')
            append("1) UVC 采集卡: ")
            append(if (hasUvc) "是（检测到 interfaceClass=0x0E 的接口）" else "否（未检测到 UVC 接口）")
            append('\n')
            append("2) 1080p60 模式: ").append(mode1080p60).append('\n')
            append("3) Camera2 外接摄像头: ")
            append(if (camera2External) "可用（可直接用 Camera2 采集）" else "不可用（必须走 libusb/libuvc 自建采集）")
            append('\n')
            append("4) 描述符解析状态: ").append(if (descriptorOk) "OK（已成功读取）" else "UNAVAILABLE（未读到）")
            append('\n')
        }
    }

    /** CAMERA 运行时权限状态：UVC 设备缺了它，USB 授权永远拿不到 */
    private fun cameraPermissionLabel(): String = try {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "已授予"
        } else {
            "未授予（这是 UVC 设备 USB 授权不弹窗的直接原因）"
        }
    } catch (t: Throwable) {
        "未知: $t"
    }

    /** 该设备是否含 UVC（Video Class，0x0E）接口 */
    private fun isUvcDevice(device: UsbDevice): Boolean = try {
        (0 until device.interfaceCount).any { i ->
            device.getInterface(i)?.interfaceClass == 0x0E
        }
    } catch (t: Throwable) {
        false
    }

    /** 兼容新旧 API 读取 EXTRA_DEVICE；intent 为 null 时返回 null */
    private fun getUsbDeviceExtra(intent: Intent?): UsbDevice? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.padlink.USB_PERMISSION"

        /** 运行时权限（CAMERA + RECORD_AUDIO）的请求码 */
        private const val REQUEST_PERMISSIONS = 1001
    }
}