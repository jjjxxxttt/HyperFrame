package dev.padlink.uvc

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.padlink.util.LogExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UVC 取流原型页面（纯代码构建界面，风格与 MainActivity 一致）。
 *
 * 只回答一个问题：纯 Kotlin 的 USB API 能不能稳定跑到 248 MB/s（1080p60 YUY2）。
 * 池大小（并发排队的 UsbRequest 数量）是最关键的变量，因此做成一行按钮，方便在平板上
 * 直接扫 8 / 16 / 32 / 64。
 */
class CaptureTestActivity : Activity(), UvcStreamProbe.Listener {

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var poolLabel: TextView

    /** 当前选中的 request 池大小（8/16/32/64 一键切换） */
    private var poolSize = 32

    private var probe: UvcStreamProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("UVC 取流 v" + appVersionName())
        setContentView(buildContentView())
        appendLog("就绪：确认采集卡已插好且已在主界面完成 USB 授权，然后点「开始取流测试」\n")
    }

    override fun onDestroy() {
        // 页面销毁必须停掉取流线程，否则会留下持有连接的后台线程
        probe?.stop()
        probe = null
        super.onDestroy()
    }

    // ==================== 界面（纯代码，风格与 MainActivity 一致） ====================

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // Android 15（targetSdk 35）强制边到边显示，不处理 insets 时顶部会被状态栏压住
        applyWindowInsetsPadding(root)

        // 版本横幅：任何一张截图都能立刻确认装的是哪一版
        val banner = TextView(this).apply {
            text = "HyperFrame UVC 取流 v" + appVersionName()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        startButton = Button(this).apply {
            text = "开始取流测试"
            configureButton(this)
            setOnClickListener { startTest() }
        }
        stopButton = Button(this).apply {
            text = "停止"
            configureButton(this)
            isEnabled = false
            setOnClickListener { probe?.stop() }
        }
        val exportButton = Button(this).apply {
            text = "导出日志"
            configureButton(this)
            setOnClickListener { exportLog() }
        }

        // 三个按钮等宽平分整行：宽度按 weight 计算，绝不会被挤出屏幕右侧
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(startButton, weightParams())
            addView(stopButton, weightParams().apply { marginStart = dp(6) })
            addView(exportButton, weightParams().apply { marginStart = dp(6) })
        }

        statusView = TextView(this).apply {
            text = "就绪"
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        // 池大小：池大小是能不能跑到 248 MB/s 的关键变量，做成一键切换便于扫参数
        poolLabel = TextView(this).apply {
            text = "request 池大小: $poolSize（改完点「开始取流测试」生效）"
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val poolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        for (size in POOL_OPTIONS) {
            val b = Button(this).apply {
                text = size.toString()
                configureButton(this)
                setOnClickListener {
                    poolSize = size
                    poolLabel.text = "request 池大小: $poolSize（改完点「开始取流测试」生效）"
                    appendLog(">>> request 池大小已设为 $poolSize\n")
                }
            }
            poolBar.addView(b, weightParams(if (size == POOL_OPTIONS.first()) 0 else dp(6)))
        }

        // 实时统计行：每秒刷新一次
        statsView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), 0, dp(8), dp(8))
            text = "MB/s: -   等效帧率: -   FID 帧率: -"
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(banner)
        root.addView(buttonBar)
        root.addView(statusView)
        root.addView(poolLabel)
        root.addView(poolBar)
        root.addView(statsView)
        root.addView(scroll)
        return root
    }

    /** 等宽按钮参数：width=0 + weight=1，由父行平分宽度 */
    private fun weightParams(startMargin: Int = 0) = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { marginStart = startMargin }

    /** 统一按钮外观：去掉系统默认最小宽度，避免文字撑出屏幕 */
    private fun configureButton(button: Button) {
        button.minWidth = 0
        button.minimumWidth = 0
        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        button.maxLines = 1
        button.setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    /** 给根布局加上系统栏/刘海的内边距（targetSdk 35 在 Android 15 上是强制边到边显示） */
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun appendLog(line: String) {
        logView.append(line)
    }

    // ==================== 取流控制 ====================

    private fun startTest() {
        if (probe?.isRunning == true) {
            statusView.text = "正在取流中，请先停止"
            return
        }
        val usbManager = try {
            getSystemService(Context.USB_SERVICE) as? UsbManager
        } catch (t: Throwable) {
            null
        }
        if (usbManager == null) {
            appendLog("!! UsbManager 不可用\n")
            return
        }
        val device = findUvcDevice(usbManager)
        if (device == null) {
            appendLog("!! 未找到 UVC 设备（interfaceClass=0x0E），请确认采集卡已插稳\n")
            statusView.text = "未找到 UVC 设备"
            return
        }
        if (!usbManager.hasPermission(device)) {
            appendLog(
                "!! 设备未授权: " + device.deviceName +
                    "；请回主界面点「请求 USB 授权」（UVC 设备还需要 CAMERA 权限才会弹窗）\n"
            )
            statusView.text = "设备未授权"
            statusView.setTextColor(Color.RED)
            return
        }

        appendLog(
            String.format(
                Locale.US,
                "\n==== 开始取流测试 @ %s ｜ 池大小=%d ｜ 时长 %d 秒 ====\n",
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                poolSize,
                TEST_DURATION_MS / 1000
            )
        )
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusView.text = "协商中…"
        statusView.setTextColor(Color.DKGRAY)

        val p = UvcStreamProbe(this, device, this)
        probe = p
        p.start(poolSize, TEST_DURATION_MS)
    }

    /** 取第一个含 UVC 接口（class=0x0E）的设备 */
    private fun findUvcDevice(usbManager: UsbManager): UsbDevice? = try {
        usbManager.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i -> d.getInterface(i)?.interfaceClass == 0x0E }
        }
    } catch (t: Throwable) {
        null
    }

    // ==================== UvcStreamProbe.Listener ====================

    override fun onLog(line: String) {
        runOnUiThread { appendLog(line) }
    }

    override fun onStats(stats: UvcStreamProbe.Stats) {
        runOnUiThread {
            statsView.text = String.format(
                Locale.US,
                "MB/s: 瞬时 %.1f ｜ 平均 %.1f (达成 %.1f%%)   等效帧率: %.2f fps   FID 帧率: %.2f fps",
                stats.instantMBps,
                stats.avgMBps,
                stats.achievedRatio * 100,
                stats.equivalentFps,
                stats.fidFps
            )
            statusView.text = String.format(Locale.US, "取流中 %.1fs", stats.elapsedMs / 1000.0)
        }
    }

    override fun onFinished(description: String) {
        runOnUiThread {
            appendLog(">>> 取流会话结束: $description\n")
            startButton.isEnabled = true
            stopButton.isEnabled = false
            probe = null
            statusView.text = "已结束"
            statusView.setTextColor(Color.DKGRAY)
        }
    }

    // ==================== 日志导出（复用公共逻辑） ====================

    private fun exportLog() {
        val text = logView.text?.toString().orEmpty()
        if (text.isBlank()) {
            statusView.text = "日志为空，请先跑一次取流测试"
            return
        }
        val fileName = "hyperframe-uvc-v" + appVersionName() + "-" +
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
        val shared = LogExporter.share(this, uri, fileName)
        statusView.text = "已导出 Download/HyperFrame/$fileName" + if (shared) "" else "（分享面板不可用）"
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    companion object {
        /** 单次取流测试时长：跑满 10 秒自动停止 */
        private const val TEST_DURATION_MS = 10_000L

        /** 可一键切换的 request 池大小 */
        private val POOL_OPTIONS = intArrayOf(8, 16, 32, 64)
    }
}