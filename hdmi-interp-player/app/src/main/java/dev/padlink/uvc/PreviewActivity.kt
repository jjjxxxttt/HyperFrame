package dev.padlink.uvc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.padlink.audio.AudioBridge
import dev.padlink.lsfg.ExtractResult
import dev.padlink.lsfg.LsfgDirectRenderer
import dev.padlink.lsfg.LsfgPipeline
import dev.padlink.lsfg.LsfgSettings
import dev.padlink.lsfg.ShaderExtractor
import dev.padlink.render.FrameAssembler
import dev.padlink.render.FrameInterpolator
import dev.padlink.render.InterpMode
import dev.padlink.render.MjpegFrameAssembler
import dev.padlink.render.Yuy2FrameAssembler
import dev.padlink.render.Yuy2SurfaceRenderer
import dev.padlink.usb.RawDescriptorParser
import dev.padlink.ui.DebugMode
import dev.padlink.ui.GlassStyle
import dev.padlink.ui.OverlayPanel
import dev.padlink.ui.PerfHudView
import dev.padlink.util.LogExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer

/**
 * 实机预览页：采集卡 → YUY2 整帧装配 → GLES 转色上屏。
 *
 * 与「取流测试」页的区别只在数据去哪：测试页把数据丢掉只算速率，本页把每段传输
 * 喂给装配器、装配成整帧后交给渲染器上屏。协商（claim / probe / commit）完全复用
 * [UvcStreamProbe]，两页走的是同一条已经验证过的通道。
 *
 * v0.18.0 起支持：
 * - **分辨率选项**：从设备描述符结构化解析全部 YUY2 档位（[RawDescriptorParser.extractProfiles]），
 *   按钮行动态生成，点选即按该档位重新协商；
 * - **断流自动恢复**：进游戏后 HDMI 源分辨率/时序一变，采集卡常输出恒定黑场（Y=16 或全 0）
 *   而 USB 流仍在跑。看门狗检测「纯黑场 / 取流停滞」后自动重新协商：先按当前档位重置一次
 *   （排除一次性时序错乱），仍无画面则逐档轮询，全部试完冷却后从头再来；
 * - **延迟基线 v1**：载荷头 PTS/SCR → 采集/装配延迟，提交 → 上屏延迟，进状态栏与日志。
 *
 * v0.19.0 起新增：
 * - **全屏模式**（EXTRA_FULLSCREEN）：沉浸式（隐藏系统栏），点按画面呼出/收起悬浮面板；
 * - **声音 v1**：[dev.padlink.audio.AudioBridge]，采集卡 UAC 音频（内核 snd-usb-audio
 *   挂载后暴露的 USB 音频输入）→ AudioRecord → AudioTrack 转放，随会话启停，可开关。
 *
 * v0.30.0 起新增（盖世风格游戏内 UI）：
 * - **右侧悬浮面板**（[dev.padlink.ui.OverlayPanel]）：深色磨砂面板盖在 HDMI 画面右缘，
 *   左缘图标栏（操作/性能/设置 + 底部独立退出）切页，收纳全部控制（开始/停止、声音、
 *   插帧、复位卡、分辨率、导出日志、调试模式）；点按画面 toggle，面板常驻直到手动收起
 *   （对齐参考 UI，无自动隐藏）；
 * - **画面顶部性能检测 HUD**（[dev.padlink.ui.PerfHudView]）：帧率/源/延迟/丢帧/CPU/内存/
 *   处理器/电池 + 帧率波形，压在画面顶部居中，可在面板「性能」页开关；
 * - **显示通路改为标准 hole-punch**：去掉 setZOrderOnTop(true) —— ZOrderOnTop 的 surface
 *   会盖住自身 bounds 内一切窗口内容，UI 永远压不到画面上。改走 gatherTransparentRegion
 *   透明孔：surface 在窗口层之下，面板/HUD 在窗口层之上正常合成叠加。
 *   **SurfaceView 仍绝不能设任何不透明背景**（v0.16.0 黑屏根因不变）。
 *
 * v0.22.0 起新增：
 * - **MJPEG 通路**：1080p120 / 4K60 的 YUY2 理论带宽（497.7 / 995 MB/s）远超 USB3 实测
 *   上限（~400 MB/s），物理上跑不满 —— 这类档位必须走 MJPEG 压缩流 + 软解码
 *   （[MjpegFrameAssembler]，RGBA 直出）。超带宽的 YUY2 档位标注"(超带宽)"并排到列表末尾；
 * - **恢复链修复**：会话重启不再清掉恢复链状态（v0.21.0 及以前 startSession 会把
 *   recoveryActive/recoveryAttempts 清零，看门狗永远走"第 1 步同档重试"，超带宽档位
 *   就形成"连 5 秒断连"死循环）；USB 错误爆发的档位记入失败清单，轮询直接跳过；
 * - **输出冻结检测**：帧计数在涨但载荷头 PTS 停滞 = 采集卡固件卡住（画面冻在最后一帧），
 *   与取流停滞互补，同样触发自动恢复。
 *
 * v0.23.0 起调整（参考 libuvc/同类 USB 采集 APK 的做法）：
 * - **恢复只做同档重协商，绝不自动换分辨率**（v0.22.0 的逐档轮询观感是"一直不停切换
 *   分辨率"）。重试间隔递增退避 3/5/8/12/20/30 秒，连续 8 次无画面就停止自动恢复、
 *   交回给用户手动换档；
 * - **MJPEG 帧边界改 SOI/EOI 流式扫描**：v0.22.0 按 FID 出帧 + 头扫 SOI/尾扫 EOI，
 *   缓冲攒两帧时会拼成巨帧（实测 3.26MB/帧）导致解码失败黑屏。
 *
 * 注意：预览会话不限时，靠「停止」或退出页面结束；退出时必须先停取流再释放渲染器，
 * 否则 GL 线程会拿着已经失效的 Surface 继续绘制。
 */
class PreviewActivity : Activity(), UvcStreamProbe.Listener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    // 控制按钮只在普通模式的按钮行里创建；全屏模式走悬浮面板，字段保持可空
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var profileBar: LinearLayout? = null

    private var probe: UvcStreamProbe? = null
    private var renderer: Yuy2SurfaceRenderer? = null

    /** 帧装配器：YUY2 直装 / MJPEG 装配+软解，公共接口（看门狗/状态栏统一口径） */
    private var assembler: FrameAssembler? = null

    /** 是否全屏模式（EXTRA_FULLSCREEN）：沉浸式 + 点按呼出控制条 */
    private var fullscreenMode = false

    /** 全屏模式的悬浮面板容器（点按画面 toggle；无自动隐藏，常驻直到手动收起） */
    private var controlPanel: View? = null

    /** 盖世风格右侧悬浮面板（v0.30.0）：全屏模式专属，收纳全部控制 */
    private var overlayPanel: OverlayPanel? = null

    /** 画面顶部性能检测 HUD（v0.30.0）：两种模式都叠加在视频上方 */
    private var perfHud: PerfHudView? = null

    /** 全屏模式的日志浮层（面板显隐时联动让出面板宽度） */
    private var overlayLogBox: View? = null

    /** 声音桥（UAC 音频 → 扬声器），随预览会话启停 */
    private var audioBridge: AudioBridge? = null

    /**
     * 下一次启动会话是否执行硬复位（重新 SET_CONFIGURATION，等效重新插拔采集卡）。
     * v0.24.0：HDMI 源切换分辨率后采集卡固件卡死（bulk 端点整批排队失败）时，
     * 重开设备救不回来，只有"拔插"能救 —— 硬复位就是拔插的软件等价物。
     */
    private var hardResetNext = false

    /** 声音开关（默认开） */
    private var soundEnabled = true

    private var soundButton: Button? = null

    /**
     * 插帧模式（HyperFrame 1.0）：关 / 自动 / LSFG / 经典，短按循环。
     * 自动（默认）= MJPEG 档且 LSFG 就绪时走 LSFG 帧生成，否则走经典 GSFG 插帧。
     */
    private var interpMode = InterpMode.AUTO

    /** 插帧质量档位（清晰/均衡/流畅，长按插帧按钮循环） */
    private var interpQuality = FrameInterpolator.Quality.BALANCED

    /** 抗拖影档位（弱/标准/强，悬浮面板「抗拖影」行循环；热切换不重建 GL 资源） */
    private var interpGhosting = FrameInterpolator.Ghosting.STANDARD

    private var interpButton: Button? = null

    /** LSFG 直连通路（1.0）：MJPEG 档 + LSFG 就绪时接管 surface 直呈（与 EGL 通路互斥） */
    private var lsfgDirect: LsfgDirectRenderer? = null

    /** LSFG 通路是否正在接管 surface */
    private var lsfgActive = false

    /** LSFG 帧生成参数（悬浮面板「插帧」子页驱动） */
    private var lsfgMultiplier = 2
    private var lsfgFlowScale = 1.0f
    private var lsfgTargetFps = 0
    private var lsfgQueueDepth = 4

    /** LSFG 上屏帧率差分估算的上一拍计数/时刻 */
    private var lsfgPosted = 0L
    private var lsfgPostedNs = 0L

    /**
     * LSFG 接管期间的 surface 事件路由：GL 渲染器自己也挂着 SurfaceHolder 回调，
     * 挂起期间它不碰 surface；重绑/解绑输出窗口由这里转发给 LSFG 通路。
     */
    private val surfaceRouter = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (lsfgActive) {
                lsfgDirect?.onSurfaceChanged(holder.surface ?: return, width, height)
            }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (lsfgActive) lsfgDirect?.onSurfaceDestroyed()
        }
    }

    /** 调试模式（长按版本徽标切换，全局持久）：调试按钮/日志区只在调试模式显示 */
    private var debugMode = false

    /** 调试模式才显示的视图（导出日志/复位卡/日志区） */
    private val debugViews = mutableListOf<View>()

    /** 当前装配/渲染管线的尺寸（换档分辨率不同时要重建） */
    private var pipelineWidth = 0
    private var pipelineHeight = 0

    /** 当前管线是不是 MJPEG 通路（同分辨率换格式也要重建） */
    private var pipelineMjpeg = false

    /** 描述符解析出的可用档位（每分辨率取最大帧率），按面积降序 */
    private var profiles: List<StreamProfile> = emptyList()

    /** 当前档位 */
    private var currentProfile: StreamProfile = StreamProfile.DEFAULT_1080P60

    /** 分辨率按钮（档位 → 按钮），换档时更新高亮 */
    private val profileButtons = LinkedHashMap<StreamProfile, Button>()

    /** 最近一次速率快照，和装配/上屏计数拼在同一行状态里展示 */
    private var lastStats: UvcStreamProbe.Stats? = null

    /** 日志文字只保留最近若干行：预览可能跑很久，无上限追加会拖慢 TextView */
    private val logLines = ArrayDeque<String>()

    // ---- 断流自动恢复（看门狗）状态 ----

    /** 自动恢复要切换到的档位：取流会话结束后按它重启；null = 不重启 */
    private var pendingRestart: StreamProfile? = null

    /** 用户主动停止/退出（此时绝不自动重启） */
    private var userStopped = false

    /** 上一次看到新帧的时刻（停滞判定） */
    private var lastProgressNs = 0L

    /** 上一次统计时装配器的帧计数 */
    private var lastFramesSeen = 0L

    /** 连续异常（黑场/停滞/输出冻结）的起始时刻；0 = 当前正常 */
    private var badSinceNs = 0L

    /**
     * 连续正常的起始时刻；0 = 刚刚还在异常。
     * 恢复确认要求**连续健康 [GOOD_HEALTH_NS]**（v0.22.0：以前是"连续 3 拍正常"，
     * 超带宽档位每次重协商后开头 2-3 拍都正常，会把恢复链状态误清掉）。
     */
    private var goodSinceNs = 0L

    /** 是否处于自动恢复轮询中 */
    private var recoveryActive = false

    /** 本次轮询已尝试次数（第 1 次 = 同档位重协商，之后逐档换） */
    private var recoveryAttempts = 0

    /** 轮询一整圈失败后的冷却截止时刻 */
    private var pausedUntilNs = 0L

    /** 冷却重试的延时任务是否已排上（避免重复排多个） */
    private var cooldownRetryScheduled = false

    /** 上一拍的（queue 失败 + request 错误）总数，用来检出"一拍内暴涨"的错误爆发 */
    private var lastErrTotal = -1

    /**
     * 看门狗独立心跳（v0.21.0 根因修复）：每 [WATCHDOG_TICK_MS] tick 一次，
     * **完全不依赖取流线程的 onStats**。
     *
     * v0.20.0 及以前 watchdog 挂在 onStats 节拍上，而 onStats 由取流线程发出 ——
     * 取流线程一旦死亡/卡死（HDMI 源换分辨率时 USB 层出错退出就是这种），心跳跟着断，
     * 看门狗永远不再醒来，画面就冻结在最后一帧、声音照常（UAC 音频是独立通道），
     * 正是实测症状。心跳跑在 UI 线程的 View 消息队列上，与被监控的数据面彻底解耦；
     * 顺带兜底「会话意外结束但恢复链还没接上」的情况。
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val running = probe != null
            if (!running && !recoveryActive && pendingRestart == null) return // 无事可做，心跳退休
            if (running) {
                watchdog()
            } else if (!userStopped && pendingRestart == null && !cooldownRetryScheduled &&
                System.nanoTime() >= pausedUntilNs
            ) {
                triggerRecovery("会话意外结束（心跳兜底）")
            }
            statusView.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullscreenMode = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        if (fullscreenMode) {
            // 全屏播放：去掉标题栏 + 沉浸式（见 enterImmersive）
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setTitle("全屏播放 v" + appVersionName())
        } else {
            setTitle("实时预览 v" + appVersionName())
        }
        // 预览时不要因为息屏把画面停掉
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        debugMode = DebugMode.isEnabled(this)
        setContentView(buildContentView())
        // LSFG 接管期间的 surface 事件路由（GL 渲染器挂起时由它转发重绑/解绑）
        surfaceView.holder.addCallback(surfaceRouter)
        // LSFG 引擎状态：检查着色器缓存，抽取状态变化时同步到悬浮面板。
        // 回调必须先挂上再 refresh：否则 refresh 触发的状态变化会通知到上一个
        // Activity 实例（甚至已销毁）的 lambda，本次变化反而丢掉。
        LsfgPipeline.onStateChanged = { runOnUiThread { syncOverlayPanel() } }
        LsfgPipeline.refresh(this)
        postLog("[LSFG] 引擎状态：${LsfgPipeline.statusDetail()}\n")
        if (fullscreenMode) {
            enterImmersive()
            postLog("全屏播放：点按画面呼出/收起右侧悬浮面板（操作/性能/设置/退出）；顶部为性能检测 HUD，可在面板「性能」页开关\n")
        }

        // 进入预览页就把显示切到同分辨率下最高刷新率（如 120/144Hz）：
        // 60fps 视频配 120Hz 面板可以均摊 vsync 抖动，减少画面顿挫
        applyBestDisplayMode()

        // 从设备描述符解析可用档位并生成分辨率按钮
        loadProfiles()
        // 按当前档位建好装配/渲染管线
        ensurePipeline(currentProfile)

        if (!fullscreenMode) {
            postLog("就绪：确认采集卡已插好、且已在主界面完成 USB 授权，再点「开始预览」\n")
        }
    }

    override fun onDestroy() {
        // 顺序不能反：先停取流（它会停掉 USB 线程，不再有新帧提交），再释放渲染器
        userStopped = true
        pendingRestart = null
        LsfgPipeline.onStateChanged = null // 防止全局回调持有已销毁页面
        statusView.removeCallbacks(watchdogRunnable)
        probe?.stop()
        probe = null
        audioBridge?.stop()
        audioBridge = null
        // MJPEG 装配器有独立解码线程，必须显式释放（否则线程拖到进程退出）
        assembler?.release()
        assembler = null
        // LSFG 直连通路先于 GL 渲染器释放（Vulkan 侧必须先放手里的 surface）
        lsfgDirect?.stop()
        lsfgDirect = null
        lsfgActive = false
        renderer?.release()
        renderer = null
        super.onDestroy()
    }

    // ==================== 全屏模式（沉浸式 + 控制条呼出） ====================

    /** 沉浸式：隐藏系统栏；从边缘划入时只短暂浮现（sticky） */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统栏短暂浮现后焦点回来时重新沉浸（sticky-immersive 的标准做法）
        if (hasFocus && fullscreenMode) enterImmersive()
    }

    /**
     * 点按画面：呼出/收起右侧悬浮面板（v0.30.0，对齐参考 UI —— 无自动隐藏，
     * 面板常驻直到再次点按画面或点面板里的「退出」）。
     */
    private fun togglePanel() {
        val panel = controlPanel ?: return
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        syncOverlayLayout()
    }

    // ==================== 显示模式（120/144Hz） ====================

    /**
     * 把显示切到与当前同分辨率下的最高刷新率模式（如 120/144Hz）。
     *
     * 60fps 的视频源配 120Hz 面板，每一帧恰好占两个显示周期，vsync 抖动被均摊掉，
     * 观感更顺。用 preferredDisplayModeId 而不是 Surface.setFrameRate：
     * 后者只请求合成器选帧率，前者直接切 Display 模式，行为更确定。
     */
    private fun applyBestDisplayMode() {
        try {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val current = display.mode
            val modes = display.supportedModes
            val list = modes.joinToString("  ") {
                String.format(Locale.US, "%dx%d@%.0fHz(id=%d)", it.physicalWidth, it.physicalHeight, it.refreshRate, it.modeId)
            }
            postLog(">>> [显示] 支持的模式: $list\n")
            postLog(
                String.format(
                    Locale.US,
                    ">>> [显示] 当前: %dx%d@%.0fHz\n",
                    current.physicalWidth, current.physicalHeight, current.refreshRate
                )
            )
            val best = modes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null && best.refreshRate > current.refreshRate) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
                postLog(
                    String.format(
                        Locale.US,
                        ">>> [显示] 已切换到 %dx%d@%.0fHz (id=%d)\n",
                        best.physicalWidth, best.physicalHeight, best.refreshRate, best.modeId
                    )
                )
            } else {
                postLog(">>> [显示] 已是同分辨率下最高刷新率，无需切换\n")
            }
        } catch (t: Throwable) {
            postLog("!! [显示] 刷新率切换失败: $t\n")
        }
    }

    // ==================== 分辨率档位（描述符结构化解析） ====================

    /**
     * 从设备原始描述符解析全部 YUY2 档位（这就是"原来的参考分辨率设置"的来源：
     * v0.8.0 起描述符解析报告里列的完整分辨率/帧率列表），生成分辨率按钮。
     *
     * 本页渲染链是 YUY2 专用的，MJPEG 等其它格式的档位只进日志不做按钮。
     */
    private fun loadProfiles() {
        val usbManager = try {
            getSystemService(Context.USB_SERVICE) as? UsbManager
        } catch (t: Throwable) {
            null
        }
        val device = usbManager?.let { findUvcDevice(it) }
        if (usbManager == null || device == null) {
            postLog("!! [分辨率] 未找到 UVC 设备，使用默认 1920x1080@60\n")
            rebuildProfileButtons()
            return
        }
        val all = if (usbManager.hasPermission(device)) {
            val conn = try {
                usbManager.openDevice(device)
            } catch (t: Throwable) {
                null
            }
            try {
                if (conn != null) RawDescriptorParser.extractProfiles(device, conn) else emptyList()
            } finally {
                try {
                    conn?.close()
                } catch (t: Throwable) {
                    // 忽略
                }
            }
        } else {
            postLog("!! [分辨率] 设备未授权，无法读描述符，使用默认 1920x1080@60（授权后重进本页可解析全部档位）\n")
            emptyList()
        }

        // v0.22.0：收 YUY2 + MJPEG 两类（1080p120 / 4K60 的 YUY2 理论带宽 497.7 / 995 MB/s，
        // 超出 USB3 实测 ~400 MB/s 上限，物理上跑不满，必须走 MJPEG 压缩流）；
        // 每个档位的**全部帧率**展开成独立选项；超带宽 YUY2 标"(超带宽)"排到列表末尾。
        val usable = all.filter { it.fourcc.startsWith("YUY2") || it.fourcc.startsWith("YUYV") || it.isMjpeg }
        val expanded = usable
            .flatMap { p -> p.allIntervals.map { iv -> p.copy(interval = iv) } }
            .distinctBy { Triple(it.formatIndex, it.frameIndex, it.interval) }
        profiles = (expanded + if (all.isNotEmpty()) synthesizeExtraProfiles(expanded) else emptyList())
            .distinctBy { Triple(it.formatIndex, it.frameIndex, it.interval) }
            .sortedWith(
                compareByDescending<StreamProfile> { if (it.overBandwidth) 0 else 1 }
                    .thenByDescending { it.width.toLong() * it.height }
                    .thenByDescending { it.fps }
                    .thenBy { if (it.isMjpeg) 1 else 0 } // 同档位 YUY2 在前（画质更好）
            )

        if (all.isNotEmpty()) {
            postLog(
                String.format(
                    Locale.US,
                    ">>> [分辨率] 描述符共解析 %d 档，本页纳入 %d 档（YUY2 %d + MJPEG %d）：\n",
                    all.size,
                    profiles.size,
                    profiles.count { !it.isMjpeg },
                    profiles.count { it.isMjpeg }
                )
            )
            for (p in profiles) {
                postLog(
                    String.format(
                        Locale.US,
                        ">>> [分辨率]   %s  格式索引 %d / 帧索引 %d / %s\n",
                        p.label, p.formatIndex, p.frameIndex, p.fourcc
                    )
                )
            }
        }

        // 默认档位：1080p60 YUY2（非超带宽、兼容性最好）；没有就取第一个非超带宽档
        currentProfile = profiles.firstOrNull {
            it.width == 1920 && it.height == 1080 && !it.isMjpeg && !it.overBandwidth && it.fps in 59.0..61.0
        }
            ?: profiles.firstOrNull { !it.overBandwidth }
            ?: profiles.firstOrNull()
            ?: StreamProfile.DEFAULT_1080P60
        postLog(">>> [分辨率] 当前档位: ${currentProfile.label}\n")
        rebuildProfileButtons()
    }

    /**
     * 补充用户点名的试验档位（1080p120 / 4K60，MJPEG）。
     *
     * 这类高帧率/高分辨率模式很多采集卡**能收但没写进描述符**（固件只列了常用档），
     * probe/commit 的 dwFrameInterval 是协商字段，设备不认时会拒绝或就近适配
     * （GET_CUR 回读能看到它实际接受的值，步骤 8 会打出来）。所以描述符里没有也补上：
     * - 同分辨率有 MJPEG 帧描述符 → 复用它的格式/帧索引，只换帧间隔；
     * - 完全没有 → 记日志说明（YUY2 带宽物理不足，硬试只会 5 秒塌一次）。
     */
    private fun synthesizeExtraProfiles(parsed: List<StreamProfile>): List<StreamProfile> {
        val wanted = listOf(
            Triple(1920, 1080, 120.0),
            Triple(3840, 2160, 60.0)
        )
        val extra = ArrayList<StreamProfile>(wanted.size)
        for ((w, h, fps) in wanted) {
            if (parsed.any { it.isMjpeg && it.width == w && it.height == h && it.fps in (fps - 0.5)..(fps + 0.5) }) {
                continue // 描述符本来就有，不用补
            }
            val tmpl = parsed.firstOrNull { it.isMjpeg && it.width == w && it.height == h }
            if (tmpl == null) {
                postLog(
                    "!! [分辨率] 设备描述符没有 ${w}x${h} 的 MJPEG 帧描述符，" +
                        "${w}x${h}@${fps.toInt()} 无法提供（YUY2 带宽物理不足，不硬试）\n"
                )
                continue
            }
            val interval = (10_000_000.0 / fps).toLong()
            extra += tmpl.copy(interval = interval, allIntervals = listOf(interval))
            postLog(
                ">>> [分辨率] 补充试验档位 ${w}x${h}@${fps.toInt()}(MJPEG·试验)：" +
                    "描述符未列出，设备可能拒绝或就近适配（协商回读可见实际值）\n"
            )
        }
        return extra
    }

    /** 生成/刷新分辨率按钮行（HyperOS 风格 chip：选中实心蓝，未选玻璃） */
    private fun rebuildProfileButtons() {
        val bar = profileBar
        if (bar != null) {
            bar.removeAllViews()
            profileButtons.clear()
            val list = profiles.ifEmpty { listOf(currentProfile) }
            for (p in list) {
                val b = Button(this).apply {
                    text = p.label
                    minWidth = 0
                    minimumWidth = 0
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    maxLines = 1
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener { userSelectProfile(p) }
                }
                profileButtons[p] = b
                bar.addView(
                    b,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                )
            }
        }
        refreshProfileButtons()
    }

    private fun refreshProfileButtons() {
        for ((p, b) in profileButtons) {
            val active = p.width == currentProfile.width &&
                p.height == currentProfile.height &&
                p.formatIndex == currentProfile.formatIndex &&
                p.frameIndex == currentProfile.frameIndex &&
                p.interval == currentProfile.interval
            b.text = p.label
            b.background = GlassStyle.chip(this, active)
            b.setTextColor(
                GlassStyle.color(if (active) GlassStyle.TEXT_PRIMARY else GlassStyle.TEXT_SECONDARY)
            )
        }
        // 悬浮面板的分辨率子页同步同一份档位表（全屏模式没有 chip 按钮行，只走面板）
        val list = profiles.ifEmpty { listOf(currentProfile) }
        val idx = list.indexOfFirst {
            it.width == currentProfile.width &&
                it.height == currentProfile.height &&
                it.formatIndex == currentProfile.formatIndex &&
                it.frameIndex == currentProfile.frameIndex &&
                it.interval == currentProfile.interval
        }.coerceAtLeast(0)
        overlayPanel?.setProfiles(list.map { it.label }, idx)
        overlayPanel?.setCurrentProfileLabel(currentProfile.label)
    }

    /** 点分辨率按钮：手动换档（立即按新档位重新协商） */
    private fun userSelectProfile(p: StreamProfile) {
        currentProfile = p
        refreshProfileButtons()
        userStopped = false
        // 用户主动操作 = 重新给所有档位机会（含之前被标记失败/判不稳定的），恢复链与塌方史从头开始
        clearRecoveryState()
        crashHistoryNs.clear()
        resetWatchdog()
        // 手动换档同样要重协商，音频流会抖动一下，按毛刺处理
        audioBridge?.dampGlitch()
        postLog("\n>>> [分辨率] 手动切换到 ${p.label}（${p.fourcc}，格式索引 ${p.formatIndex} / 帧索引 ${p.frameIndex}）\n")
        val running = probe?.isRunning == true
        if (running) {
            // 先停旧会话，onFinished 里按 pendingRestart 重启
            pendingRestart = p
            probe?.stop()
        } else {
            startSession(p)
        }
    }

    /**
     * 按档位确保装配/渲染管线存在且尺寸匹配。
     * 分辨率变了就必须重建：帧缓冲大小、纹理尺寸、着色器 uTexDim 都跟宽高绑定。
     */
    private fun ensurePipeline(p: StreamProfile) {
        val mjpeg = p.isMjpeg
        if (pipelineWidth == p.width && pipelineHeight == p.height && pipelineMjpeg == mjpeg &&
            renderer != null && assembler != null
        ) {
            return
        }
        // 旧装配器先释放：MJPEG 装配器有独立解码线程，不释放会和新线程抢位图/缓冲
        assembler?.release()
        assembler = null
        renderer?.let {
            surfaceView.holder.removeCallback(it)
            it.release()
        }
        val r = Yuy2SurfaceRenderer(
            p.width, p.height,
            rgbaMode = mjpeg
        ) { line -> postLog(line) }
        // 换档重建渲染器后把插帧模式/质量/抗拖影档位带过去（插帧器随渲染器一起重建，自动清掉旧帧记忆）
        r.interpMode = interpMode
        r.interpQuality = interpQuality
        r.interpGhosting = interpGhosting
        renderer = r
        val holder = surfaceView.holder
        holder.addCallback(r)
        if (lsfgActive) {
            // LSFG 正接管 surface：新渲染器直接挂起，绝不碰 surface（BufferQueue 单生产者）
            r.suspendGl()
        } else {
            // SurfaceHolder 回调只在状态变化那一刻派发给当时已注册的 callback，事后 addCallback
            // 不保证补发 —— 换档时 surface 早已就绪，新 renderer 可能永远收不到 surfaceChanged，
            // GL 线程就不启动 → 换分辨率后黑屏。这里手动补一发；渲染器对同一 surface 的重复
            // surfaceChanged 是幂等的（已在跑同一 surface 会直接 return）。
            val sf = holder.surfaceFrame
            if (holder.surface?.isValid == true && sf.width() > 0 && sf.height() > 0) {
                r.surfaceChanged(holder, 0, sf.width(), sf.height())
            }
        }
        assembler = if (mjpeg) {
            // MJPEG：装配（剥头/FID 分帧/提取 JPEG）→ 独立线程软解 RGBA → 直接上屏
            MjpegFrameAssembler(
                p.width, p.height,
                p.maxVideoFrameSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                onFrame = { frame -> onDecodedFrame(frame) },
                onLog = { line -> postLog(line) }
            )
        } else {
            Yuy2FrameAssembler(
                p.width, p.height,
                onFrame = { frame -> onDecodedFrame(frame) },
                onLog = { line -> postLog(line) }
            )
        }
        pipelineWidth = p.width
        pipelineHeight = p.height
        pipelineMjpeg = mjpeg
        postLog(">>> [管线] 已按 ${p.width}x${p.height}（${if (mjpeg) "MJPEG 解码 → RGBA" else "YUY2 直装"}）重建装配/渲染管线\n")
        if (lsfgActive) {
            lsfgDirect?.stop()
            lsfgDirect = null
            if (shouldUseLsfg()) {
                // 换档联动（M5）：帧生成上下文与宽高绑定，LSFG 会话按新档位整体重建
                postLog(">>> [LSFG] 档位变更，按 ${p.width}x${p.height} 重建帧生成会话\n")
                startLsfgDirect()
            } else {
                // 新档位不再走 LSFG（如换到 YUY2 档）：当场交还 GL 通路
                postLog(">>> [LSFG] 新档位不走 LSFG，交还经典插帧/直绘通路\n")
                exitLsfgMode()
            }
        }
    }

    /**
     * 解码帧路由（MJPEG 解码线程 / YUY2 装配线程调用）：
     * LSFG 接管时喂给直连通路，否则走 GL 渲染器（直绘或 GSFG 插帧）。
     */
    private fun onDecodedFrame(frame: ByteBuffer) {
        val d = lsfgDirect
        if (d != null && lsfgActive) {
            d.submitFrame(frame)
        } else {
            renderer?.submitFrame(frame)
        }
    }

    /** 当前档位/状态是否应走 LSFG 直连通路 */
    private fun shouldUseLsfg(): Boolean {
        // YUY2 档不进 LSFG：pushFrame 只吃 RGBA，CPU 转换的双份开销抵消直连收益
        if (!pipelineMjpeg) return false
        if (!LsfgPipeline.isReady()) return false
        return when (interpMode) {
            InterpMode.OFF, InterpMode.GSFG -> false
            InterpMode.AUTO, InterpMode.LSFG -> true
        }
    }

    /** 插帧模式/档位/引擎状态变化后调用：按需进出 LSFG 通路（幂等） */
    private fun syncInterpPath() {
        val want = shouldUseLsfg()
        if (want == lsfgActive) return
        if (want) enterLsfgMode() else exitLsfgMode()
        syncOverlayPanel()
    }

    /** 进 LSFG 模式：GL 全停 → Vulkan 接管 surface → 起帧生成会话 */
    private fun enterLsfgMode() {
        if (lsfgActive) return
        val r = renderer ?: return
        val holder = surfaceView.holder
        val surface = holder.surface
        val sf = holder.surfaceFrame
        if (surface == null || !surface.isValid || sf.width() <= 0 || sf.height() <= 0) {
            postLog("!! [LSFG] surface 未就绪，暂缓切换 LSFG 通路\n")
            return
        }
        r.suspendGl() // 全停 GL（detach）
        startLsfgDirect()
    }

    /** 在当前 surface 上起 LSFG 直连通路；失败自动回退经典插帧（GSFG）通路 */
    private fun startLsfgDirect() {
        val holder = surfaceView.holder
        val surface = holder.surface
        val sf = holder.surfaceFrame
        if (surface == null || !surface.isValid) {
            renderer?.resumeGl(holder)
            return
        }
        val d = LsfgDirectRenderer(pipelineWidth, pipelineHeight) { line -> postLog(line) }
        val rc = d.start(
            surface, sf.width(), sf.height(),
            ShaderExtractor.cacheDir(this).absolutePath,
            LsfgSettings(lsfgMultiplier, lsfgFlowScale, lsfgTargetFps, lsfgQueueDepth)
        )
        if (rc < 0) {
            d.stop()
            lsfgDirect = null
            lsfgActive = false
            LsfgPipeline.markSessionEnded(failed = true, error = "初始化失败 $rc")
            postLog("!! [LSFG] 启动失败（错误码 $rc），回退经典插帧\n")
            renderer?.resumeGl(holder)
            return
        }
        lsfgDirect = d
        lsfgActive = true
        LsfgPipeline.markActive()
        if (interpMode == InterpMode.LSFG && rc == 1) {
            postLog(">>> [LSFG] 帧生成不可用，本次以直绘直呈运行\n")
        }
    }

    /** 回 GSFG/直绘通路：Vulkan 全停 → GL 重启 */
    private fun exitLsfgMode() {
        val wasActive = lsfgActive
        lsfgActive = false
        lsfgDirect?.stop()
        lsfgDirect = null
        if (wasActive) {
            LsfgPipeline.markSessionEnded(failed = false)
            renderer?.resumeGl(surfaceView.holder)
        }
    }

    /** 帧生成参数变更（悬浮面板「插帧」子页）：倍率/光流需重建会话，节奏参数热更 */
    private fun applyLsfgParams() {
        val d = lsfgDirect
        if (d == null || !lsfgActive) return
        val holder = surfaceView.holder
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        postLog(">>> [LSFG] 参数更新，重建帧生成会话（倍率=${lsfgMultiplier}x 光流=$lsfgFlowScale）\n")
        lsfgDirect = null
        d.stop()
        startLsfgDirect()
    }

    // ==================== 界面（纯代码构建，风格与其它页一致） ====================

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GlassStyle.windowBackground(this@PreviewActivity)
        }
        if (fullscreenMode) {
            buildFullscreenContent(root)
            return root
        }
        // Android 15（targetSdk 35）强制边到边，不处理 insets 顶部会被状态栏压住
        applyWindowInsetsPadding(root)

        // 头部：标题 + 版本玻璃徽标（与首页同风格）
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            addView(TextView(this@PreviewActivity).apply {
                text = "实时预览"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
            })
            addView(TextView(this@PreviewActivity).apply {
                text = "v" + appVersionName() + " · code " + appVersionCode()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GlassStyle.pill(this@PreviewActivity, false)
                // 长按版本徽标 = 调试模式开关（调试按钮/日志区显隐）
                setOnLongClickListener {
                    toggleDebugMode()
                    true
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) })
        }

        val buttonBar = buildButtonBar()
        val profileScroll = buildProfileScroll()
        statusView = buildStatusView()
        statsView = buildStatsView()

        surfaceView = buildSurfaceView()
        // 视频区上叠加性能检测 HUD（v0.30.0，画面顶部居中，与全屏模式一致）
        val videoBox = FrameLayout(this).apply {
            addView(
                surfaceView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        val hud = PerfHudView(this).apply { setVersion("HyperFrame v" + appVersionName()) }
        perfHud = hud
        videoBox.addView(
            hud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(10) }
        )

        val logBox = buildLogBox()
        // 日志区也是调试层的一部分：默认隐藏
        debugViews.add(logBox)
        applyDebugVisibility()

        root.addView(banner)
        root.addView(buttonBar)
        root.addView(profileScroll)
        root.addView(statusView)
        root.addView(statsView)
        root.addView(videoBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(logBox)
        return root
    }

    /** 等宽按钮参数：width=0 + weight=1，由父行平分宽度 */
    private fun weightParams(startMargin: Int = 0) = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { marginStart = startMargin }

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

    /** 按钮可用态视觉：禁用时整体变淡（Button 默认 disabled 深色在玻璃底上很脏） */
    private fun setEnabledVisual(button: Button?, enabled: Boolean) {
        button?.isEnabled = enabled
        button?.alpha = if (enabled) 1f else 0.45f
    }

    /** 控制按钮行（普通模式）：开始 / 停止 / 声音 / 插帧 / 复位卡 / 导出日志 / 返回 */
    private fun buildButtonBar(): LinearLayout {
        val start = Button(this).apply {
            text = if (fullscreenMode) "开始" else "开始预览"
            configureButton(this)
            // 主操作按钮：蓝色渐变实心胶囊
            setTextColor(GlassStyle.color(GlassStyle.TEXT_PRIMARY))
            background = GlassStyle.pill(this@PreviewActivity, true)
            setOnClickListener { onStartClicked() }
        }
        startButton = start
        val stop = Button(this).apply {
            text = "停止"
            configureButton(this)
            setEnabledVisual(this, false)
            setOnClickListener { onStopClicked() }
        }
        stopButton = stop
        val sound = Button(this).apply {
            configureButton(this)
            setOnClickListener { onSoundButtonClicked() }
        }
        soundButton = sound
        val interp = Button(this).apply {
            configureButton(this)
            setOnClickListener { onInterpButtonClicked() }
            // 长按 = 循环插帧质量档位（清晰/均衡/流畅）
            setOnLongClickListener {
                onInterpButtonLongClicked()
                true
            }
        }
        interpButton = interp
        updateSoundButton()
        updateInterpButton()
        // 导出日志常驻控制条（v0.28.0 恢复）：截图会截断/遮挡日志正文，导出文件才能拿到
        // 完整内容 —— 定位卡顿/果冻/断连全靠它，不该藏进调试层
        val exportButton = Button(this).apply {
            text = "导出日志"
            configureButton(this)
            setOnClickListener { exportLog() }
        }
        // 手动硬复位（等效重新插拔采集卡）：HDMI 源切换后采集卡固件卡死时，
        // 用户不用真去拔线，点这个就能把设备整套重建
        val resetCardButton = Button(this).apply {
            text = "复位卡"
            configureButton(this)
            setOnClickListener { onResetCardClicked() }
        }
        val backButton = Button(this).apply {
            text = if (fullscreenMode) "退出" else "返回"
            configureButton(this)
            setOnClickListener { finish() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(start, weightParams())
        bar.addView(stop, weightParams().apply { marginStart = dp(6) })
        bar.addView(sound, weightParams().apply { marginStart = dp(6) })
        bar.addView(interp, weightParams().apply { marginStart = dp(6) })
        bar.addView(resetCardButton, weightParams().apply { marginStart = dp(6) })
        bar.addView(exportButton, weightParams().apply { marginStart = dp(6) })
        bar.addView(backButton, weightParams().apply { marginStart = dp(6) })
        // 调试按钮按调试模式显隐（GONE 的按钮不占等宽格位）；
        // 「导出日志」v0.28.0 起常驻，不再进调试层
        debugViews.add(resetCardButton)
        applyDebugVisibility()
        return bar
    }

    /** 切换调试模式（长按版本徽标触发）并刷新调试视图显隐 */
    private fun toggleDebugMode() {
        debugMode = DebugMode.toggle(this)
        applyDebugVisibility()
        postLog(
            if (debugMode) ">>> 调试模式已开启：调试按钮与日志区已显示\n"
            else ">>> 调试模式已关闭：调试按钮与日志区已隐藏\n"
        )
    }

    /** 调试视图按调试模式显隐 */
    private fun applyDebugVisibility() {
        for (v in debugViews) {
            v.visibility = if (debugMode) View.VISIBLE else View.GONE
        }
    }

    /** 分辨率选项行（档位按钮由 loadProfiles 动态生成；多档时可横向滚动） */
    private fun buildProfileScroll(): HorizontalScrollView {
        profileBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), 0, dp(8), 0)
            addView(
                profileBar,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun buildStatusView(): TextView = TextView(this).apply {
        text = "就绪"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
        setPadding(dp(16), dp(4), dp(16), 0)
    }

    private fun buildStatsView(): TextView = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fullscreenMode) 10f else 11f)
        setTextColor(GlassStyle.color(GlassStyle.TEXT_TERTIARY))
        setPadding(dp(16), dp(2), dp(16), dp(4))
        text = "速率: -   装配: -   上屏: -"
    }

    /**
     * 画面主区域：等比填充，两侧留黑边（渲染器内部算视口）。
     *
     * 【黑屏根因，v0.16.0 修复】这里之前是 setBackgroundColor(Color.BLACK)。
     * SurfaceView 能显示画面，靠的是 gatherTransparentRegion 在窗口层打一个透明孔、
     * 让下方的 surface 露出来；一旦给 SurfaceView 设了不透明背景，这个区域就不再是
     * "透明"的，窗口层的黑色背景直接压在 surface 之上 —— GL 画得再对也看不见，
     * 症状就是 v0.12~0.15 连续四版的"回读全对但屏幕全黑"。
     * 所以：SurfaceView 绝不能设任何不透明背景。
     *
     * 【v0.30.0 显示通路变更】去掉 setZOrderOnTop(true)，走标准 hole-punch：
     * surface 留在窗口层之下靠透明孔显示，悬浮面板/HUD 等窗口内容正常叠在视频上。
     * 之前 ZOrderOnTop 的 surface 会盖住自身 bounds 内一切窗口内容，UI 永远压不到
     * 画面上，这正是盖世式悬浮面板做不了的原因。
     */
    private fun buildSurfaceView(): SurfaceView = SurfaceView(this)

    private fun buildLogBox(): LinearLayout {
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(GlassStyle.color(GlassStyle.TEXT_SECONDARY))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84)
            ).apply { setMargins(dp(16), dp(4), dp(16), dp(12)) }
            background = GlassStyle.glassCard(this@PreviewActivity)
            addView(
                logScroll,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    /**
     * 全屏布局（v0.30.0，盖世风格游戏内 UI）：整屏视频 + 右侧悬浮面板 + 顶部性能 HUD。
     *
     * hole-punch 显示通路（见 buildSurfaceView）：surface 在窗口层之下靠透明孔显示，
     * 面板/HUD/状态/日志都是普通窗口内容，直接叠在视频上。点按画面 toggle 面板
     * （无自动隐藏，对齐参考 UI）。
     */
    private fun buildFullscreenContent(root: LinearLayout) {
        surfaceView = buildSurfaceView().apply {
            setOnClickListener { togglePanel() }
        }
        statusView = buildStatusView()
        statsView = buildStatsView() // 不挂树：updateStatsLine 仍要往它写文本，保留载体
        val logBox = buildLogBox().apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64),
                Gravity.BOTTOM or Gravity.START
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(12)
            }
        }
        overlayLogBox = logBox
        // 日志区也是调试层的一部分：默认隐藏
        debugViews.add(logBox)

        val hud = PerfHudView(this).apply { setVersion("HyperFrame v" + appVersionName()) }
        perfHud = hud

        val panel = OverlayPanel(this, panelListener)
        overlayPanel = panel
        controlPanel = panel

        val scene = FrameLayout(this)
        scene.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        scene.addView(
            hud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(12) }
        )
        scene.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(12)
                marginStart = dp(16)
            }
        )
        scene.addView(logBox)
        // 面板自设 LayoutParams（贴右缘、全高），直接 addView 即可
        scene.addView(panel)
        applyDebugVisibility()
        syncOverlayLayout()

        // 视频要真正边到边（沉浸式），只让 HUD/状态/日志让开刘海与手势条
        scene.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            (hud.layoutParams as? FrameLayout.LayoutParams)?.topMargin = dp(12) + top
            (statusView.layoutParams as? FrameLayout.LayoutParams)?.topMargin = dp(12) + top
            (logBox.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = dp(12) + bottom
            insets
        }

        root.addView(
            scene,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /**
     * 面板显隐联动（全屏模式）：面板占着右缘时，HUD/日志让出面板宽度 ——
     * FrameLayout 的 CENTER_HORIZONTAL 排布是 `left += (leftMargin - rightMargin) / 2`，
     * rightMargin = 面板宽恰好把 HUD 中心挪到「面板左侧可见区」的正中。
     */
    private fun syncOverlayLayout() {
        if (!fullscreenMode) return
        val panelShown = controlPanel?.visibility == View.VISIBLE
        val panelW = if (panelShown) (controlPanel?.layoutParams?.width ?: 0).coerceAtLeast(0) else 0
        (perfHud?.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.rightMargin != panelW) {
                it.rightMargin = panelW
                perfHud?.layoutParams = it
            }
        }
        (overlayLogBox?.layoutParams as? FrameLayout.LayoutParams)?.let {
            val end = panelW + dp(16)
            if (it.rightMargin != end) {
                it.rightMargin = end
                overlayLogBox?.layoutParams = it
            }
        }
    }

    /** 悬浮面板动作 → 既有处理逻辑（v0.30.0，全部复用按钮行的同一套处理器） */
    private val panelListener = object : OverlayPanel.Listener {
        override fun onPanelStartStop(start: Boolean) {
            if (start) onStartClicked() else onStopClicked()
        }

        override fun onPanelSoundToggle() {
            onSoundButtonClicked()
        }

        override fun onPanelInterpToggle() {
            onInterpButtonClicked()
        }

        override fun onPanelInterpQualityCycle() {
            onInterpButtonLongClicked()
        }

        override fun onPanelInterpGhostingCycle() {
            onInterpGhostingCycle()
        }

        override fun onPanelResetCard() {
            onResetCardClicked()
        }

        override fun onPanelExportLog() {
            exportLog()
        }

        override fun onPanelProfileIndex(index: Int) {
            val list = profiles.ifEmpty { listOf(currentProfile) }
            list.getOrNull(index)?.let { userSelectProfile(it) }
        }

        override fun onPanelHudToggle(show: Boolean) {
            perfHud?.visibility = if (show) View.VISIBLE else View.GONE
        }

        override fun onPanelDebugToggle() {
            toggleDebugMode()
        }

        override fun onPanelImportDll() {
            pickLosslessDll()
        }

        override fun onPanelLsfgModeCycle() {
            onInterpButtonClicked()
        }

        override fun onPanelLsfgMultiplierCycle() {
            lsfgMultiplier = if (lsfgMultiplier >= 8) 2 else lsfgMultiplier + 1
            syncOverlayPanel()
            applyLsfgParams()
            postLog(">>> [LSFG] 帧生成倍率 → ${lsfgMultiplier}x（输出帧率 = 源帧率 × 倍率）\n")
        }

        override fun onPanelLsfgFlowCycle() {
            lsfgFlowScale = when (lsfgFlowScale) {
                1.0f -> 0.5f
                0.5f -> 0.25f
                else -> 1.0f
            }
            syncOverlayPanel()
            applyLsfgParams()
            postLog(">>> [LSFG] 光流精度 → ${lsfgFlowLabel()}\n")
        }

        override fun onPanelLsfgFpsCycle() {
            lsfgTargetFps = when (lsfgTargetFps) {
                0 -> 30
                30 -> 60
                60 -> 120
                else -> 0
            }
            syncOverlayPanel()
            applyLsfgParams()
            postLog(">>> [LSFG] 目标帧率上限 → ${lsfgFpsLabel()}\n")
        }

        override fun onPanelLsfgQueueCycle() {
            lsfgQueueDepth = if (lsfgQueueDepth >= 6) 2 else lsfgQueueDepth + 1
            syncOverlayPanel()
            applyLsfgParams()
            postLog(">>> [LSFG] 待处理帧队列深度 → $lsfgQueueDepth\n")
        }

        override fun onPanelExit() {
            finish()
        }
    }

    // ==================== LSFG 着色器导入（SAF） ====================

    /** 打开系统文件选择器，让用户挑 Lossless Scaling 安装目录里的 Lossless.dll */
    private fun pickLosslessDll() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // 各文件管理器对 .dll 的 MIME 报告不一，放宽过滤
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_DLL)
        } catch (t: Throwable) {
            postLog(">>> [LSFG] 打开文件选择器失败：${t.message}\n")
        }
    }

    @Deprecated("Deprecated in Java; SAF 选文件仍走 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_DLL || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            // 持久化读权限：重进 App 想重新抽取时 URI 仍可用（不支持的提供者忽略即可）
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        importLosslessDll(uri)
    }

    /**
     * 后台线程执行「拷贝 → 抽取 → 删副本 → 驱动校验」（DXBC→SPIR-V 转译可能要几秒），
     * 结果回 UI 线程写日志并同步面板状态。
     */
    private fun importLosslessDll(uri: Uri) {
        postLog("\n>>> [LSFG] 导入 Lossless.dll：抽取着色器中（DXBC→SPIR-V 转译 + 驱动校验，可能需要几秒）…\n")
        syncOverlayPanel()
        Thread({
            val result = LsfgPipeline.importDll(this, uri)
            runOnUiThread {
                when (result) {
                    is ExtractResult.Success ->
                        postLog(">>> [LSFG] 着色器抽取并校验成功，LSFG 插帧可用（DLL 副本已删除，只留 SPIR-V 缓存）\n")
                    is ExtractResult.Failure ->
                        postLog(">>> [LSFG] 着色器抽取失败（错误码 ${result.code}）：${result.message}\n")
                }
                syncOverlayPanel()
            }
        }, "lsfg-import").start()
    }

    /** 开始（按钮行与悬浮面板共用） */
    private fun onStartClicked() {
        userStopped = false
        // 用户主动开始 = 恢复链/失败档位记忆全部清零，重新给所有档位机会
        clearRecoveryState()
        resetWatchdog()
        startSession(currentProfile)
    }

    /** 停止（按钮行与悬浮面板共用） */
    private fun onStopClicked() {
        userStopped = true
        pendingRestart = null
        clearRecoveryState()
        probe?.stop()
    }

    /** 手动硬复位（等效重新插拔采集卡）：HDMI 源切换后采集卡固件卡死时的救命键 */
    private fun onResetCardClicked() {
        userStopped = false
        clearRecoveryState()
        resetWatchdog()
        audioBridge?.dampGlitch()
        hardResetNext = true
        postLog("\n>>> [硬复位] 手动复位采集卡（等效重新插拔）：SET_CONFIGURATION 重建设备 + 全套重协商\n")
        val p = probe
        if (p != null && p.isRunning) {
            // 先停旧会话，onFinished 里带 hardResetNext 重启
            pendingRestart = currentProfile
            p.stop()
        } else {
            hardResetNext = false
            startSession(currentProfile, true)
        }
    }

    /** 悬浮面板状态回推（开关/档位/会话态），UI 更新后随手同步 */
    private fun syncOverlayPanel() {
        val panel = overlayPanel ?: return
        panel.setSessionRunning(probe?.isRunning == true)
        panel.setSoundEnabled(soundEnabled)
        panel.setInterpState(
            interpMode != InterpMode.OFF,
            interpMode.label,
            interpQuality.label,
            interpGhosting.label
        )
        panel.setHudVisible(perfHud?.visibility == View.VISIBLE)
        panel.setDebugMode(debugMode)
        panel.setLsfgStatus(LsfgPipeline.statusShort(), LsfgPipeline.statusDetail())
        panel.setLsfgParams(
            "${lsfgMultiplier}x",
            lsfgFlowLabel(),
            lsfgFpsLabel(),
            "$lsfgQueueDepth 档"
        )
    }

    /** 光流精度中文标签 */
    private fun lsfgFlowLabel(): String = when (lsfgFlowScale) {
        1.0f -> "高（1.0，精细）"
        0.5f -> "中（0.5）"
        else -> "低（0.25，快速）"
    }

    /** 目标帧率上限中文标签 */
    private fun lsfgFpsLabel(): String = if (lsfgTargetFps == 0) "关闭" else "$lsfgTargetFps fps"

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

    /** 追加一行日志（任意线程可调）。只保留最近 [MAX_LOG_LINES] 行，避免长时间预览把 UI 拖慢 */
    private fun postLog(line: String) {
        runOnUiThread {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logView.text = logLines.joinToString("")
            // 日志框只有几十 dp 高，新行都在末尾，必须跟着滚到底部才看得到
            logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            updateStatsLine()
        }
    }

    // ==================== 取流 + 预览控制 ====================

    /** 按指定档位开始一次取流会话；[hardReset] = 先硬复位采集卡（等效重新插拔）再协商 */
    private fun startSession(p: StreamProfile, hardReset: Boolean = false) {
        if (probe?.isRunning == true) {
            statusView.text = "正在预览中，请先停止"
            return
        }
        val usbManager = try {
            getSystemService(Context.USB_SERVICE) as? UsbManager
        } catch (t: Throwable) {
            null
        }
        if (usbManager == null) {
            postLog("!! UsbManager 不可用\n")
            return
        }
        val device = findUvcDevice(usbManager)
        if (device == null) {
            postLog("!! 未找到 UVC 设备（interfaceClass=0x0E），请确认采集卡已插稳\n")
            statusView.text = "未找到 UVC 设备"
            return
        }
        if (!usbManager.hasPermission(device)) {
            postLog(
                "!! 设备未授权: " + device.deviceName +
                    "；请回主界面点「请求 USB 授权」（UVC 设备还需要 CAMERA 权限才会弹窗）\n"
            )
            statusView.text = "设备未授权"
            statusView.setTextColor(GlassStyle.color(GlassStyle.ERROR))
            return
        }

        currentProfile = p
        ensurePipeline(p)
        // 会话边界（M5）：按当前模式/档位决定进 LSFG 还是 GL 通路（幂等）
        syncInterpPath()
        val asm = assembler ?: return
        val r = renderer ?: return
        asm.reset()
        lastStats = null
        resetWatchdog()

        postLog(
            String.format(
                Locale.US,
                "\n==== 开始实时预览 @ %s ｜ %s (%s) ｜ request 池 %d ｜ 不限时 ====\n",
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                p.label,
                p.fourcc,
                POOL_SIZE
            )
        )
        setEnabledVisual(startButton, false)
        setEnabledVisual(stopButton, true)
        statusView.text = "协商中… " + p.label
        statusView.setTextColor(statusColor())
        refreshProfileButtons()

        // 声音随会话启动（开关默认开；权限缺失 / 无 USB 音频输入时只记日志，不阻断视频）。
        // 硬复位会把 UAC 音频设备整个重建（snd-usb-audio 随 SET_CONFIGURATION 重绑），
        // 这时起 AudioRecord 只会拿到"设备正在消失"的残骸 —— 等复位完成后再起
        if (hardReset) {
            postLog(">>> [硬复位] 本次启动执行采集卡硬复位（等效重新插拔）：SET_CONFIGURATION + 全套重协商\n")
            audioBridge?.dampGlitch()
            statusView.postDelayed({
                if (!isFinishing && !isDestroyed && !userStopped) {
                    postLog(">>> [声音] 硬复位后重建 USB 音频采集（UAC 设备已随复位重新枚举）\n")
                    audioBridge?.stop()
                    audioBridge = null
                    startAudioIfNeeded()
                }
            }, AUDIO_RESTART_AFTER_RESET_MS)
        } else {
            startAudioIfNeeded()
        }

        val streamProbe = UvcStreamProbe(this, device, this, p)
        streamProbe.transferSink = UvcStreamProbe.TransferSink { buffer, length -> asm.push(buffer, length) }
        probe = streamProbe
        // durationMs <= 0 = 不限时，靠「停止」或退出页面结束
        streamProbe.start(POOL_SIZE, 0L, hardReset)
        // 看门狗独立心跳：从此刻起每秒自查一次，与取流线程的 onStats 彻底解耦（见 watchdogRunnable）
        statusView.removeCallbacks(watchdogRunnable)
        statusView.postDelayed(watchdogRunnable, WATCHDOG_TICK_MS)
    }

    /** 取第一个含 UVC 接口（class=0x0E）的设备 */
    private fun findUvcDevice(usbManager: UsbManager): UsbDevice? = try {
        usbManager.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i -> d.getInterface(i)?.interfaceClass == 0x0E }
        }
    } catch (t: Throwable) {
        null
    }

    // ==================== 声音（采集卡 UAC 音频 → 扬声器） ====================

    /**
     * 声音开关：
     * - 关：静音（[AudioBridge.muted]，采集保持排空，再点即刻恢复，不用重新枚举设备）；
     * - 开：没起过就走 [startAudioIfNeeded] 启动。
     */
    private fun onSoundButtonClicked() {
        if (soundEnabled) {
            soundEnabled = false
            audioBridge?.muted = true
            updateSoundButton()
            postLog(">>> [声音] 已静音（采集保持，点「声音」即刻恢复）\n")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postLog(">>> [声音] 需要录音权限才能读取 USB 音频，请在系统弹窗中允许\n")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        soundEnabled = true
        updateSoundButton()
        val bridge = audioBridge
        if (bridge != null && bridge.isRunning) {
            bridge.muted = false
            postLog(">>> [声音] 已恢复转放\n")
        } else {
            startAudioIfNeeded()
        }
    }

    private fun updateSoundButton() {
        soundButton?.text = if (soundEnabled) "声音 开" else "声音 关"
    }

    /**
     * 插帧按钮（HyperFrame 1.0）：短按循环 关 → 自动 → LSFG → 经典。
     * 自动/LSFG 走 LSFG 帧生成（MJPEG 档），经典为 GSFG 风格运动补偿插帧兜底。
     */
    private fun onInterpButtonClicked() {
        val order = InterpMode.entries
        interpMode = order[(order.indexOf(interpMode) + 1) % order.size]
        renderer?.interpMode = interpMode
        syncInterpPath()
        updateInterpButton()
        postLog(">>> [插帧] 模式 → ${interpMode.label}：${interpModeHint()}\n")
    }

    /** 各插帧模式的一句话说明（日志/面板用） */
    private fun interpModeHint(): String = when (interpMode) {
        InterpMode.OFF -> "关闭插帧，逐帧直绘"
        InterpMode.AUTO -> "自动 —— MJPEG 档走 LSFG 帧生成，其余走经典插帧"
        InterpMode.LSFG -> "LSFG 帧生成（未导入 Lossless.dll 或启动失败时自动回退经典插帧）"
        InterpMode.GSFG -> "经典 GSFG 运动补偿插帧（${interpQuality.label}档 · 抗拖影${interpGhosting.label}，固定 2x 低延迟）"
    }

    /** 长按插帧按钮：循环经典插帧的质量档位（清晰 → 均衡 → 流畅，光流网格密度） */
    private fun onInterpButtonLongClicked() {
        val order = FrameInterpolator.Quality.entries
        interpQuality = order[(order.indexOf(interpQuality) + 1) % order.size]
        renderer?.interpQuality = interpQuality
        updateInterpButton()
        // 悬浮面板「插帧质量」行的读数也要立刻跟上（否则要等下一次统计节拍才刷新）
        syncOverlayPanel()
        postLog(">>> [插帧] 经典档质量 → ${interpQuality.label}（光流网格更细果冻更少，GPU 开销更大）\n")
    }

    /** 悬浮面板「抗拖影」行：循环档位（弱 → 标准 → 强），热切换、只换权重引用 */
    private fun onInterpGhostingCycle() {
        val order = FrameInterpolator.Ghosting.entries
        interpGhosting = order[(order.indexOf(interpGhosting) + 1) % order.size]
        renderer?.interpGhosting = interpGhosting
        syncOverlayPanel()
        postLog(">>> [插帧] 抗拖影档 → ${interpGhosting.label}（越强越快回退真实帧、光流更跟手，果冻感略增）\n")
    }

    private fun updateInterpButton() {
        interpButton?.text = when (interpMode) {
            InterpMode.OFF -> "插帧 关"
            InterpMode.AUTO -> "插帧 自动"
            InterpMode.LSFG -> "插帧 LSFG"
            InterpMode.GSFG -> "插帧 经典·${interpQuality.label}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            soundEnabled = true
            postLog(">>> [声音] 录音权限已授予\n")
            startAudioIfNeeded()
        } else {
            soundEnabled = false
            postLog("!! [声音] 录音权限被拒绝，声音不可用（可在系统设置里授权后点「声音」重试）\n")
        }
        updateSoundButton()
    }

    /** 随预览会话启动声音（开关开着 + 有录音权限才真正启动；失败只记日志不阻断视频） */
    private fun startAudioIfNeeded() {
        if (!soundEnabled) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postLog("!! [声音] 未授予录音权限，本次无声（点「声音」按钮可重新申请）\n")
            return
        }
        val bridge = audioBridge ?: AudioBridge(this) { line -> postLog(line) }.also { audioBridge = it }
        if (bridge.isRunning) return
        bridge.muted = false
        bridge.start()
    }

    private fun stopAudio() {
        audioBridge?.stop()
    }

    // ==================== 断流自动恢复（看门狗） ====================

    /**
     * 只重置**会话级**看门狗统计。
     *
     * 【v0.22.0 关键修复】以前这里会顺带把 recoveryActive/recoveryAttempts/pausedUntilNs
     * 清掉，而它被 startSession 每次调用 —— 自动恢复重启会话时恢复链状态被清回起点，
     * triggerRecovery 永远走"第 1 步：同档重协商"，超带宽档位（4K30 YUY2）每次活 2-5 秒
     * 就塌、再同档重试，形成用户看到的"连 5 秒就断连"死循环。
     * 恢复链状态现在只在用户操作时由 [clearRecoveryState] 清。
     */
    private fun resetWatchdog() {
        lastFramesSeen = 0L
        lastProgressNs = System.nanoTime()
        badSinceNs = 0L
        goodSinceNs = 0L
        lastErrTotal = -1
    }

    /** 清恢复链状态（只在用户操作：开始 / 停止 / 手动换档） */
    private fun clearRecoveryState() {
        recoveryActive = false
        recoveryAttempts = 0
        pausedUntilNs = 0L
        hardResetNext = false
    }

    /**
     * 每秒（独立心跳 [watchdogRunnable]，不依赖 onStats）检查一次画面健康度：
     * - **停滞**：装配帧计数不再增长（USB 断流 / 会话卡死）；
     * - **纯黑场**：帧还在出，但 Y 均值极低且中部一行毫无起伏 —— 典型的
     *   "进游戏后 HDMI 源分辨率切换、采集卡锁不住新时序"的症状（v0.17.0 实测：
     *   USB 仍 248MB/s，帧内容恒 Y=16 后变全 0）。
     *
     * 连续 [BAD_NS] 异常后触发自动恢复：先同档位重新协商（管线重置），
     * 仍无画面则逐档轮询；全部试完冷却 [PAUSE_NS] 后从头再来。
     */
    private fun watchdog() {
        val asm = assembler ?: return
        val now = System.nanoTime()
        val frames = asm.counters.frames
        val progressed = frames > lastFramesSeen
        if (progressed) {
            lastFramesSeen = frames
            lastProgressNs = now
        }

        // USB 错误爆发提示（v0.23.0）：一拍内 queue 失败/请求错误暴涨 = USB 层被数据量
        // 打塌（超带宽档位的典型症状）。只提示不自动换档 —— 换档决定权在用户
        val s = lastStats
        if (s != null) {
            val err = s.queueFailures + s.requestErrors
            if (lastErrTotal >= 0 && err - lastErrTotal >= ERROR_BURST_MARK) {
                postLog(
                    String.format(
                        Locale.US,
                        "!! [看门狗] USB 错误爆发（本拍 +%d）：当前档位 %s 把 USB 层打塌了，建议手动切到 MJPEG 或低一档分辨率\n",
                        err - lastErrTotal,
                        currentProfile.label
                    )
                )
            }
            lastErrTotal = err
        }

        val stalled = now - lastProgressNs > STALL_NS
        // 输出冻结：帧计数照常在涨（USB 流不停），但载荷头 PTS 停滞 —— 采集卡固件卡住、
        // 画面冻在最后一帧的铁证（与"取流停滞"互补，同样必须触发恢复）
        val p = probe
        val ptsFrozen = p != null && p.lastPtsChangeNs > 0 && progressed &&
            now - p.lastPtsChangeNs > PTS_FREEZE_NS
        val black = asm.lastYAvg <= BLACK_AVG_MAX && asm.lastMidRowRange <= BLACK_ROW_RANGE_MAX
        val bad = stalled || black || ptsFrozen

        if (bad) {
            if (badSinceNs == 0L) {
                badSinceNs = now
                // 从发现异常那一刻起就开始压毛刺（重协商风暴前先清干净播放队列）
                audioBridge?.dampGlitch()
                postLog(
                    String.format(
                        Locale.US,
                        ">>> [看门狗] 检测到异常：%s（Y 均值 %.1f，中部行极差 %d）\n",
                        when {
                            stalled -> "取流停滞"
                            ptsFrozen -> "采集卡输出冻结（PTS 停滞，画面内容不更新）"
                            else -> "画面纯黑（疑似源分辨率切换导致无信号）"
                        },
                        asm.lastYAvg,
                        asm.lastMidRowRange
                    )
                )
            }
            goodSinceNs = 0L
        } else {
            badSinceNs = 0L
            if (goodSinceNs == 0L) goodSinceNs = now
            // 必须**连续健康 GOOD_HEALTH_NS** 才认定恢复：超带宽档位每次重协商后开头几拍
            // 看着都正常，短窗口确认会把恢复链误清掉（v0.21.0 的 goodStreak=3 就是这毛病）
            if (recoveryActive && now - goodSinceNs >= GOOD_HEALTH_NS) {
                recoveryActive = false
                recoveryAttempts = 0
                pausedUntilNs = 0L
                postLog(
                    ">>> [看门狗] 画面已连续健康 " +
                        (now - goodSinceNs) / 1_000_000_000L +
                        " 秒，锁定 ${currentProfile.label}\n"
                )
            }
        }

        if (pendingRestart != null) return
        if (now < pausedUntilNs) return
        // 停滞/输出冻结判定本身就已经持续了 STALL_NS / PTS_FREEZE_NS，不必再叠一层
        // BAD_NS 确认窗 —— 直接恢复；黑场保留 BAD_NS 确认（转场/一闪而过的黑帧不该触发重协商）
        when {
            stalled -> triggerRecovery("取流停滞")
            ptsFrozen -> triggerRecovery("采集卡输出冻结")
            badSinceNs != 0L && now - badSinceNs >= BAD_NS -> triggerRecovery("画面纯黑")
        }
    }

    /** 各档位塌方时间戳（label → 滑动窗口队列）；手动切档时清空（重新给所有档位机会） */
    private val crashHistoryNs = HashMap<String, ArrayDeque<Long>>()

    /**
     * 断流恢复 = **只按当前档位重新协商**，永不自动换分辨率（v0.23.0）。
     *
     * 【教训】v0.22.0 的"逐档轮询"会把分辨率换来换去（4K60→1440p→1080p 循环），
     * 用户观感是"一直不停切换分辨率"。参考 libuvc/参考 APK 的做法：出错只做
     * reset → 重新 claim → 同档重连，换档永远是用户手动决定。
     * 每次重试间隔递增退避（3/5/8/12/20/30s），连续 [MAX_SAME_PROFILE_RETRY] 次
     * 仍无画面就停止自动恢复并提示用户。
     */
    /** 记一次塌方（滑动窗口统计；只在恢复链起点记，链中途的连环触发不重复计） */
    private fun recordCrash(p: StreamProfile) {
        val now = System.nanoTime()
        val q = crashHistoryNs.getOrPut(p.label) { ArrayDeque() }
        while (!q.isEmpty() && now - q.first() > CRASH_WINDOW_NS) q.removeFirst()
        q.addLast(now)
    }

    private fun crashCount(p: StreamProfile): Int {
        val now = System.nanoTime()
        val q = crashHistoryNs[p.label] ?: return 0
        while (!q.isEmpty() && now - q.first() > CRASH_WINDOW_NS) q.removeFirst()
        return q.size
    }

    /**
     * 该档若已在窗口内反复塌方，返回更轻的一档（profiles 已按"分辨率降序 → 帧率降序"排好，
     * 向后第一个严格更轻的非超带宽档即是）；否则返回 null（继续按当前档恢复）。
     * 降档判定消费掉旧档塌方史，并给目标档清零（全新机会）。
     */
    private fun lighterProfileIfUnstable(p: StreamProfile): StreamProfile? {
        if (crashCount(p) < CRASH_LIMIT) return null
        val idx = profiles.indexOfFirst { it.label == p.label }
        if (idx < 0) return null
        val curPixels = p.width.toLong() * p.height
        val lower = profiles.drop(idx + 1).firstOrNull {
            !it.overBandwidth && (it.width.toLong() * it.height < curPixels || it.fps < p.fps)
        } ?: return null
        crashHistoryNs.remove(p.label)
        crashHistoryNs.remove(lower.label)
        return lower
    }

    private fun triggerRecovery(reason: String) {
        val now = System.nanoTime()
        if (now < pausedUntilNs) {
            // 退避期内又被触发：排个延时任务到点再试，避免重连风暴
            scheduleCooldownRetry()
            return
        }
        // 塌方史死循环修复（v1.1.2）：120fps/4K 这类档位的表现是"硬复位救得活、
        // 活 10-20 秒又塌"，而健康确认会把 recoveryAttempts 清零，永远到不了
        // MAX_SAME_PROFILE_RETRY，形成无限断连循环。这里在恢复链起点记塌方，
        // 窗口内达上限就判定该档在这张卡上不稳定，自动降到更轻的一档
        //（v0.23.0 "不自动换档"原则的例外：只降不升、且是反复塌方后的最后手段，
        //  日志明说、用户手动切回任意档位即清空塌方史重新给机会）。
        if (!recoveryActive) {
            recordCrash(currentProfile)
            val lower = lighterProfileIfUnstable(currentProfile)
            if (lower != null) {
                postLog(
                    "!! [看门狗] ${currentProfile.label} 近期反复断连 $CRASH_LIMIT 次" +
                        "（硬复位救得活但稳不住），判定该档在这张采集卡上不稳定，自动降到 ${lower.label}；" +
                        "手动切回任意档位会重置此判定\n"
                )
                currentProfile = lower
                refreshProfileButtons()
            }
        }
        recoveryActive = true
        recoveryAttempts++
        if (recoveryAttempts > MAX_SAME_PROFILE_RETRY) {
            recoveryActive = false
            pausedUntilNs = 0L
            postLog(
                "!! [看门狗] 已按当前档位 ${currentProfile.label} 连续重协商 " +
                    "$MAX_SAME_PROFILE_RETRY 次仍无画面，停止自动恢复。\n" +
                    "请手动换分辨率档位，或点「复位卡」做一次等效插拔，或检查 HDMI 源（120Hz/VRR/4K 可能超出采集卡输入能力）\n"
            )
            return
        }
        // 采集卡固件卡死的升级策略（v0.24.0 实测、v0.28.0 提速）：HDMI 源换分辨率后
        // 固件卡死时 close/重开/软重协商全救不回来，只有拔插能救 —— 所以第一次恢复就
        // 直接硬复位（等效插拔），不做无意义的软重试。恢复重启由 onFinished 立即接力，
        // 中间的退避窗 [pausedUntilNs] 只用来拦"重连风暴"（连续失败才拉长间隔）。
        if (recoveryAttempts >= HARD_RESET_FROM_ATTEMPT) {
            hardResetNext = true
        }
        val hr = hardResetNext
        postLog(
            ">>> [看门狗] $reason —— 第 $recoveryAttempts/$MAX_SAME_PROFILE_RETRY 次按当前档位 " +
                currentProfile.label + "重新协商" + if (hr) "（含硬复位，等效重新插拔）" else "" + "\n"
        )
        // 连续失败的下一次重试至少间隔退避时长（画面恢复健康时由 watchdog 的恢复确认清掉）
        pausedUntilNs = System.nanoTime() + backoffNsFor(recoveryAttempts)
        badSinceNs = 0L
        userStopped = false
        audioBridge?.dampGlitch()
        val p = probe
        if (p != null && p.isRunning) {
            // 停掉旧会话，onFinished 看到 pendingRestart 立刻带硬复位重启（v0.28.0：
            // 以前这里等退避到点才重启，白白多 3 秒黑屏）
            pendingRestart = currentProfile
            p.stop()
        } else {
            hardResetNext = false
            startSession(currentProfile, hr)
        }
    }

    /** 两次同档重协商之间的最小间隔（递增退避） */
    private fun backoffNsFor(attempt: Int): Long = when {
        attempt <= 1 -> 3_000_000_000L
        attempt == 2 -> 5_000_000_000L
        attempt == 3 -> 8_000_000_000L
        attempt == 4 -> 12_000_000_000L
        attempt == 5 -> 20_000_000_000L
        else -> 30_000_000_000L
    }

    /**
     * 退避到点后补一次恢复尝试。
     * 会话已经停了、看门狗不会再 tick，退避期内的连环触发只能靠延时任务把恢复链续上。
     * 到点后若用户已手动接管（recoveryActive=false / 已有会话 / 已退出）则什么都不做。
     */
    private fun scheduleCooldownRetry() {
        if (cooldownRetryScheduled) return
        cooldownRetryScheduled = true
        val delayMs = ((pausedUntilNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L) + 100L
        statusView.postDelayed({
            cooldownRetryScheduled = false
            if (recoveryActive && !userStopped && !isFinishing && !isDestroyed && probe == null) {
                triggerRecovery("退避结束，重试")
            }
        }, delayMs)
    }

    // ==================== UvcStreamProbe.Listener ====================

    override fun onLog(line: String) {
        postLog(line)
    }

    override fun onStats(stats: UvcStreamProbe.Stats) {
        lastStats = stats
        runOnUiThread {
            statusView.setTextColor(statusColor())
            statusView.text = String.format(
                Locale.US, "预览中 %.1fs ｜ %s", stats.elapsedMs / 1000.0, currentProfile.label
            )
            updateStatsLine()
            // 看门狗不再挂在这里（v0.21.0）：它由独立心跳驱动，取流线程死了也能被检出
        }
    }

    override fun onFinished(description: String) {
        // 装配小结里带着「载荷头是不是额外开销」的结论，这是排查画面错位的第一手证据
        val asm = assembler
        if (asm != null) {
            asm.logSummary()
        }
        // 会话一结束就压声音毛刺：断链瞬间 UAC 音频跟着源同步抖动，垃圾样本赶在
        // 恢复触发之前就会漏进扬声器（用户听到的"报错音"），必须第一时间掐掉
        if (!userStopped) {
            audioBridge?.dampGlitch()
        }
        // bulk 端点整批排队失败 = 采集卡固件卡死的铁证（HDMI 源切换分辨率后常见），
        // 这种状态 close/重开救不回来（v0.23.0 实测反复断链），只有"拔插"能救 ——
        // 下次启动直接上硬复位（等效插拔），不做无意义的软重试
        val endpointDead = description.contains("全部 request 排队失败")
        if (endpointDead) {
            hardResetNext = true
        }
        runOnUiThread {
            postLog(">>> 取流会话结束: $description\n")
            if (endpointDead) {
                postLog(">>> [硬复位] bulk 端点整批排队失败 = 采集卡固件卡死（源切换分辨率后的典型症状）→ 下次启动硬复位，等效重新插拔\n")
            }
            val next = pendingRestart
            pendingRestart = null
            probe = null
            if (next != null && !userStopped && !isFinishing && !isDestroyed) {
                // 自动恢复/手动换档/手动复位：按新档位重启（带着硬复位标记）
                postLog(">>> [看门狗] 自动重新协商 → ${next.label}\n")
                val hr = hardResetNext
                hardResetNext = false
                startSession(next, hr)
                return@runOnUiThread
            }
            if (recoveryActive && !userStopped && !isFinishing && !isDestroyed) {
                // 恢复中途会话就结束了（典型：协商被设备拒绝，会话根本没跑起来）。
                // 看门狗只在会话运行时 tick，不接上这一下恢复链就断在这里了 —— 直接续接恢复。
                postLog(">>> [看门狗] 会话提前结束（协商被拒或异常退出），按当前档位继续恢复\n")
                setEnabledVisual(startButton, true)
                setEnabledVisual(stopButton, false)
                statusView.text = "自动恢复中（退避后重试）"
                triggerRecovery("会话提前结束")
                return@runOnUiThread
            }
            if (!userStopped && !isFinishing && !isDestroyed) {
                // 会话意外结束而用户并没有点「停止」：典型是 HDMI 源换分辨率时 USB 层出错、
                // 取流线程整个死亡退出。这等价于"取流停滞"，绝不能就这么停在最后一帧 ——
                // 直接进入自动恢复（第 1 步同档重协商，仍无画面则逐档轮询）。
                postLog("!! 取流会话意外结束（用户未停止），进入自动恢复\n")
                setEnabledVisual(startButton, true)
                setEnabledVisual(stopButton, false)
                statusView.text = "自动恢复中"
                triggerRecovery("会话意外结束")
                return@runOnUiThread
            }
            setEnabledVisual(startButton, true)
            setEnabledVisual(stopButton, false)
            stopAudio()
            statusView.text = "已结束"
            statusView.setTextColor(statusColor())
            updateStatsLine()
        }
    }

    /**
     * 把速率、装配、上屏、延迟四组数字拼成四行（都由 runOnUiThread 触发，不会跨线程读视图）。
     *
     * 装配那行里的「线上/帧」是最关键的诊断数字：它等于像素字节数 + 载荷头开销。
     * 若约为 像素 + 12×段数 → 每段传输都带头（正常）；若约为 像素 + 12 → 每帧只有一个头。
     * 延迟行是 v0.18.0 的延迟基线 v1：采集→装配（PTS/SCR 换算）+ 提交→上屏（swap 时刻）。
     */
    private fun updateStatsLine() {
        val c = assembler?.counters
        val r = renderer
        val s = lastStats
        // MJPEG 是压缩流：frameBytes 只是帧上限，达成率百分比没有意义，只报瞬时速率
        val rate = when {
            s == null -> "-"
            currentProfile.isMjpeg -> String.format(Locale.US, "%.1f MB/s (MJPEG 压缩流)", s.instantMBps)
            else -> String.format(Locale.US, "%.1f MB/s (%.1f%%)", s.instantMBps, s.achievedRatio * 100)
        }
        val asmLine = if (c == null) {
            "装配: -"
        } else {
            String.format(
                Locale.US,
                "装配: %d 帧 ｜ 带头段 %d / 无头段 %d ｜ 丢半帧 %d ｜ 段/帧 %.0f ｜ 线上 %.0f B/帧",
                c.frames, c.headerTransfers, c.headerlessTransfers,
                c.partialDrops, c.transfersPerFrame, c.wireBytesPerFrame
            )
        }
        val ld = if (lsfgActive) lsfgDirect else null
        // LSFG 通路的上屏帧率按 getPostedFrameCount 差分估算（渲染循环只给累计值）
        var lsfgFps = 0.0
        var lsfgLatency = -1.0
        val glLine: String
        val latLine: String
        if (ld != null) {
            val now = System.nanoTime()
            val posted = ld.postedFrameCount()
            if (lsfgPostedNs != 0L && now > lsfgPostedNs) {
                lsfgFps = (posted - lsfgPosted) * 1_000_000_000.0 / (now - lsfgPostedNs)
            }
            lsfgPosted = posted
            lsfgPostedNs = now
            lsfgLatency = ld.averageLatencyMs()
            glLine = String.format(
                Locale.US,
                "上屏: LSFG 直呈 %.1f fps（上屏 %d 帧，生成 %d 帧，采集 %d 帧）",
                lsfgFps, posted, ld.generatedFrameCount(), ld.uniqueCaptureCount()
            )
            latLine = String.format(
                Locale.US,
                "延迟: 采集→装配 %s ｜ LSFG 显示 %s",
                formatMs(assembler?.lastCaptureLatencyMs ?: -1.0),
                formatMs(lsfgLatency)
            )
        } else {
            glLine = String.format(
                Locale.US,
                "上屏: %.1f fps（共 %d 帧，丢帧 %d）",
                r?.renderFps ?: 0.0, r?.renderedFrames ?: 0L, r?.droppedCount ?: 0L
            )
            latLine = String.format(
                Locale.US,
                "延迟: 采集→装配 %s ｜ 提交→上屏 %s",
                formatMs(assembler?.lastCaptureLatencyMs ?: -1.0),
                formatMs(r?.renderLatencyMs ?: -1.0)
            )
        }
        val text = "速率: $rate\n$asmLine\n$glLine\n$latLine"
        statsView.text = text
        // v0.30.0：性能 HUD 喂实时链路指标（帧率/源/延迟/丢帧），悬浮面板「性能」页同步读数
        perfHud?.setLive(
            if (ld != null) lsfgFps else r?.renderFps ?: 0.0,
            s?.instantFidFps ?: 0.0,
            if (ld != null) lsfgLatency else r?.renderLatencyMs ?: -1.0,
            r?.droppedCount ?: 0L
        )
        overlayPanel?.setStatsText(text)
        syncOverlayPanel()
    }

    private fun formatMs(ms: Double): String =
        if (ms < 0) "-" else String.format(Locale.US, "%.1f ms", ms)

    /** 状态行普通文字色：全屏模式深色控制条上用主文字色，普通模式用次级色 */
    private fun statusColor(): Int =
        GlassStyle.color(if (fullscreenMode) GlassStyle.TEXT_PRIMARY else GlassStyle.TEXT_SECONDARY)

    // ==================== 日志导出（复用公共逻辑） ====================

    private fun exportLog() {
        val text = logView.text?.toString().orEmpty()
        if (text.isBlank()) {
            statusView.text = "日志为空，请先跑一次预览"
            return
        }
        val fileName = "hyperframe-preview-v" + appVersionName() + "-" +
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

    private fun appVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (t: Throwable) {
        -1L
    }

    companion object {
        /** request 池大小：取流测试页扫过 8/16/32/64，32 是已验证能跑满 248.9 MB/s 的档位 */
        private const val POOL_SIZE = 32

        /** 日志视图最多保留的行数 */
        private const val MAX_LOG_LINES = 2000

        /** Y 均值 ≤ 它才可能是黑场（有限范围黑 Y=16、全 0 场 Y≈0） */
        private const val BLACK_AVG_MAX = 18.0

        /** 中部一行 Y 极差 ≤ 它才算"毫无内容"（真实暗场景一整行也会有起伏） */
        private const val BLACK_ROW_RANGE_MAX = 2

        /** 连续异常多久才触发自动恢复 */
        private const val BAD_NS = 3_000_000_000L

        /** 看门狗独立心跳的节拍（毫秒） */
        private const val WATCHDOG_TICK_MS = 1000L

        /** 多久没有新帧算取流停滞（v0.28.0 3s→2s：检测时长直接决定断连黑屏时长） */
        private const val STALL_NS = 2_000_000_000L

        /** 帧计数在涨但 PTS 停滞超过它 = 采集卡输出冻结（画面冻在最后一帧） */
        private const val PTS_FREEZE_NS = 3_000_000_000L

        /** 同档重协商连续失败多少次后放弃自动恢复，交回给用户 */
        private const val MAX_SAME_PROFILE_RETRY = 8

        /** 档位塌方史的滑动窗口（纳秒）：窗内塌方达 [CRASH_LIMIT] 次就判该档不稳定、自动降档 */
        private const val CRASH_WINDOW_NS = 600_000_000_000L   // 10 分钟

        /** 判定档位不稳定的塌方次数上限（间歇性塌方档"救得活但稳不住"的死循环修复，v1.1.2） */
        private const val CRASH_LIMIT = 3

        /**
         * 从第几次恢复起升级为硬复位（v0.28.0 起 = 1，即第一次恢复就硬复位）。
         * v0.24.0 实测：HDMI 源换分辨率后采集卡固件卡死时 close/重开/软重协商全救不回来，
         * 只有"拔插"（等效 SET_CONFIGURATION 硬复位）能救 —— 先来一轮软重试纯属浪费黑屏时间。
         */
        private const val HARD_RESET_FROM_ATTEMPT = 1

        /** 硬复位后多久重建 USB 音频采集（等 UAC 设备随 SET_CONFIGURATION 重新枚举完） */
        private const val AUDIO_RESTART_AFTER_RESET_MS = 3000L

        /** 连续健康这么久才认定"已恢复"（不能用短窗口：重协商后开头几拍必然正常） */
        private const val GOOD_HEALTH_NS = 10_000_000_000L

        /** 一拍内 queue 失败 + request 错误增量达到它 = USB 错误爆发（只提示，不自动换档） */
        private const val ERROR_BURST_MARK = 16

        /** 全屏模式启动 extra：true = 沉浸式全屏播放（点按视频呼出/收起控制条） */
        const val EXTRA_FULLSCREEN = "dev.padlink.uvc.FULLSCREEN"

        /** 录音权限（声音 v1）运行时请求码 */
        private const val REQUEST_RECORD_AUDIO = 1002

        /** SAF 选 Lossless.dll 的请求码 */
        private const val REQ_PICK_DLL = 1003
    }
}
