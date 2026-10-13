package dev.padlink.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.util.Locale

/**
 * 插帧模式（PadLink 1.0）：关 / 自动 / LSFG / 经典。
 *
 * - 关：不插帧，直绘采集帧；
 * - 自动（默认）：MJPEG 档且 LSFG 就绪时走 LSFG 帧生成，否则走经典插帧；
 * - LSFG：强制走 LSFG 帧生成（未导入 Lossless.dll 或初始化失败时回退经典）；
 * - 经典：GSFG 风格运动补偿插帧（纯 GLES shader，永远可用兜底）。
 *
 * 非「关」档时 GSFG 插帧器保持启用（LSFG 接管 surface 时 GL 通路整个挂起，
 * 不会两边同时插帧）。
 */
enum class InterpMode(val label: String, val gsfg: Boolean) {
    OFF("关", false),
    AUTO("自动", true),
    LSFG("LSFG", true),
    GSFG("经典", true)
}

/**
 * YUY2 → 屏幕的实时预览渲染器：自建 EGL + GLES 3.0，零第三方依赖。
 *
 * **为什么不把 YUY2 转成 RGB 再上屏**：CPU 转 1080p60 需要每秒处理 248 MB 并写出同样多的
 * RGB 数据，纯属浪费；GPU 一个 texelFetch 就能顺手做完，所以整条链路是
 * "USB 直接缓冲 → 纹理 → 片元着色器转色"。
 *
 * **纹理格式的关键点**：一帧 YUY2 的字节布局是 `Y0 U Y1 V`（每 4 字节 2 个像素），
 * 每行 1920 像素 × 2 字节 = 3840 字节。把它当成 **1920×1080 的 RG8 纹理**（每 texel 2 字节）
 * 上传，正好一个字节都不多不少、不需要任何转换：
 * - texel(px, py) = (Y_px, px 为偶数时是 Cb、奇数时是 Cr)；
 * - 于是输出像素 px 的亮度取自 texel px，色度取自同一对里的 texel(偶数) = Cb、texel(奇数) = Cr。
 * 这里用 `texelFetch` 做点采样（不做任何插值），既精确又避免色度串色。
 *
 * **线程模型**：USB 取流线程只管 `submitFrame()`（拷进空闲缓冲并唤醒），
 * EGL/GL 全部在自建的 GL 线程上跑，两者用一个锁 + 环形缓冲交接，丢帧优先丢最旧的
 * （预览只关心最新画面）。
 *
 * 色度/亮度的电平与矩阵：1080p60 属于 HD，HDMI 对 YCbCr 用 BT.709
 * （1080p 通常还是有限范围 16-235）。先按 BT.709 有限范围实现；若画面偏灰或偏色，
 * 只需改片元着色器里的这几个系数。
 */
class Yuy2SurfaceRenderer(
    /** 帧宽（像素），来自 StreamProfile；纹理宽 = 帧宽（每 texel 恰 2 字节） */
    private val frameWidth: Int = 1920,
    /** 帧高（像素），来自 StreamProfile */
    private val frameHeight: Int = 1080,
    /**
     * RGBA 模式（v0.22.0，MJPEG 通路）：帧数据是 MJPEG 解码后的 RGBA8888，
     * 纹理按 GL_RGBA 上传、着色器直接输出；默认 false = YUY2 专用通路。
     */
    private val rgbaMode: Boolean = false,
    private val onLog: (String) -> Unit
) : SurfaceHolder.Callback {

    /** 一帧的字节数：YUY2 每像素 2 字节 / RGBA 每像素 4 字节 */
    private val frameBytes = frameWidth * frameHeight * if (rgbaMode) 4 else 2

    // ==================== 与 USB 线程交接的帧缓冲 ====================

    /**
     * 运动补偿插帧器（GSFG 风格：三帧静止 UI 掩码 + 主/小幅双分支光流 + 融合加权）。
     * 独立持有自己的程序/纹理/FBO，惰性初始化；只在 GL 线程上被调用。
     */
    private val interpolator = FrameInterpolator(frameWidth, frameHeight, rgbaMode, onLog)

    /**
     * 插帧模式（UI 线程写，GL 线程每帧读）。开启时每个源帧固定出 2 帧
     * （t=0.5 中间帧先上屏、真实帧紧随其后），零等待零 pacing。
     * v0.27.0 起低延迟优先：v0.26.0 的自适应倍率+pacing 把真实帧推迟
     * 整整一个源帧间隔，游戏场景没法玩，已整体删除。
     */
    @Volatile
    var interpMode: InterpMode = InterpMode.OFF

    /** 插帧质量档位（UI 线程写，转发给插帧器） */
    var interpQuality: FrameInterpolator.Quality
        get() = interpolator.quality
        set(value) {
            interpolator.quality = value
        }

    /** 抗拖影档位（UI 线程写，转发给插帧器；热切换，只换权重引用） */
    var interpGhosting: FrameInterpolator.Ghosting
        get() = interpolator.ghosting
        set(value) {
            interpolator.ghosting = value
        }

    private val lock = Object()

    /** 空闲缓冲（GL 线程用完就还回来） */
    private val freeBuffers = java.util.ArrayDeque<ByteBuffer>()

    /** 最新待渲染的帧；为空表示 GL 线程已全部消化 */
    private var latest: ByteBuffer? = null

    private var publishedFrames = 0L
    private var droppedFrames = 0L

    /** 上一次源帧到达时刻（submitFrame 内更新，供出帧预算估源帧率） */
    private var lastArrivalNs = 0L

    /** 源帧到达间隔均值（EMA；断流那种超长间隔不进均值） */
    @Volatile
    private var srcIntervalEmaNs = 0.0

    /** 最近一帧提交进来的主机时刻（延迟基线用） */
    @Volatile
    private var latestSubmitNs = 0L

    // ==================== 插帧出帧预算（v0.28.0）====================

    /**
     * 本阶段是否出"中间帧 + 真实帧"（2 swap/源帧）；false = 只出真实帧（1x 直绘）。
     *
     * 【v0.28.0 卡顿根因修复】v0.27.0 的固定 2x 假设"屏幕吸收得了每源帧两次 swap"：
     * 显示只有 60Hz、或 GPU/NN 推理太慢时，渲染循环被 swap 反压拖到源帧率一半以下，
     * 源帧成片被丢（丢的是真实游戏帧），观感比不开插帧还卡。
     * 现在按"上一帧耗时 vs 源帧到达间隔"自适应：跟不上就自动退回 1x 直绘
     * （= 关插帧的效果，帧率立刻回满），之后再低频试探 2x，撑得住就常驻。
     */
    @Volatile
    private var interpBudget2 = true

    /** 退回 1x 后的最短保持时间（防抖） */
    private var budgetHoldUntilNs = 0L

    /** 下一次试探 2x 的时刻（指数退避：2s→4s→…→30s，2x 持续健康则归零） */
    private var budgetNextProbeNs = 0L

    private var budgetCooldownNs = PROBE_COOLDOWN_START_NS

    /** 当前这轮 2x 是从什么时刻开始的（持续 [PROBE_SUSTAIN_NS] 健康 = 试探成功） */
    private var budget2SinceNs = 0L

    /** 连续"跟不上"的帧数（攒够 [BEHIND_STREAK] 才降档，抗抖动） */
    private var behindStreak = 0

    /** 单帧绘制（含 swap 等待）耗时均值 */
    private var drawDurEmaNs = 0.0

    /** 上一次统计到的丢帧总数（算两帧之间新增的丢帧） */
    private var lastStealSeen = 0L

    // ==================== GL 状态（只在 GL 线程上访问） ====================

    @Volatile
    private var running = false

    private var glThread: Thread? = null

    /** 当前 GL 线程绑定的 surface（同一 surface 只改尺寸时无需重建 EGL） */
    @Volatile
    private var boundSurface: Surface? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texture = 0
    private var uOriginLoc = -1
    private var uSizeLoc = -1
    private var uTexDimLoc = -1

    private var lastLoggedWidth = -1
    private var lastLoggedHeight = -1

    /**
     * 当前 surface 尺寸（surfaceChanged 每次都更新，含"同一 surface 只是尺寸变了"的情况）。
     *
     * drawFrame 必须优先用这里的尺寸：eglQuerySurface 对窗口 surface 可能停留在创建时的
     * 旧值，v0.19.0 全屏页的控制条呼出/收起会实时收缩/展开 SurfaceView，尺寸跟踪错了
     * 视口就会按旧尺寸算、画面被裁切。
     */
    @Volatile
    private var windowWidth = 0

    @Volatile
    private var windowHeight = 0

    /** 是否已经打过"首帧绘制"日志（只在每会话的第一帧打一次） */
    private var firstFrameLogged = false

    /** 回读诊断的节流计数 */
    private var readbackCounter = 0L

    /** 回读一个像素用的暂存缓冲（只在 GL 线程上用） */
    private val readPixelBuf = ByteBuffer.allocateDirect(4)

    // ==================== 对外可读的统计 ====================

    /** EGL 是否已就绪（未就绪时 submitFrame 直接丢帧，不做无用的 4MB 拷贝） */
    @Volatile
    var isReady = false
        private set

    /** 已真正画到屏幕上的帧数（GL 线程每秒写一次） */
    @Volatile
    var renderedFrames = 0L
        private set

    /** 最近一秒的实际上屏帧率 */
    @Volatile
    var renderFps = 0.0
        private set

    /** 因缓冲周转不过来而被丢掉的帧数（丢的永远是最旧的） */
    val droppedCount: Long
        get() = synchronized(lock) { droppedFrames }

    /** 已提交给渲染器的帧数 */
    val submittedCount: Long
        get() = synchronized(lock) { publishedFrames }

    /** 提交 → 上屏延迟（毫秒，指数平滑；-1 = 还没测到） */
    @Volatile
    var renderLatencyMs = -1.0
        private set

    // ==================== SurfaceHolder.Callback ====================

    override fun surfaceCreated(holder: SurfaceHolder) {
        // 不在 created 启动：等 surfaceChanged 拿到真实宽高再启动，
        // 避免画到一块没显示出来的 surface 上。
        onLog(">>> [surface] created id=${System.identityHashCode(holder.surface)} ${describe(holder)}\n")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val surface = holder.surface
        onLog(">>> [surface] changed id=${System.identityHashCode(surface)} ${width}x$height fmt=$format ${describe(holder)}\n")
        // 尺寸先记下（幂等，重复回调只刷新数值）：同一 surface 只改尺寸时 EGL 不重建，
        // drawFrame 靠这两个值算视口
        windowWidth = width
        windowHeight = height
        if (suspended) {
            // LSFG 直连通路接管中：EGL 绝不能碰 surface（BufferQueue 单生产者），
            // 尺寸已记下，回退时 resumeGl 会按最新尺寸重启 GL
            onLog(">>> [surface] LSFG 接管中，GL 挂起不重启\n")
            return
        }
        val bound = boundSurface
        if (running && bound === surface) {
            // 同一个 surface 只是尺寸变了（如全屏页控制条呼出/收起）：尺寸已在上方记下，
            // drawFrame 每帧按最新尺寸算视口，无需重建 EGL —— 重建反而引入双 GL 线程互踩
            // EGL 的竞态（边到边下 surfaceChanged 常连发两次，正是 v0.15.0 之前偶发异常的来源）。
            return
        }
        restartGl(surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onLog(">>> [surface] destroyed id=${System.identityHashCode(holder.surface)}\n")
        // 必须在 surfaceDestroyed 返回之前把 GL 线程停干净，否则之后这个 Surface 就失效了
        stopGl()
    }

    private fun describe(holder: SurfaceHolder): String {
        val f = holder.surfaceFrame
        return "frame=${f.width()}x${f.height()}@(${f.left},${f.top}) valid=${holder.surface?.isValid ?: false}"
    }

    // ==================== 启停 ====================

    /**
     * 先停掉旧的（如果有），再基于新 surface 启动。
     *
     * 旧 GL 线程没在宽限期内退出时**绝不**启动第二个：两套 EGL 会互踩
     * （旧线程随后的 destroyEgl 会拆掉新线程的 surface/context）。
     */
    private fun restartGl(surface: Surface) {
        val old = stopGl()
        if (old != null && old.isAlive) {
            onLog("!! [surface] 旧 GL 线程未在 ${GL_STOP_GRACE_MS}ms 内退出，跳过本次重建（避免双 GL 线程互踩）\n")
            return
        }
        startGl(surface)
    }

    private fun startGl(surface: Surface) {
        if (running) return
        synchronized(lock) {
            // 两个缓冲足够：任一时刻只有一帧在 GL 线程手里（inFlight），另一帧是最新待渲染帧
            if (freeBuffers.isEmpty()) {
                freeBuffers.addLast(ByteBuffer.allocateDirect(frameBytes))
                freeBuffers.addLast(ByteBuffer.allocateDirect(frameBytes))
            }
            latest = null
            running = true
        }
        boundSurface = surface
        firstFrameLogged = false
        readbackCounter = 0L
        lastLoggedWidth = -1
        lastLoggedHeight = -1
        val t = Thread({ glLoop(surface) }, "padlink-gl")
        glThread = t
        t.start()
    }

    /** 停掉 GL 线程并等它退出；返回该线程（调用方可检查 isAlive） */
    private fun stopGl(): Thread? {
        synchronized(lock) {
            running = false
            latest = null
            lock.notifyAll()
        }
        val t = glThread ?: return null
        try {
            t.join(GL_STOP_GRACE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!t.isAlive) {
            // 真正退出了才清引用；没退出就留着，让下一次 stopGl 继续 join、
            // 让 restartGl 的 isAlive 检查能拦住"双 GL 线程"。
            glThread = null
            isReady = false
            boundSurface = null
        }
        return t
    }

    /** Activity 销毁时调用（正常情况下 surfaceDestroyed 已经停过一次，这里只是兜底） */
    fun release() {
        stopGl()
    }

    /** LSFG 直连通路接管 surface 时置 true：GL 线程全停、surface 事件不再重启 EGL */
    @Volatile
    private var suspended = false

    /**
     * 进 LSFG 模式：停掉 GL 线程并挂起本渲染器。
     * 必须在 Vulkan 侧 attach 之前调用（BufferQueue 单生产者）。
     */
    fun suspendGl() {
        suspended = true
        stopGl()
        onLog(">>> [surface] GL 已挂起（LSFG 接管）\n")
    }

    /** 回 GSFG/直绘通路：解除挂起并按当前 surface 重启 GL */
    fun resumeGl(holder: SurfaceHolder) {
        suspended = false
        val sf = holder.surfaceFrame
        if (holder.surface?.isValid == true && sf.width() > 0 && sf.height() > 0) {
            surfaceChanged(holder, 0, sf.width(), sf.height())
        }
        onLog(">>> [surface] GL 已恢复（LSFG 退出）\n")
    }

    // ==================== USB 线程侧：提交一帧 ====================

    /**
     * 提交一整帧 YUY2 像素（长度必须正好是 1920*1080*2）。
     *
     * 会拷进内部空闲缓冲，所以调用方可以立刻复用传进来的 buffer。
     * 两个缓冲都被占用时丢弃**最旧**的那一帧 —— 预览看的就是"最新"。
     */
    fun submitFrame(src: ByteBuffer) {
        synchronized(lock) {
            if (!running || !isReady) return
            // 源帧到达间隔（出帧预算用）：断流那种超长间隔不代表源帧率，不进均值
            val now = System.nanoTime()
            if (lastArrivalNs != 0L) {
                val gap = now - lastArrivalNs
                if (gap in 1..MAX_SRC_GAP_NS) {
                    srcIntervalEmaNs =
                        if (srcIntervalEmaNs <= 0.0) gap.toDouble()
                        else srcIntervalEmaNs * 0.9 + gap * 0.1
                }
            }
            lastArrivalNs = now
            // 丢帧永远丢"最旧"的：已经有还没被画的 latest 时直接顶掉它（复用它自己的
            // 缓冲）。绝不能拿空闲缓冲去覆盖 —— 旧 latest 的缓冲会被丢掉引用而泄漏，
            // 缓冲池越转越小，最后每帧都在丢、画面一顿一顿（v0.28.0 修复）。
            var dst = latest
            if (dst != null) {
                latest = null
                droppedFrames++
            } else {
                dst = freeBuffers.pollLast()
            }
            if (dst == null) {
                droppedFrames++
                return
            }
            val dup = src.duplicate()
            dup.position(0)
            dup.limit(frameBytes)
            dst.clear()
            dst.put(dup)
            dst.position(0)
            dst.limit(frameBytes)
            latest = dst
            latestSubmitNs = now
            publishedFrames++
            lock.notifyAll()
        }
    }

    // ==================== GL 线程 ====================

    private fun glLoop(surface: Surface) {
        if (!setupEgl(surface)) {
            synchronized(lock) { running = false }
            return
        }
        if (!setupGl()) {
            destroyEgl()
            synchronized(lock) { running = false }
            return
        }
        isReady = true
        onLog(">>> [预览] GL 就绪（GLES ${GLES30.glGetString(GLES30.GL_VERSION)}）\n")

        var rendered = 0L
        var windowFrames = 0L
        var loggedStatsCount = 0L
        var lastSampleNs = System.nanoTime()

        while (running) {
            val frame: ByteBuffer? = synchronized(lock) {
                while (running && latest == null) {
                    try {
                        lock.wait(WAIT_SLICE_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                val f = latest
                latest = null
                f
            }
            if (frame == null) continue

            val drawStartNs = System.nanoTime()
            val frameSwaps = try {
                drawFrame(frame)
            } catch (t: Throwable) {
                onLog("!! [预览] 绘制异常: $t\n")
                -1
            }
            synchronized(lock) { freeBuffers.addLast(frame) }

            if (frameSwaps < 0) {
                onLog(">>> [预览] 上屏失败（窗口可能已失效），停止渲染循环\n")
                break
            }

            // 出帧预算自适应（v0.28.0）：本帧（含 swap 等待）真实耗时 vs 源帧到达间隔，
            // 决定下一帧出 2 帧（插帧）还是只出真实帧
            updateInterpBudget(System.nanoTime() - drawStartNs)

            // 统计按实际 swap 次数计：插帧自适应倍率下一次源帧可产出多帧
            rendered += frameSwaps
            windowFrames += frameSwaps
            val now = System.nanoTime()
            if (now - lastSampleNs >= 1_000_000_000L) {
                renderFps = windowFrames * 1e9 / (now - lastSampleNs)
                renderedFrames = rendered
                windowFrames = 0
                lastSampleNs = now
                // 每隔几秒把上屏统计写进日志：导出日志里才能看到"到底上屏了多少帧"
                if (rendered / STATS_LOG_EVERY_FRAMES > loggedStatsCount) {
                    loggedStatsCount = rendered / STATS_LOG_EVERY_FRAMES
                    val dropped = synchronized(lock) { droppedFrames }
                    // 插帧通路状态自证（v0.31.0）：日志里直接看出当前到底走的哪条通路，
                    // 不再需要靠"上屏 ≈ FID"反推（v0.30.0 排查时日志窗口太小推不出来）
                    val interpState = when {
                        !interpMode.gsfg -> "关"
                        interpolator.initFailed -> "开·初始化失败(直绘)"
                        !interpBudget2 -> "开·1x退避(跟不上)"
                        else -> "开·2x"
                    }
                    onLog(
                        String.format(
                            Locale.US,
                            ">>> [上屏] %.1f fps ｜ 累计上屏 %d 帧 ｜ 丢弃 %d 帧 ｜ surface 有效=%b ｜ 插帧 %s\n",
                            renderFps, rendered, dropped, boundSurface?.isValid ?: false, interpState
                        )
                    )
                }
            }
        }

        renderedFrames = rendered
        isReady = false
        synchronized(lock) { running = false }
        destroyEgl()
        onLog(">>> [预览] 渲染循环已退出，共上屏 $rendered 帧\n")
    }

    // ==================== EGL ====================

    private fun setupEgl(surface: Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            onLog("!! [预览] eglGetDisplay 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            onLog("!! [预览] eglInitialize 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfig, 0) ||
            numConfig[0] < 1
        ) {
            onLog("!! [预览] eglChooseConfig 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
            return false
        }
        val config = configs[0] ?: return false

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            onLog("!! [预览] eglCreateContext(ES3) 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
            return false
        }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            onLog("!! [预览] eglCreateWindowSurface 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            onLog("!! [预览] eglMakeCurrent 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
            return false
        }
        return true
    }

    private fun destroyEgl() {
        // 插帧器的 GL 资源要在上下文还活着时删（放在解绑/terminate 之前）
        interpolator.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        if (texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
    }

    // ==================== GL 资源 ====================

    private fun setupGl(): Boolean {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        if (vs == 0) return false
        val fs = compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            if (rgbaMode) FRAGMENT_SHADER_RGBA else FRAGMENT_SHADER_YUY2
        )
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return false
        }
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            onLog("!! [预览] 着色器链接失败: ${GLES30.glGetProgramInfoLog(program)}\n")
            return false
        }
        uOriginLoc = GLES30.glGetUniformLocation(program, "uOrigin")
        uSizeLoc = GLES30.glGetUniformLocation(program, "uSize")
        uTexDimLoc = GLES30.glGetUniformLocation(program, "uTexDim")

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // 只是分配存储：YUY2 用 RG8（每 texel 恰 2 字节），RGBA 用 RGBA8（每 texel 4 字节）
        if (rgbaMode) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, frameWidth, frameHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
        } else {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, frameWidth, frameHeight, 0,
                GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, null
            )
        }
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
        GLES30.glUniform2f(uTexDimLoc, frameWidth.toFloat(), frameHeight.toFloat())
        return true
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            onLog("!! [预览] 着色器编译失败: ${GLES30.glGetShaderInfoLog(shader)}\n")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // ==================== 每帧绘制 ====================

    /** 返回本帧成功上屏（swap）的次数；-1 表示上屏失败（Surface 已失效），调用方应当退出循环 */
    private fun drawFrame(buf: ByteBuffer): Int {
        // 优先用 surfaceChanged 汇报的尺寸（支持 v0.19.0 全屏页的实时收缩/展开），
        // 拿不到时才退回 eglQuerySurface
        var sw = windowWidth
        var sh = windowHeight
        if (sw <= 0 || sh <= 0) {
            val size = IntArray(2)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, size, 0)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, size, 1)
            sw = size[0]
            sh = size[1]
        }
        if (sw <= 0 || sh <= 0) {
            // 窗口还没准备好，本帧直接丢掉
            return 0
        }
        if (sw != lastLoggedWidth || sh != lastLoggedHeight) {
            lastLoggedWidth = sw
            lastLoggedHeight = sh
            onLog(">>> [预览] 窗口 ${sw}x$sh\n")
        }

        // 等比填充 + 居中（黑边而不是拉伸变形）
        val viewAspect = frameWidth.toFloat() / frameHeight
        val winAspect = sw.toFloat() / sh
        val vw: Int
        val vh: Int
        if (winAspect > viewAspect) {
            vh = sh
            vw = (sh * viewAspect).toInt()
        } else {
            vw = sw
            vh = (sw / viewAspect).toInt()
        }
        val vx = (sw - vw) / 2
        val vy = (sh - vh) / 2

        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (!firstFrameLogged) {
            firstFrameLogged = true
            onLog(">>> [预览] 首帧绘制：窗口 ${sw}x$sh ｜ 视口 ${vw}x$vh @ ($vx,$vy)\n")
        }

        // 上传源帧（两条通路共用）：每帧只更新像素内容，不做纹理重新分配
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
            if (rgbaMode) GLES30.GL_RGBA else GLES30.GL_RG,
            GLES30.GL_UNSIGNED_BYTE, buf
        )

        var swaps = 1
        val interp = interpolator
        // 出帧预算（v0.28.0）：interpBudget2=false = 渲染循环跟不上源帧率，此时中间帧/
        // 光流全部跳过、只出真实帧（= 关插帧的效果）—— 绝不为了插帧把真实帧挤掉
        when {
            interpMode.gsfg && interpBudget2 -> {
                // GSFG 风格插帧通路（v0.29.0）：源帧 → RGBA 全帧 → 三帧静止 UI 掩码 →
                // 主运动金字塔+小幅支路双分支 → 融合加权 → t=0.5 中间帧先上屏、
                // 真实帧紧随其后。全程无 sleep、无 pacing、真实帧不推迟。
                if (interp.ensureReady()) {
                    interp.resolve(texture)
                    if (interp.canInterpolate) {
                        interp.computeFlow()
                        interp.drawInterpolatedAt(0.5f, vx, vy, vw, vh)
                        if (!swapOrLog()) return -1
                        interp.drawReal(vx, vy, vw, vh)
                        interp.endFrame()
                        swaps = 2
                    } else {
                        // 首帧没有"上一帧"可插：只出真实帧
                        interp.drawReal(vx, vy, vw, vh)
                        interp.endFrame()
                    }
                } else {
                    // 插帧器初始化失败：回退直绘
                    interp.invalidate()
                    drawDirect(vx, vy, vw, vh)
                }
            }

            else -> {
                // 插帧关 / 出帧预算只够 1x：直绘通路，源纹理 → 屏幕一步到位。
                // 关掉插帧就丢掉"上一帧"记忆，避免下次开启时拿旧内容插值
                interp.invalidate()
                drawDirect(vx, vy, vw, vh)
            }
        }

        // 回读诊断：把刚画出来的几个像素值写进日志。这样"屏幕上到底画出了什么"
        // 不再依赖用户肉眼描述 —— 导出日志本身就带结论。
        readbackCounter++
        if (readbackCounter >= READBACK_EVERY_FRAMES) {
            readbackCounter = 0
            val center = readPixel(vx + vw / 2, vy + vh / 2)
            val topLeft = readPixel(vx + 8, vy + vh - 8)
            val bar = readPixel(if (vx > 16) vx / 2 else vx + vw / 8, vy + vh / 2)
            onLog(
                String.format(
                    Locale.US,
                    ">>> [回读] 画面中心 RGB=%s ｜ 左上角 RGB=%s ｜ 黑边区 RGB=%s ｜ 纹理均值=%d\n",
                    center, topLeft, bar, sampleAverageY(buf)
                )
            )
        }

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            onLog("!! [预览] GL 错误 0x${Integer.toHexString(err)}\n")
        }
        val ok = swapOrLog()
        if (ok) {
            // 提交 → 上屏延迟（延迟基线 v1）：swap 成功即认为本帧已交给合成器
            val sub = latestSubmitNs
            if (sub > 0) {
                val ms = (System.nanoTime() - sub) / 1e6
                renderLatencyMs = if (renderLatencyMs < 0) ms else renderLatencyMs * 0.8 + ms * 0.2
            }
        }
        return if (ok) swaps else -1
    }

    // ==================== 插帧出帧预算（v0.28.0） ====================

    /**
     * 每帧结束后用"本帧真实耗时（含 swap 等待）"与"源帧到达间隔"对比，决定下一帧
     * 出 2 帧（插帧）还是只出真实帧：
     * - **跟不上**（连续 [BEHIND_STREAK] 帧耗时 > 源帧间隔 1.12 倍，或一帧之间丢 ≥3 帧）
     *   → 退回 1x 直绘，指数退避后再试探 2x（2s→4s→…→30s）；
     * - **2x 撑住 [PROBE_SUSTAIN_NS]** → 退避归零，之后正常情况一直 2x；
     * - 插帧关（OFF）→ 预算状态全部复位。
     *
     * 【为什么不走 v0.26.0 的 pacing】pacing 用 sleep 把真实帧往后推（+1 源帧间隔延迟），
     * 游戏没法玩；这里真实帧永远不推迟，中间帧只是"屏幕有空位时的加帧"，
     * 没空位就不出 —— 最差情况退化成关插帧的效果，永远不会比不开还卡。
     */
    private fun updateInterpBudget(drawDurNs: Long) {
        val now = System.nanoTime()
        if (!interpMode.gsfg) {
            interpBudget2 = true
            behindStreak = 0
            drawDurEmaNs = 0.0
            budget2SinceNs = 0L
            budgetCooldownNs = PROBE_COOLDOWN_START_NS
            return
        }
        drawDurEmaNs =
            if (drawDurEmaNs <= 0.0) drawDurNs.toDouble()
            else drawDurEmaNs * 0.85 + drawDurNs * 0.15
        val interval = srcIntervalEmaNs
        val stealsNow = synchronized(lock) { droppedFrames }
        val stealDelta = stealsNow - lastStealSeen
        lastStealSeen = stealsNow

        if (interpBudget2) {
            val behind = (interval > 1_000_000.0 && drawDurEmaNs > interval * 1.12) || stealDelta >= 3
            if (behind) {
                behindStreak++
                if (behindStreak >= BEHIND_STREAK) {
                    interpBudget2 = false
                    behindStreak = 0
                    budget2SinceNs = 0L
                    budgetHoldUntilNs = now + BUDGET_HOLD_NS
                    val waitNs = budgetCooldownNs
                    budgetNextProbeNs = now + waitNs
                    budgetCooldownNs = (waitNs * 2).coerceAtMost(PROBE_COOLDOWN_MAX_NS)
                    onLog(
                        String.format(
                            Locale.US,
                            ">>> [插帧] 上屏跟不上源帧率（%.1f ms/帧 vs 源帧间隔 %.1f ms），" +
                                "本阶段只出真实帧；%.0f 秒后再试 2x\n",
                            drawDurEmaNs / 1e6, interval / 1e6, waitNs / 1e9
                        )
                    )
                }
            } else {
                behindStreak = 0
                if (budget2SinceNs == 0L) {
                    budget2SinceNs = now
                } else if (now - budget2SinceNs >= PROBE_SUSTAIN_NS && budgetCooldownNs != PROBE_COOLDOWN_START_NS) {
                    // 2x 长期撑得住：试探退避归零（下次跟不上时从 2s 重新退避）
                    budgetCooldownNs = PROBE_COOLDOWN_START_NS
                    onLog(">>> [插帧] 2x 出帧持续健康，退避档位归零\n")
                }
            }
        } else {
            behindStreak = 0
            if (now >= budgetHoldUntilNs && now >= budgetNextProbeNs) {
                interpBudget2 = true
                budget2SinceNs = now
                onLog(">>> [插帧] 试探恢复 2x 出帧（撑不住会自动退回 1x）\n")
            }
        }
    }

    /**
     * 真实帧直绘：源纹理 → 屏幕一步到位（插帧关 / 插帧器失败 / 插帧通路里的真实帧）。
     * 自己 clear + 设视口，与 FrameInterpolator.drawReal 同一约定：
     * ① 插帧通路里 swap 之后后缓冲内容未定义，黑边区必须靠 clear 补；
     * ② AI 通路的 resolve 会把 viewport 改成模型输入尺寸，这里必须重设回来。
     * 前置条件：源纹理已上传并绑定在 TEXTURE0。
     */
    private fun drawDirect(vx: Int, vy: Int, vw: Int, vh: Int) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uOriginLoc, vx.toFloat(), vy.toFloat())
        GLES30.glUniform2f(uSizeLoc, vw.toFloat(), vh.toFloat())
        // 顶点全靠 gl_VertexID 现算，不需要任何顶点缓冲
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** swap 并把失败原因（EGL 错误码）写进日志：之前静默返回，出问题无从排查 */
    private fun swapOrLog(): Boolean {
        val ok = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!ok) {
            onLog("!! [预览] eglSwapBuffers 失败 err=0x${Integer.toHexString(EGL14.eglGetError())}\n")
        }
        return ok
    }

    /** 读回一个像素的 RGB（glReadPixels 有同步开销，只做诊断用，不能每帧调） */
    private fun readPixel(x: Int, y: Int): String {
        readPixelBuf.clear()
        GLES30.glReadPixels(x, y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readPixelBuf)
        return String.format(
            Locale.US,
            "(%d,%d,%d)",
            readPixelBuf.get(0).toInt() and 0xFF,
            readPixelBuf.get(1).toInt() and 0xFF,
            readPixelBuf.get(2).toInt() and 0xFF
        )
    }

    /**
     * 直接在这一帧要上传的缓冲里抽样出亮度均值。
     *
     * 与装配器的抽样互为印证：装配器证明"装配出来的帧有内容"，这里证明"喂给 GL 的纹理有内容"。
     * YUY2 模式步长取 4 的倍数，保证每次都落在 Y 字节上（YUY2 是 Y0 U Y1 V）；
     * RGBA 模式按整像素取亮度近似 0.299R + 0.587G + 0.114B。
     */
    private fun sampleAverageY(buf: ByteBuffer): Int {
        var sum = 0L
        var n = 0L
        var i = 0
        while (i < frameBytes) {
            sum += if (rgbaMode) {
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                ((r * 299 + g * 587 + b * 114) / 1000).toLong()
            } else {
                (buf.get(i).toInt() and 0xFF).toLong()
            }
            n++
            i += Y_SAMPLE_STRIDE
        }
        return if (n > 0) (sum / n).toInt() else 0
    }

    companion object {
        /** EGL_RENDERABLE_TYPE 里的 OpenGL ES 3 位（EGL_OPENGL_ES3_BIT_KHR = 0x40） */
        private const val EGL_OPENGL_ES3_BIT = 0x40

        /** 等待新帧的切分时长，保证停止请求最多 200ms 内被响应 */
        private const val WAIT_SLICE_MS = 200L

        /** 停止时留给 GL 线程退出的时间 */
        private const val GL_STOP_GRACE_MS = 2000L

        /** 每累计上屏这么多帧就往日志写一次统计（60fps 下约 10 秒） */
        private const val STATS_LOG_EVERY_FRAMES = 600L

        /** 每多少帧回读一次屏幕像素写进日志（60fps 下约 3 秒） */
        private const val READBACK_EVERY_FRAMES = 180L

        /** 源帧到达间隔均值的取样上限：超过它的间隔（断流）不算源帧率 */
        private const val MAX_SRC_GAP_NS = 500_000_000L

        /** 连续多少帧"跟不上"才降回 1x（抗单帧抖动） */
        private const val BEHIND_STREAK = 15

        /** 退回 1x 后最短保持多久才允许试探 2x */
        private const val BUDGET_HOLD_NS = 1_000_000_000L

        /** 试探 2x 的初始/最大退避 */
        private const val PROBE_COOLDOWN_START_NS = 2_000_000_000L
        private const val PROBE_COOLDOWN_MAX_NS = 30_000_000_000L

        /** 2x 持续健康这么久 = 试探成功，退避归零 */
        private const val PROBE_SUSTAIN_NS = 5_000_000_000L

        /** 抽样 Y 均值的步长（必须是 4 的倍数，保证落在 Y 字节上） */
        private const val Y_SAMPLE_STRIDE = 512

        /**
         * 顶点着色器：用 gl_VertexID 生成一个覆盖整个视口的大三角形
         * （(-1,-1), (3,-1), (-1,3)），不需要 VBO/VAO。
         */
        private const val VERTEX_SHADER = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        /**
         * 片元着色器（YUY2）：把 RG8 纹理（每个 texel = YUY2 的 2 个字节）还原成 RGB。
         *
         * gl_FragCoord 是窗口绝对坐标（原点在视口左下），减去视口原点得到视口内坐标，
         * 再按 uTexDim（纹理宽高，v0.18.0 起不再硬编码 1920x1080）换算成像素下标；
         * `1.0 - uv.y` 负责把纹理第 0 行（画面顶部）放到视口上方。
         */
        private const val FRAGMENT_SHADER_YUY2 = """#version 300 es
precision mediump float;
precision highp int;
uniform sampler2D uTex;
uniform vec2 uOrigin;
uniform vec2 uSize;
uniform vec2 uTexDim;
out vec4 outColor;

void main() {
    vec2 local = gl_FragCoord.xy - uOrigin;

    vec2 uv = local / uSize;
    int px = int(clamp(uv.x, 0.0, 0.999999) * uTexDim.x);
    int py = int(clamp(1.0 - uv.y, 0.0, 0.999999) * uTexDim.y);

    int pairBase = (px >> 1) << 1;
    // 同一对里的两个 texel：偶数位给 Cb，奇数位给 Cr
    //
    // 注意：texelFetch 返回的是**归一化值 [0,1]**，不是 0..255 的字节值。
    // v0.12.0 之前这里少了 * 255.0，于是 (y-16)*1.164 得到约 -18、Cb/Cr-128 得到约 -127.5，
    // 代入 BT.709 后 G 被钳到 1.0 而 R/B 被钳到 0 —— 整屏纯绿 (0,255,0)，这正是当时的症状。
    // 所以必须先还原成字节域再做 YCbCr→RGB。
    float yEven = texelFetch(uTex, ivec2(pairBase, py), 0).r * 255.0;
    float cb = texelFetch(uTex, ivec2(pairBase, py), 0).g * 255.0;
    float yOdd = texelFetch(uTex, ivec2(pairBase + 1, py), 0).r * 255.0;
    float cr = texelFetch(uTex, ivec2(pairBase + 1, py), 0).g * 255.0;

    float y = ((px & 1) == 0 ? yEven : yOdd);

    // BT.709 有限范围（16-235 / 16-240）→ 全范围 RGB，全程用字节域计算
    float yy = (y - 16.0) * 1.164;
    float u = cb - 128.0;
    float v = cr - 128.0;
    vec3 rgb = vec3(
        yy + 1.793 * v,
        yy - 0.213 * u - 0.533 * v,
        yy + 2.112 * u
    ) / 255.0;
    outColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

        /**
         * 片元着色器（RGBA，MJPEG 通路）：纹理就是解码后的 RGBA8888 像素，
         * 只做整数像素点采样 + 上下翻转，直接输出。
         */
        private const val FRAGMENT_SHADER_RGBA = """#version 300 es
precision mediump float;
precision highp int;
uniform sampler2D uTex;
uniform vec2 uOrigin;
uniform vec2 uSize;
uniform vec2 uTexDim;
out vec4 outColor;

void main() {
    vec2 local = gl_FragCoord.xy - uOrigin;

    vec2 uv = local / uSize;
    int px = int(clamp(uv.x, 0.0, 0.999999) * uTexDim.x);
    int py = int(clamp(1.0 - uv.y, 0.0, 0.999999) * uTexDim.y);
    outColor = texelFetch(uTex, ivec2(px, py), 0);
}
"""
    }
}