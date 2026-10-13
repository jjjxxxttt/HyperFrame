package dev.padlink.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * GSFG 风格运动补偿插帧器（v0.29.0 重写）：纯 GLES 3.0 全 GPU，零第三方依赖。
 *
 * **架构参照盖世 GSFG 1.1**（六要素，全部在 fragment shader 里跑）：
 *
 * 1. **三帧识别静止界面**（P2）：用 t-2/t-1/t 三帧亮度双差分出"静止掩码"（血条/准星/
 *    HUD 这类不动的界面），后续光流对这些区域加运动罚，向量被压向 0 —— HUD 不再跟着
 *    背景抖。
 * 2. **上一组运动时序传播**（P3 初始化 + P5/P6 时序 EMA）：上一对帧的最终光流作为
 *    本对帧金字塔粗层的搜索起点，且最终光流再与上组结果按 uTempW 混合 —— 块匹配
 *    向量的帧间跳动（果冻/水波纹）被压平。
 * 3. **主运动金字塔逐层细化 + 小幅运动独立分支**（P3→P4→P5 / P7）：主路从 1/16 网格
 *    粗匹配逐层精修到质量档网格；另一路零起点、小半径、亚像素步进从头估计小幅运动
 *    （不跟背景走的前景/UI 动效），主路粗层吞掉的小位移由它兜住。
 * 4. **融合网络逐像素加权**（P8）：逐格特征（两路代价/静止掩码/双向不一致量/半程
 *    warp 色差/两路分歧/主路位移量）过一层加权求和 + sigmoid 得权重 w，
 *    flowFinal = mix(小幅支路, 主路, w)。权重全部走 uniform（[GsfgWeights]），
 *    当前是手调 v1，后续训练出的真权重可直接替换、shader 不用改。
 * 5. **初步画面引导运动场上采样**（P10 内联）：低分辨率光流上采样到全分辨率时用
 *    draft 初稿帧的亮度做 joint-bilateral（3x3 邻格），边缘处不再跨物体插值。
 * 6. **t 连续任意时刻插帧**（P10）：uT 是连续参数，2x/3x/非整数倍只是取不同 t。
 *
 * **pass 图**（每对帧 P1–P9 一次，P10 每个输出 t 一次）：
 * P1 resolve（源帧→RGBA 全帧+mip）→ P2 staticMask（三帧差分）→
 * P3/P4/P5 主光流金字塔（1/16→1/8→质量档网格，含静区罚/平滑×2/时序 EMA）→
 * P6 反向光流（−主路初始化，L0 精修）→ P7 小幅运动支路 → P8 融合 →
 * P9 draft 初稿（半分辨率）→ P10 合成（引导上采样 + t warp + 三重遮挡回退）。
 *
 * **出帧节奏**：固定 2x 低延迟（每源帧 t=0.5 中间帧 + 真实帧，零等待零 pacing）。
 *
 * **光流编码契约**（全管线统一）：RGBA8 纹理，RG=(向量+64)/128（±64 全分辨率像素、
 * 0.5px 精度），B=匹配代价/255，A=融合权重/validity。纹理是仿射编码，
 * 线性混合编码值 = 混合向量。
 *
 * **光流方向契约**（极易写反，改动 warp 采样符号前必读）：`uFlowF` 存的是**前向流**
 * `f = p_cur − p_prev`（块匹配把 A=prev 的格子往前指到 B=cur，见 [FLOW_SHADER] 的
 * `blockCost(center, flow)` 取 `uTexB(center + flow)`）；`uFlowB` 存的是反向流
 * `b = p_prev − p_cur ≈ −f`，其格子锚在 B 上（所以不一致量判定取 `uFlowB(uv + f)`）。
 * 因此"取上一帧"的采样坐标是 `y − t·f`、"取当前帧"是 `y + (1−t)·f`；
 * 凡是写成 `+t·f` / `−(1−t)·f` 的地方都是符号错误。
 *
 * **线程约定**：所有 GL 调用只能在持有 EGL 上下文的 GL 线程上调用；
 * [enabled] / [quality] / [invalidate] 可从任意线程调（只是 volatile 标志）。
 */
class FrameInterpolator(
    /** 全分辨率帧宽 */
    private val frameWidth: Int,
    /** 全分辨率帧高 */
    private val frameHeight: Int,
    /** true = 源帧是 MJPEG 解码的 RGBA；false = YUY2 打包（RG8） */
    private val rgbaMode: Boolean,
    private val onLog: (String) -> Unit
) {

    /**
     * 插帧质量档位：主光流 L0 网格密度。CLEAR 1/4（细腻）；BALANCED 1/6（默认）；
     * SMOOTH 1/8（省算力）。>1920 宽的档位（4K）自动再降一档密度控开销。
     * 语义即 GSFG 的"运动估计分辨率档"（GSFG 默认 60%）。
     */
    enum class Quality(val gridBase: Int, val label: String) {
        CLEAR(4, "清晰"),
        BALANCED(6, "均衡"),
        SMOOTH(8, "流畅")
    }

    /**
     * 抗拖影档位（v1.0.2）：只调两组权重，不动网格密度。
     *
     * 拖影的两个来源（静态定位）：
     * 1. 遮挡回退判定 `bad` 在 [occlW0, occlW1] 之间是**软混合** —— 区间越宽、低阈值越高，
     *    错配像素残留越多 → 软双影。收窄区间 + 提高低阈值 = 更快地整块回退到真实帧。
     * 2. 时序光流 EMA `tempW` 让光流向上一组结果靠拢 —— 运动方向突变时向量滞后，
     *    中间帧位置偏 → 拖尾。调小它 = 更跟手，代价是果冻感略增。
     *
     * [occl] = [0]低阈值 [1]高阈值 [2]代价增益 [3]不一致量除数；[temp] = 时序 EMA 权重。
     */
    enum class Ghosting(
        val label: String,
        private val occl: FloatArray,
        private val temp: Float
    ) {
        WEAK("弱", floatArrayOf(0.16f, 0.38f, 1.4f, 36f), 0.36f),
        STANDARD("标准", floatArrayOf(0.08f, 0.22f, 2.4f, 20f), 0.20f),
        STRONG("强", floatArrayOf(0.04f, 0.13f, 3.2f, 13f), 0.08f);

        /** 把本档位叠加到基础权重上（只换 occlW/tempW，其余保持） */
        fun apply(base: GsfgWeights): GsfgWeights =
            base.copy(occlW = occl.copyOf(), tempW = temp)
    }

    /**
     * GSFG 风格插帧的全部可调权重（= "网络权重"位）。
     *
     * 当前是手调 v1 默认值；后续用 PyTorch 训练出的真权重按同样字段序平铺即可替换
     * （计划中的 assets gsfg_weights.bin 方案），shader 完全不用改。
     *
     * 融合特征顺序（[fuseW] 与之逐项相乘后求和 + [fuseB] 过 sigmoid 得主路权重 w）：
     * 0 costF   主路匹配代价（0~1，越大越不可信）→ 负权重（转投小幅支路）
     * 1 costS   小幅支路匹配代价 → 正权重（支路不可信就用主路）
     * 2 static  静止掩码（1=静止界面）→ 正权重（静止区用平滑过的主路更稳）
     * 3 incons  双向不一致量 |f+b|/64（越大=遮挡/错配）→ 负权重
     * 4 colorD  半程 warp 色差（0~1，越大=遮挡/新内容）→ 负权重
     * 5 dVS     两路分歧 |vF-vS|/64（分歧大时支路多是局部误匹配）→ 正权重
     * 6 magF    主路位移量 |vF|/64（全局运动大时主路更可信）→ 正权重
     */
    data class GsfgWeights(
        /** 融合 7 特征权重（见类注释的特征顺序） */
        val fuseW: FloatArray = floatArrayOf(-6f, 6f, 4f, -4f, -3f, 3f, 2f),
        /** 融合偏置（正 = 基线偏向主路） */
        val fuseB: Float = 2f,
        /** 代价加权平滑：格子权重 = 1/(bias + cost*gain) */
        val smoothW: FloatArray = floatArrayOf(0.02f, 4f),
        /** 引导上采样：draft 亮度差衰减系数（越大越锐利） */
        val guideW: Float = 8f,
        /** [0]=静止判定阈值（字节域亮度差），[1]=静区运动罚增益 */
        val staticW: FloatArray = floatArrayOf(8f, 3f),
        /** 遮挡三重判定：[0]低阈值 [1]高阈值 [2]代价增益 [3]不一致量除数 */
        val occlW: FloatArray = floatArrayOf(0.12f, 0.30f, 1.8f, 28f),
        /** 时序传播 EMA：最终光流 = mix(本帧, 上组最终, 该值) */
        val tempW: Float = 0.3f
    )

    /** 全部可调权重（未来训练权重直接替换这里/由外部注入；抗拖影档位会改写 occlW/tempW） */
    @Volatile
    var weights = Ghosting.STANDARD.apply(GsfgWeights())

    /**
     * 抗拖影档位（任意线程可写）：只换 [weights] 引用，GL 线程下一帧生效，不重建网格资源。
     * 默认 [Ghosting.STANDARD]，比 v1.0.1 的原始手调值更抗拖影。
     */
    @Volatile
    var ghosting: Ghosting = Ghosting.STANDARD
        set(value) {
            field = value
            weights = value.apply(weights)
        }

    /** 插帧开关（任意线程可写，GL 线程每帧读） */
    @Volatile
    var enabled = false

    /** 质量档位（任意线程可写；变化时 GL 线程下一帧重建网格资源） */
    @Volatile
    var quality: Quality = Quality.BALANCED
        set(value) {
            if (field != value) {
                field = value
                rebuildRequested = true
            }
        }

    /** 档位变化后请求重建（网格尺寸变了，纹理/FBO 必须重开） */
    @Volatile
    private var rebuildRequested = false

    /** 请求丢弃"上一帧"记忆（关开关/换档/换内容时调，防止拿旧内容插值） */
    @Volatile
    private var resetRequested = true

    /** GL 资源是否已建好（GL 线程） */
    private var initialized = false

    /** 初始化失败后不再重试，回退直绘通路（GL 线程） */
    private var failed = false

    /** 是否有"上一帧"可参与插值（GL 线程） */
    private var hasPrev = false

    // ---- 程序（全部 GL 线程私有） ----
    private var progResolve = 0
    private var progStatic = 0
    private var progFlow = 0
    private var progSmooth = 0
    private var progBlend = 0
    private var progFuse = 0
    private var progDraft = 0
    private var progInterp = 0
    private var progDraw = 0

    // ---- 全帧槽位（3 槽轮转：cur / prev / prevPrev，三帧静止检测用） ----
    private val frameTex = IntArray(3)
    private val frameFbo = IntArray(3)
    private var slotCur = 0
    private var slotPrev = -1
    private var slotPP = -1

    /** 三帧亮度史攒够（≥t-2 一帧）才出有效静止掩码 */
    private var lumaHistReady = 0

    // ---- 光流网格纹理 ----
    /** 主路 L0 ping-pong（平滑×2） */
    private var texFlowF0 = 0
    private var texFlowF1 = 0
    /** 反向 L0 ping-pong */
    private var texFlowB0 = 0
    private var texFlowB1 = 0
    /** 小幅运动支路 ping-pong */
    private var texFlowS0 = 0
    private var texFlowS1 = 0
    /** 主路金字塔中间层（L2 粗 / L1 中） */
    private var texFlowL2 = 0
    private var texFlowL1 = 0
    /** 融合结果（A 通道 = 主路权重 w） */
    private var texFlowFinal = 0
    /** 上一对帧的最终光流（时序传播锚点，前向/反向各一对；ping-pong 防"读写同一张纹理"的 GL 反馈回路） */
    private val texFlowAnchorF = IntArray(2)
    private val texFlowAnchorB = IntArray(2)

    private var texStatic = 0
    private var texDraft = 0

    private var fboFlowF0 = 0
    private var fboFlowF1 = 0
    private var fboFlowB0 = 0
    private var fboFlowB1 = 0
    private var fboFlowS0 = 0
    private var fboFlowS1 = 0
    private var fboFlowL2 = 0
    private var fboFlowL1 = 0
    private var fboFlowFinal = 0
    private val fboAnchorF = IntArray(2)
    private val fboAnchorB = IntArray(2)
    private var fboStatic = 0
    private var fboDraft = 0

    /** 锚点读/写槽位下标：读 [anchorIdx]、写 [1-anchorIdx]，每帧交换（ping-pong） */
    private var anchorIdx = 0

    /** 时序传播锚点是否已有合法内容（reset 后首对帧不混旧光流） */
    private var flowPrimed = false

    /** 主光流 L0 网格尺寸（随档位变化，rebuild 时重算） */
    private var flowDiv = 6
    private var flowW = 1
    private var flowH = 1

    /** 格子中心 → 全分辨率像素的换算比例 */
    private var scaleX = 1f
    private var scaleY = 1f

    // ---- 统计（每 [STATS_EVERY] 帧一次 readback + 逐 pass 计时） ----
    private var frameCount = 0L
    private var statFlowMs = 0f
    private var statPassMs = FloatArray(8)
    private var statBufFlow: ByteBuffer? = null
    private var statBufStatic: ByteBuffer? = null

    /** 丢弃上一帧记忆（任意线程可调） */
    fun invalidate() {
        resetRequested = true
    }

    /**
     * 确保 GL 资源就绪；首次调用时建纹理/程序/FBO，档位变化时先重建。
     * 失败返回 false（调用方回退直绘）。只能在 GL 线程调用。
     */
    fun ensureReady(): Boolean {
        if (initialized && rebuildRequested) {
            rebuildRequested = false
            onLog(">>> [插帧] 切换档位 → ${quality.label}，重建运动估计网格…\n")
            release()
        }
        if (initialized) return true
        if (failed) return false
        initialized = true
        val ok = try {
            setupGl()
        } catch (t: Throwable) {
            onLog("!! [插帧] 初始化异常: $t\n")
            false
        }
        if (!ok) {
            // 初始化失败必须把 initialized 也清掉：否则下次 ensureReady 会因
            // "initialized==true" 误报就绪，拿着 0 号纹理画到黑屏
            initialized = false
            failed = true
            onLog("!! [插帧] 初始化失败，本会话回退直绘通路\n")
            return false
        }
        onLog(
            ">>> [插帧] GSFG 管线就绪（${quality.label}档）：主光流网格 ${flowW}x${flowH}（全分辨率 1/${flowDiv}）" +
                "｜金字塔 1/${flowDiv * 4}→1/${flowDiv * 2}→1/${flowDiv} 逐层细化｜三帧静止 UI 掩码｜小幅运动支路" +
                "｜融合加权｜draft 引导上采样｜t 参数化合成\n"
        )
        return true
    }

    /** 把上传纹理解析成 RGBA 全帧并落到"当前帧"槽。GL 线程调用。 */
    fun resolve(srcTexture: Int) {
        if (resetRequested) {
            resetRequested = false
            hasPrev = false
            // 断档后历史全部作废：亮度史/光流锚点与新内容没有连续性
            lumaHistReady = 0
            flowPrimed = false
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo[slotCur])
        GLES30.glViewport(0, 0, frameWidth, frameHeight)
        GLES30.glUseProgram(progResolve)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexture)
        GLES30.glUniform1i(uLoc(progResolve, "uTex"), 0)
        GLES30.glUniform2i(uLoc(progResolve, "uDim"), frameWidth, frameHeight)
        drawTriangle()
        // 先解绑 FBO 再给纹理生成 mip（避免"纹理挂在已绑定 FBO 上被改动"的驱动兼容问题）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // 光流金字塔各层在 mip 上做块匹配，当前帧必须现场生成 mip 链
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotCur])
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        if (lumaHistReady < 2) lumaHistReady++
    }

    /** 是否已有上一帧（false 时只能画真实帧） */
    val canInterpolate: Boolean
        get() = hasPrev

    /** GL 资源初始化是否已失败（失败后本会话回退直绘；供 [上屏] 日志行展示通路状态） */
    val initFailed: Boolean
        get() = failed

    /**
     * 计算"上一帧 → 当前帧"这一对帧的 GSFG 光流管线（P2–P9）。
     * 每对帧只调一次，之后 [drawInterpolatedAt] 可以按不同 t 反复出帧。GL 线程调用。
     */
    fun computeFlow() {
        val t0 = System.nanoTime()
        val timing = frameCount % STATS_EVERY == 0L
        var acc = t0

        // ---- P2 三帧静止掩码（静止 UI 识别） ----
        passStatic()
        if (timing) { acc = mark("static", 0, acc) }

        // ---- P3/P4/P5 主路金字塔：1/16 粗 → 1/8 中 → L0 细（GSFG"低分辨率逐层细化"） ----
        // 起点 = 上一组最终光流（时序传播）；无锚点时从 0 起步
        passFlow(
            fboFlowL2, l2w(), l2h(), texFlowAnchorF[anchorIdx], flowPrimed, false, true,
            RADIUS_L2, LOD_L2, TAP_L2, 0f
        )
        passFlow(
            fboFlowL1, l1w(), l1h(), texFlowL2, true, false, true,
            RADIUS_L1, LOD_L1, TAP_L1, 0f
        )
        passFlow(
            fboFlowF0, flowW, flowH, texFlowL1, true, false, true,
            RADIUS_L0, LOD_L0, TAP_L0, weights.staticW[1]
        )
        if (timing) { acc = mark("主路金字塔", 1, acc) }

        // ---- 代价加权平滑 ×2（压野向量）+ 时序 EMA（压帧间抖动 = 果冻） ----
        passSmooth(texFlowF0, fboFlowF1)
        passSmooth(texFlowF1, fboFlowF0)
        val w = if (flowPrimed) weights.tempW else 0f
        passBlend(texFlowF0, texFlowAnchorF[anchorIdx], fboFlowF1, w)
        if (timing) { acc = mark("主路平滑+时序", 2, acc) }

        // ---- P6 反向光流：−前向最终流初始化，L0 单层精修（遮挡判定 |f+b| 用） ----
        passFlow(
            fboFlowB0, flowW, flowH, texFlowF1, true, true, false,
            RADIUS_L0, LOD_L0, TAP_L0, 0f
        )
        passSmooth(texFlowB0, fboFlowB1)
        passSmooth(texFlowB1, fboFlowB0)
        passBlend(texFlowB0, texFlowAnchorB[anchorIdx], fboFlowB1, w)
        if (timing) { acc = mark("反向光流", 3, acc) }

        // ---- P7 小幅运动支路：零起点 + 小半径 + 亚像素步进（GSFG"小幅运动"分支） ----
        passFlow(
            fboFlowS0, flowW, flowH, texFlowF1, false, false, true,
            RADIUS_S, LOD_S, TAP_S, 0f
        )
        passSmooth(texFlowS0, fboFlowS1)
        passSmooth(texFlowS1, fboFlowS0)
        if (timing) { acc = mark("小幅支路", 4, acc) }

        // ---- P8 融合加权（GSFG"融合网络"，权重 = GsfgWeights） ----
        passFuse()
        if (timing) { acc = mark("融合", 5, acc) }

        // ---- P9 draft 初稿（半分辨率，P10 引导上采样的引导源） ----
        passDraft()
        if (timing) { acc = mark("draft", 6, acc) }

        // ---- 时序锚点转正：最终光流拷进锚点新槽（读旧写新，避免 GL 读写同纹理），换槽 ----
        val nxt = 1 - anchorIdx
        passBlend(texFlowF1, texFlowAnchorF[anchorIdx], fboAnchorF[nxt], 0f)
        passBlend(texFlowB1, texFlowAnchorB[anchorIdx], fboAnchorB[nxt], 0f)
        anchorIdx = nxt
        flowPrimed = true
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // ---- 常态统计：每 120 帧一次低频 readback + 汇总（验证 HUD 稳定性不靠肉眼） ----
        frameCount++
        if (timing) {
            statFlowMs = ((System.nanoTime() - t0) / 1e6).toFloat()
            reportStats()
        }
    }

    /**
     * 画 t 时刻的合成帧（0 < t < 1）。
     * 内部会自己 clear + 设视口；调用方在之后 swap。GL 线程调用。
     */
    fun drawInterpolatedAt(t: Float, vx: Int, vy: Int, vw: Int, vh: Int) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glUseProgram(progInterp)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotPrev])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotCur])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowFinal)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowB1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texDraft)
        GLES30.glUniform1i(uLoc(progInterp, "uTexA"), 1)
        GLES30.glUniform1i(uLoc(progInterp, "uTexB"), 2)
        GLES30.glUniform1i(uLoc(progInterp, "uFlowF"), 3)
        GLES30.glUniform1i(uLoc(progInterp, "uFlowB"), 4)
        GLES30.glUniform1i(uLoc(progInterp, "uDraft"), 5)
        GLES30.glUniform2f(uLoc(progInterp, "uFullDim"), frameWidth.toFloat(), frameHeight.toFloat())
        GLES30.glUniform2f(uLoc(progInterp, "uGridDim"), flowW.toFloat(), flowH.toFloat())
        GLES30.glUniform2f(uLoc(progInterp, "uOrigin"), vx.toFloat(), vy.toFloat())
        GLES30.glUniform2f(uLoc(progInterp, "uSize"), vw.toFloat(), vh.toFloat())
        GLES30.glUniform1f(uLoc(progInterp, "uT"), t)
        GLES30.glUniform1f(uLoc(progInterp, "uGuideW"), weights.guideW)
        GLES30.glUniform4f(
            uLoc(progInterp, "uOcclW"),
            weights.occlW[0], weights.occlW[1], weights.occlW[2], weights.occlW[3]
        )
        drawTriangle()
    }

    /**
     * 画真实当前帧（不做任何 warp）。
     * 每次 swap 后后缓冲内容未定义，所以必须自己 clear（黑边区靠 clear 补）。GL 线程调用。
     */
    fun drawReal(vx: Int, vy: Int, vw: Int, vh: Int) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glUseProgram(progDraw)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotCur])
        GLES30.glUniform1i(uLoc(progDraw, "uTex"), 1)
        GLES30.glUniform2i(uLoc(progDraw, "uDim"), frameWidth, frameHeight)
        GLES30.glUniform2f(uLoc(progDraw, "uOrigin"), vx.toFloat(), vy.toFloat())
        GLES30.glUniform2f(uLoc(progDraw, "uSize"), vw.toFloat(), vh.toFloat())
        drawTriangle()
    }

    /**
     * 一帧结束：三槽轮转（cur→prev→prevPrev），不搬数据。
     * 旧 prevPrev 槽位成为下一帧的"当前帧"待写槽。GL 线程调用。
     */
    fun endFrame() {
        slotPP = slotPrev
        slotPrev = slotCur
        slotCur = (slotCur + 1) % 3
        hasPrev = true
    }

    /** 释放全部 GL 资源（GL 线程调用，EGL 上下文还活着时） */
    fun release() {
        for (p in intArrayOf(
            progResolve, progStatic, progFlow, progSmooth, progBlend,
            progFuse, progDraft, progInterp, progDraw
        )) {
            if (p != 0) GLES30.glDeleteProgram(p)
        }
        progResolve = 0; progStatic = 0; progFlow = 0; progSmooth = 0; progBlend = 0
        progFuse = 0; progDraft = 0; progInterp = 0; progDraw = 0
        val tex = (frameTex + texFlowAnchorF + texFlowAnchorB + intArrayOf(
            texFlowF0, texFlowF1, texFlowB0, texFlowB1, texFlowS0, texFlowS1,
            texFlowL2, texFlowL1, texFlowFinal,
            texStatic, texDraft
        )).filter { it != 0 }.toIntArray()
        if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
        frameTex.fill(0)
        texFlowF0 = 0; texFlowF1 = 0; texFlowB0 = 0; texFlowB1 = 0
        texFlowS0 = 0; texFlowS1 = 0; texFlowL2 = 0; texFlowL1 = 0
        texFlowFinal = 0
        texFlowAnchorF.fill(0); texFlowAnchorB.fill(0)
        texStatic = 0; texDraft = 0
        val fbo = (frameFbo + fboAnchorF + fboAnchorB + intArrayOf(
            fboFlowF0, fboFlowF1, fboFlowB0, fboFlowB1, fboFlowS0, fboFlowS1,
            fboFlowL2, fboFlowL1, fboFlowFinal, fboStatic, fboDraft
        )).filter { it != 0 }.toIntArray()
        if (fbo.isNotEmpty()) GLES30.glDeleteFramebuffers(fbo.size, fbo, 0)
        frameFbo.fill(0)
        fboFlowF0 = 0; fboFlowF1 = 0; fboFlowB0 = 0; fboFlowB1 = 0
        fboFlowS0 = 0; fboFlowS1 = 0; fboFlowL2 = 0; fboFlowL1 = 0
        fboFlowFinal = 0
        fboAnchorF.fill(0); fboAnchorB.fill(0)
        fboStatic = 0; fboDraft = 0
        initialized = false
        failed = false
        hasPrev = false
        flowPrimed = false
        lumaHistReady = 0
        resetRequested = true
    }

    // ==================== GL 资源构建 ====================

    private fun setupGl(): Boolean {
        // 网格密度随档位；4K 档自动再降一档密度控算力
        flowDiv = quality.gridBase + (if (frameWidth > 1920) 2 else 0)
        flowW = (frameWidth + flowDiv - 1) / flowDiv
        flowH = (frameHeight + flowDiv - 1) / flowDiv
        scaleX = frameWidth.toFloat() / flowW
        scaleY = frameHeight.toFloat() / flowH

        progResolve = buildProgram(
            VERTEX_SHADER,
            if (rgbaMode) RESOLVE_SHADER_RGBA else RESOLVE_SHADER_YUY2
        )
        if (progResolve == 0) return false
        progStatic = buildProgram(VERTEX_SHADER, STATIC_SHADER)
        if (progStatic == 0) return false
        progFlow = buildProgram(VERTEX_SHADER, FLOW_SHADER)
        if (progFlow == 0) return false
        progSmooth = buildProgram(VERTEX_SHADER, SMOOTH_SHADER)
        if (progSmooth == 0) return false
        progBlend = buildProgram(VERTEX_SHADER, BLEND_SHADER)
        if (progBlend == 0) return false
        progFuse = buildProgram(VERTEX_SHADER, FUSE_SHADER)
        if (progFuse == 0) return false
        progDraft = buildProgram(VERTEX_SHADER, DRAFT_SHADER)
        if (progDraft == 0) return false
        progInterp = buildProgram(VERTEX_SHADER, INTERP_SHADER)
        if (progInterp == 0) return false
        progDraw = buildProgram(VERTEX_SHADER, DRAW_SHADER)
        if (progDraw == 0) return false

        for (i in 0..2) {
            frameTex[i] = createFrameTex()
            frameFbo[i] = createFbo(frameTex[i])
            if (frameFbo[i] == 0) return false
        }
        slotCur = 0
        slotPrev = -1
        slotPP = -1

        texFlowF0 = createFlowTex(flowW, flowH)
        texFlowF1 = createFlowTex(flowW, flowH)
        texFlowB0 = createFlowTex(flowW, flowH)
        texFlowB1 = createFlowTex(flowW, flowH)
        texFlowS0 = createFlowTex(flowW, flowH)
        texFlowS1 = createFlowTex(flowW, flowH)
        texFlowL2 = createFlowTex(l2w(), l2h())
        texFlowL1 = createFlowTex(l1w(), l1h())
        texFlowFinal = createFlowTex(flowW, flowH)
        texFlowAnchorF[0] = createFlowTex(flowW, flowH)
        texFlowAnchorF[1] = createFlowTex(flowW, flowH)
        texFlowAnchorB[0] = createFlowTex(flowW, flowH)
        texFlowAnchorB[1] = createFlowTex(flowW, flowH)
        texStatic = createRTex(flowW, flowH)
        texDraft = createFrameTex(frameWidth / 2, frameHeight / 2)

        fboFlowF0 = createFbo(texFlowF0)
        fboFlowF1 = createFbo(texFlowF1)
        fboFlowB0 = createFbo(texFlowB0)
        fboFlowB1 = createFbo(texFlowB1)
        fboFlowS0 = createFbo(texFlowS0)
        fboFlowS1 = createFbo(texFlowS1)
        fboFlowL2 = createFbo(texFlowL2)
        fboFlowL1 = createFbo(texFlowL1)
        fboFlowFinal = createFbo(texFlowFinal)
        fboAnchorF[0] = createFbo(texFlowAnchorF[0])
        fboAnchorF[1] = createFbo(texFlowAnchorF[1])
        fboAnchorB[0] = createFbo(texFlowAnchorB[0])
        fboAnchorB[1] = createFbo(texFlowAnchorB[1])
        fboStatic = createFbo(texStatic)
        fboDraft = createFbo(texDraft)

        anchorIdx = 0
        flowPrimed = false
        lumaHistReady = 0
        statBufFlow = ByteBuffer.allocateDirect(flowW * flowH * 4).order(ByteOrder.nativeOrder())
        statBufStatic = ByteBuffer.allocateDirect(flowW * flowH).order(ByteOrder.nativeOrder())
        return fboFlowF0 != 0 && fboFlowF1 != 0 && fboFlowB0 != 0 && fboFlowB1 != 0 &&
            fboFlowS0 != 0 && fboFlowS1 != 0 && fboFlowL2 != 0 && fboFlowL1 != 0 &&
            fboFlowFinal != 0 &&
            fboAnchorF[0] != 0 && fboAnchorF[1] != 0 && fboAnchorB[0] != 0 && fboAnchorB[1] != 0 &&
            fboStatic != 0 && fboDraft != 0
    }

    /** 金字塔中间层网格尺寸（整除保护：至少 1） */
    private fun l1w() = ((flowW + 1) / 2).coerceAtLeast(1)
    private fun l1h() = ((flowH + 1) / 2).coerceAtLeast(1)
    private fun l2w() = ((flowW + 3) / 4).coerceAtLeast(1)
    private fun l2h() = ((flowH + 3) / 4).coerceAtLeast(1)

    /** 全帧 RGBA8 纹理：warp 需要双线性 + mip（金字塔块匹配读 mip） */
    private fun createFrameTex(w: Int = frameWidth, h: Int = frameHeight): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        return t[0]
    }

    /** 光流网格纹理：RGBA8（RG=向量编码，B=代价，A=融合权重），线性采样供层间初始化 */
    private fun createFlowTex(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        return t[0]
    }

    /** 单通道 R8 纹理（静止掩码） */
    private fun createRTex(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null
        )
        return t[0]
    }

    private fun createFbo(tex: Int): Int {
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            onLog("!! [插帧] FBO 不完整 status=0x${Integer.toHexString(status)}\n")
            return 0
        }
        return f[0]
    }

    // ==================== 各 pass ====================

    /** P2 三帧静止掩码：三帧亮度双差分，连续三帧都稳的区域 = 静止 UI */
    private fun passStatic() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboStatic)
        GLES30.glViewport(0, 0, flowW, flowH)
        if (lumaHistReady < 2 || slotPP < 0) {
            // 历史不够（刚 reset/冷启动）：掩码清零（= 全部当运动区，不加静止约束）
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES30.glUseProgram(progStatic)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotPP])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotPrev])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotCur])
        GLES30.glUniform1i(uLoc(progStatic, "uPP"), 1)
        GLES30.glUniform1i(uLoc(progStatic, "uPrev"), 2)
        GLES30.glUniform1i(uLoc(progStatic, "uCur"), 3)
        GLES30.glUniform2f(uLoc(progStatic, "uGridDim"), flowW.toFloat(), flowH.toFloat())
        GLES30.glUniform1f(uLoc(progStatic, "uTh"), weights.staticW[0])
        drawTriangle()
    }

    /**
     * 通用环形块匹配光流 pass（P3/P4/P5/P6/P7 共用，uniform 控制层/初始化/罚项）。
     * forward=true：uTexA=上一帧、uTexB=当前帧（位移 = 前向）；
     * forward=false：两纹理对调（位移 = 反向）。
     */
    private fun passFlow(
        outFbo: Int, outW: Int, outH: Int,
        initTex: Int, useInit: Boolean, negInit: Boolean, forward: Boolean,
        radius: Float, lod: Float, tap: Float, staticPenalty: Float
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(progFlow)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            if (forward) frameTex[slotPrev] else frameTex[slotCur]
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            if (forward) frameTex[slotCur] else frameTex[slotPrev]
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, initTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texStatic)
        GLES30.glUniform1i(uLoc(progFlow, "uTexA"), 1)
        GLES30.glUniform1i(uLoc(progFlow, "uTexB"), 2)
        GLES30.glUniform1i(uLoc(progFlow, "uInit"), 3)
        GLES30.glUniform1i(uLoc(progFlow, "uStatic"), 4)
        GLES30.glUniform1f(uLoc(progFlow, "uUseInit"), if (useInit) 1f else 0f)
        GLES30.glUniform1f(uLoc(progFlow, "uNegInit"), if (negInit) 1f else 0f)
        GLES30.glUniform1f(uLoc(progFlow, "uRadius"), radius)
        GLES30.glUniform1f(uLoc(progFlow, "uLod"), lod)
        GLES30.glUniform1f(uLoc(progFlow, "uTap"), tap)
        GLES30.glUniform1f(uLoc(progFlow, "uStaticW"), staticPenalty)
        GLES30.glUniform2f(uLoc(progFlow, "uFullDim"), frameWidth.toFloat(), frameHeight.toFloat())
        GLES30.glUniform2f(uLoc(progFlow, "uScale"), frameWidth.toFloat() / outW, frameHeight.toFloat() / outH)
        drawTriangle()
    }

    /** 代价加权平滑 pass：inTex → outFbo */
    private fun passSmooth(inTex: Int, outFbo: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, flowW, flowH)
        GLES30.glUseProgram(progSmooth)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex)
        GLES30.glUniform1i(uLoc(progSmooth, "uFlow"), 3)
        GLES30.glUniform2i(uLoc(progSmooth, "uFlowDim"), flowW, flowH)
        GLES30.glUniform2f(uLoc(progSmooth, "uSmoothW"), weights.smoothW[0], weights.smoothW[1])
        drawTriangle()
    }

    /** 时间平滑 pass：out = mix(cur, prev, prevWeight)；prevWeight=0 即纯拷贝 cur→out */
    private fun passBlend(curTex: Int, prevTex: Int, outFbo: Int, prevWeight: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, flowW, flowH)
        GLES30.glUseProgram(progBlend)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex)
        GLES30.glUniform1i(uLoc(progBlend, "uCur"), 3)
        GLES30.glUniform1i(uLoc(progBlend, "uPrev"), 4)
        GLES30.glUniform1f(uLoc(progBlend, "uPrevWeight"), prevWeight)
        drawTriangle()
    }

    /** P8 融合加权：主路/小幅支路按 sigmoid(feat·uFuseW + uFuseB) 逐格混合 */
    private fun passFuse() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowFinal)
        GLES30.glViewport(0, 0, flowW, flowH)
        GLES30.glUseProgram(progFuse)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowF1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowS0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowB1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texStatic)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotPrev])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotCur])
        GLES30.glUniform1i(uLoc(progFuse, "uFlowF"), 1)
        GLES30.glUniform1i(uLoc(progFuse, "uFlowS"), 2)
        GLES30.glUniform1i(uLoc(progFuse, "uFlowB"), 3)
        GLES30.glUniform1i(uLoc(progFuse, "uStatic"), 4)
        GLES30.glUniform1i(uLoc(progFuse, "uTexA"), 5)
        GLES30.glUniform1i(uLoc(progFuse, "uTexB"), 6)
        GLES30.glUniform2f(uLoc(progFuse, "uFullDim"), frameWidth.toFloat(), frameHeight.toFloat())
        GLES30.glUniform2f(uLoc(progFuse, "uGridDim"), flowW.toFloat(), flowH.toFloat())
        GLES30.glUniform1fv(uLoc(progFuse, "uFuseW[0]"), FUSE_DIM, weights.fuseW, 0)
        GLES30.glUniform1f(uLoc(progFuse, "uFuseB"), weights.fuseB)
        drawTriangle()
    }

    /** P9 draft 初稿：最终光流双向 warp 半程融合（P10 的引导源） */
    private fun passDraft() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboDraft)
        GLES30.glViewport(0, 0, frameWidth / 2, frameHeight / 2)
        GLES30.glUseProgram(progDraft)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotPrev])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[slotCur])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowFinal)
        GLES30.glUniform1i(uLoc(progDraft, "uTexA"), 1)
        GLES30.glUniform1i(uLoc(progDraft, "uTexB"), 2)
        GLES30.glUniform1i(uLoc(progDraft, "uFlow"), 3)
        GLES30.glUniform2f(uLoc(progDraft, "uFullDim"), frameWidth.toFloat(), frameHeight.toFloat())
        drawTriangle()
    }

    // ==================== 统计 ====================

    /** 逐 pass 计时打点（每 120 帧的那一帧才 glFinish 精测，其余帧零开销） */
    private fun mark(name: String, idx: Int, prevNs: Long): Long {
        GLES30.glFinish()
        val now = System.nanoTime()
        val ms = ((now - prevNs) / 1e6).toFloat()
        statPassMs[idx] = if (statPassMs[idx] <= 0f) ms else statPassMs[idx] * 0.7f + ms * 0.3f
        return now
    }

    /**
     * 每 120 帧一行量化统计（验证 HUD 稳定性/果冻不靠肉眼）：
     * 静止占比 / 静区平均 |v|（应≈0）/ 全场平均 |v| / 双向不一致量 / 各 pass 耗时 EMA。
     */
    private fun reportStats() {
        val bufF = statBufFlow ?: return
        val bufS = statBufStatic ?: return
        // R8 行宽非 4 倍数时默认 4 字节行填充会写爆缓冲，先关对齐；position 归零防偏移
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        bufF.position(0)
        bufS.position(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowFinal)
        GLES30.glReadPixels(0, 0, flowW, flowH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bufF)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboStatic)
        GLES30.glReadPixels(0, 0, flowW, flowH, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bufS)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        var staticCnt = 0
        var staticMag = 0.0
        var allMag = 0.0
        val n = flowW * flowH
        for (i in 0 until n) {
            val fx = bufF.get(i * 4).toInt() and 0xFF
            val fy = bufF.get(i * 4 + 1).toInt() and 0xFF
            val sm = (bufS.get(i).toInt() and 0xFF) / 255f
            val mag = Math.hypot((fx - 128) / 2.0, (fy - 128) / 2.0)
            allMag += mag
            if (sm > 0.5f) {
                staticCnt++
                staticMag += mag
            }
        }
        val staticPct = if (n > 0) staticCnt * 100f / n else 0f
        val staticAvg = if (staticCnt > 0) staticMag / staticCnt else 0.0
        val allAvg = if (n > 0) allMag / n else 0.0

        onLog(
            String.format(
                Locale.US,
                ">>> [插帧] 120帧统计：静止占比 %.1f%%｜静区平均|v| %.2fpx｜全场平均|v| %.2fpx｜" +
                    "光流管线 %.2fms（静止掩码 %.2f / 金字塔 %.2f / 平滑 %.2f / 反向 %.2f / 支路 %.2f / 融合 %.2f / draft %.2f）\n",
                staticPct, staticAvg, allAvg,
                statFlowMs, statPassMs[0], statPassMs[1], statPassMs[2],
                statPassMs[3], statPassMs[4], statPassMs[5], statPassMs[6]
            )
        )
    }

    // ==================== GL 基建 ====================

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return 0
        }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            onLog("!! [插帧] 着色器链接失败: ${GLES30.glGetProgramInfoLog(prog)}\n")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            onLog("!! [插帧] 着色器编译失败: ${GLES30.glGetShaderInfoLog(shader)}\n")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun uLoc(prog: Int, name: String): Int = GLES30.glGetUniformLocation(prog, name)

    /** 全屏大三角（gl_VertexID 现算，无 VBO） */
    private fun drawTriangle() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    companion object {
        /** 融合特征维度（与 [GsfgWeights.fuseW] 一一对应） */
        private const val FUSE_DIM = 7

        /** 统计节拍：每多少帧做一次逐 pass 计时 + 低频 readback */
        private const val STATS_EVERY = 120L

        // ---- 金字塔各层搜索参数（全分辨率像素） ----
        /** L2 粗层（网格 1/16）：大环覆盖 ±32px/帧，配合锚点传播 */
        private const val RADIUS_L2 = 32f
        private const val LOD_L2 = 3f
        private const val TAP_L2 = 16f
        /** L1 中层（网格 1/8）：小环精修 */
        private const val RADIUS_L1 = 4f
        private const val LOD_L1 = 2f
        private const val TAP_L1 = 8f
        /** L0 细层（质量档网格）：微调 + 静区运动罚 */
        private const val RADIUS_L0 = 2f
        private const val LOD_L0 = 1f
        private const val TAP_L0 = 4f
        /** 小幅运动支路：零起点、小半径、亚像素（0.5px 步进由环 0.5 兜底） */
        private const val RADIUS_S = 2f
        private const val LOD_S = 0f
        private const val TAP_S = 4f

        /**
         * 顶点着色器：gl_VertexID 生成覆盖视口的大三角形（与主渲染器同一技巧）。
         */
        private const val VERTEX_SHADER = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        /**
         * 解析（YUY2 → RGBA 全帧）：源纹理是打包的 RG8（每 texel = Y_px + Cb/Cr），
         * 字节域 BT.709 有限范围转 RGB；py 做一次翻转，FBO 纹理从此统一
         * v=0 = 画面底部。与主渲染器 YUY2 着色器同一套系数（v0.12.0 的教训：
         * texelFetch 是归一化值，必须先 *255 还原字节域）。
         */
        private const val RESOLVE_SHADER_YUY2 = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform ivec2 uDim;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int px = p.x;
    int sy = uDim.y - 1 - p.y;
    int pairBase = (px >> 1) << 1;
    float yEven = texelFetch(uTex, ivec2(pairBase, sy), 0).r * 255.0;
    float cb = texelFetch(uTex, ivec2(pairBase, sy), 0).g * 255.0;
    float yOdd = texelFetch(uTex, ivec2(pairBase + 1, sy), 0).r * 255.0;
    float cr = texelFetch(uTex, ivec2(pairBase + 1, sy), 0).g * 255.0;
    float y = ((px & 1) == 0) ? yEven : yOdd;
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

        /** 解析（RGBA 拷贝 + 翻转）：MJPEG 通路的源纹理已是 RGBA，只统一行序 */
        private const val RESOLVE_SHADER_RGBA = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform ivec2 uDim;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    outColor = texelFetch(uTex, ivec2(p.x, uDim.y - 1 - p.y), 0);
}
"""

        /**
         * P2 三帧静止掩码（GSFG"识别保持静止的界面区域"）：
         * t-2/t-1/t 三帧亮度双相邻差分，两次都几乎不动的区域 = 静止 UI（血条/准星/HUD）。
         * 输出 R8：1=静止，0=运动。亮度差超 uTh（字节域）即判运动，半阈值内平滑过渡。
         */
        private const val STATIC_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uPP;
uniform sampler2D uPrev;
uniform sampler2D uCur;
uniform vec2 uGridDim;
uniform float uTh;
out vec4 outColor;

float luma(sampler2D tex, vec2 uv) {
    return dot(texture(tex, uv).rgb, vec3(0.299, 0.587, 0.114)) * 255.0;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uGridDim;
    float lpp = luma(uPP, uv);
    float lp = luma(uPrev, uv);
    float lc = luma(uCur, uv);
    float d = max(abs(lpp - lp), abs(lp - lc));
    float mask = 1.0 - smoothstep(uTh * 0.5, uTh, d);
    outColor = vec4(mask, 0.0, 0.0, 1.0);
}
"""

        /**
         * 通用环形块匹配光流（P3/P4/P5/P6/P7 共用）。
         *
         * 初始化（uUseInit/uNegInit）：起点 = 锚点流（时序传播）或上层金字塔流，
         * 反向层取负；三环精修 r、r/2、r/4 各 8 方向。
         * 静区运动罚（uStaticW）：候选位移越大、掩码越"静止"，代价加得越多 ——
         * 静止 UI 的向量被压向 0（GSFG"约束这些区域的运动估计"）。
         *
         * 编码契约：RG = (位移 + 64) / 128（±64 全分辨率像素、0.5px 精度），
         * B = 最优匹配代价/255，A = 1。块匹配用双线性采样，天然支持小数位移。
         */
        private const val FLOW_SHADER = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform sampler2D uInit;
uniform sampler2D uStatic;
uniform float uUseInit;
uniform float uNegInit;
uniform float uRadius;
uniform float uLod;
uniform float uTap;
uniform float uStaticW;
uniform vec2 uFullDim;
uniform vec2 uScale;
out vec4 outColor;

const vec2 DIRS[8] = vec2[8](
    vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0),
    vec2(0.7071, 0.7071), vec2(-0.7071, 0.7071),
    vec2(0.7071, -0.7071), vec2(-0.7071, -0.7071)
);

float lumaAt(sampler2D tex, vec2 posFull, float lod) {
    vec2 uv = (posFull + 0.5) / uFullDim;
    vec3 c = textureLod(tex, uv, lod).rgb;
    return dot(c, vec3(0.299, 0.587, 0.114)) * 255.0;
}

float blockCost(vec2 center, vec2 flow, float lod) {
    float s = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 t = vec2(float(i), float(j)) * uTap;
            s += abs(lumaAt(uTexA, center + t, lod) - lumaAt(uTexB, center + t + flow, lod));
        }
    }
    return s / 9.0;
}

void main() {
    vec2 q = gl_FragCoord.xy - 0.5;
    vec2 center = (q + 0.5) * uScale;

    // 起点：初始化流（锚点/上层，线性采样跨网格尺寸）或 0
    vec2 best = vec2(0.0);
    if (uUseInit > 0.5) {
        vec2 uv = (q + 0.5) * uScale / uFullDim;
        best = texture(uInit, uv).rg * 128.0 - 64.0;
        if (uNegInit > 0.5) best = -best;
    }

    // 静止掩码（同一网格坐标）
    float sm = 0.0;
    if (uStaticW > 0.0) {
        vec2 suv = (q + 0.5) / vec2(textureSize(uStatic, 0));
        sm = texture(uStatic, suv).r;
    }

    // 三环精修：r、r/2、r/4 各 8 方向（代价在同一 lod 上比较，跨 lod 不可比）
    float bestCost = blockCost(center, best, uLod) + uStaticW * length(best) * sm;
    for (int ring = 0; ring < 3; ring++) {
        float r = uRadius / pow(2.0, float(ring));
        for (int d = 0; d < 8; d++) {
            vec2 c = best + DIRS[d] * r;
            float cost = blockCost(center, c, uLod) + uStaticW * length(c) * sm;
            if (cost < bestCost) {
                bestCost = cost;
                best = c;
            }
        }
    }

    outColor = vec4((best + 64.0) / 128.0, clamp(bestCost / 255.0, 0.0, 1.0), 1.0);
}
"""

        /**
         * 代价加权平滑：3x3 邻域按匹配代价加权平均（连续跑两遍 ≈ 5x5）。
         * 代价高的向量（匹配不可信）权重低，压掉块匹配的离群野向量；
         * 权重 1/(uSmoothW.x + 代价*uSmoothW.y)。
         */
        private const val SMOOTH_SHADER = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uFlow;
uniform ivec2 uFlowDim;
uniform vec2 uSmoothW;
out vec4 outColor;

void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    vec2 sum = vec2(0.0);
    float wsum = 0.0;
    float centerCost = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            ivec2 qc = clamp(q + ivec2(i, j), ivec2(0), uFlowDim - 1);
            vec4 s = texelFetch(uFlow, qc, 0);
            vec2 f = s.rg * 128.0 - 64.0;
            float w = 1.0 / (uSmoothW.x + s.b * uSmoothW.y);
            sum += f * w;
            wsum += w;
            if (i == 0 && j == 0) centerCost = s.b;
        }
    }
    vec2 avg = sum / wsum;
    outColor = vec4((avg + 64.0) / 128.0, centerCost, 1.0);
}
"""

        /**
         * 时间平滑（时序传播 EMA）：out = mix(cur, prev, uPrevWeight)。
         * prevWeight=1 时退化成纯拷贝（把最终光流转正进锚点，复用同一着色器）。
         * 网格级 RGBA 整体线性混合（RG 的编码是仿射的，混合编码值 = 混合向量）。
         */
        private const val BLEND_SHADER = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform float uPrevWeight;
out vec4 outColor;

void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uCur, q, 0);
    vec4 p = texelFetch(uPrev, q, 0);
    outColor = mix(c, p, uPrevWeight);
}
"""

        /**
         * P8 融合加权（GSFG"融合网络为每个像素分配两路候选的权重"）：
         * 7 维特征 × uFuseW 求和 + uFuseB 过 sigmoid 得主路权重 w，
         * flowFinal = mix(小幅支路, 主路, w)，A 通道载 w。
         * 权重当前是手调 v1（见 GsfgWeights），未来训练权重直接换 uniform。
         */
        private const val FUSE_SHADER = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uFlowF;
uniform sampler2D uFlowS;
uniform sampler2D uFlowB;
uniform sampler2D uStatic;
uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform vec2 uFullDim;
uniform vec2 uGridDim;
uniform float uFuseW[7];
uniform float uFuseB;
out vec4 outColor;

vec2 dec(vec2 enc) { return enc * 128.0 - 64.0; }

void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    vec4 fs = texelFetch(uFlowF, q, 0);
    vec4 ss = texelFetch(uFlowS, q, 0);
    vec2 vf = dec(fs.rg);
    vec2 vs = dec(ss.rg);
    float sm = texture(uStatic, (gl_FragCoord.xy) / uGridDim).r;

    // 双向不一致量：前向流终点处取反向流，正确匹配时两者互为相反数
    vec2 uv = (gl_FragCoord.xy) / uGridDim;
    vec2 b = dec(texture(uFlowB, uv + vf / uFullDim).rg);
    float incons = length(vf + b) / 64.0;

    // 半程 warp 色差（遮挡/新内容特征）：A 后退半步 vs B 前进半步。
    // 方向必须与 uFlowF 的"前向流"约定一致（v1.0.2 及之前写反，导致运动区恒定报大色差、
    // 融合网络被误导去信小幅支路）
    vec2 pf = gl_FragCoord.xy / uGridDim * uFullDim;
    vec3 ca = texture(uTexA, (pf - 0.5 * vf + 0.5) / uFullDim).rgb;
    vec3 cb = texture(uTexB, (pf + 0.5 * vf + 0.5) / uFullDim).rgb;
    float colorD = distance(ca, cb);

    float feat[7];
    feat[0] = fs.b;
    feat[1] = ss.b;
    feat[2] = sm;
    feat[3] = incons;
    feat[4] = colorD;
    feat[5] = length(vf - vs) / 64.0;
    feat[6] = length(vf) / 64.0;

    float x = uFuseB
        + uFuseW[0] * feat[0]
        + uFuseW[1] * feat[1]
        + uFuseW[2] * feat[2]
        + uFuseW[3] * feat[3]
        + uFuseW[4] * feat[4]
        + uFuseW[5] * feat[5]
        + uFuseW[6] * feat[6];
    x = clamp(x, -8.0, 8.0);
    float w = 1.0 / (1.0 + exp(-x));

    vec2 v = mix(vs, vf, w);
    float cost = mix(ss.b, fs.b, w);
    outColor = vec4((v + 64.0) / 128.0, cost, w);
}
"""

        /**
         * P9 draft 初稿（半分辨率）：最终光流双向 warp 半程融合。
         * 只是 P10 引导上采样的引导源，轻遮挡（色差大）回退较新真实帧即可。
         */
        private const val DRAFT_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform sampler2D uFlow;
uniform vec2 uFullDim;
out vec4 outColor;

void main() {
    vec2 uv = gl_FragCoord.xy / (uFullDim * 0.5);
    vec2 f = texture(uFlow, uv).rg * 128.0 - 64.0;
    // 与 P10 同一约定：uFlow 为前向流 f = p_cur − p_prev，t=0.5 时 A 取 uv − 0.5f、B 取 uv + 0.5f
    vec3 a = texture(uTexA, uv - 0.5 * f / uFullDim).rgb;
    vec3 c = texture(uTexB, uv + 0.5 * f / uFullDim).rgb;
    float d = distance(a, c);
    outColor = vec4(mix(mix(a, c, 0.5), c, smoothstep(0.25, 0.5, d)), 1.0);
}
"""

        /**
         * P10 合成 t 时刻的帧（GSFG ⑤⑥）：
         * 1. **draft 引导上采样**：低分辨率光流以 draft 初稿亮度做 3x3 joint-bilateral
         *    上采样到全分辨率，边缘处不跨物体插值（减少边缘错位）。
         * 2. **t 参数化 warp**：上一帧沿光流前进 t 步、当前帧后退 (1-t) 步，融合。
         * 3. **遮挡回退**：两侧颜色差 / 匹配代价 / 双向不一致量三重判定任一超标 →
         *    回退较新的真实帧，宁缺勿影。
         */
        private const val INTERP_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uTexA;
uniform sampler2D uTexB;
uniform sampler2D uFlowF;
uniform sampler2D uFlowB;
uniform sampler2D uDraft;
uniform vec2 uFullDim;
uniform vec2 uGridDim;
uniform vec2 uOrigin;
uniform vec2 uSize;
uniform float uT;
uniform float uGuideW;
uniform vec4 uOcclW;
out vec4 outColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 uv = (gl_FragCoord.xy - uOrigin) / uSize;

    // ---- draft 引导上采样：3x3 邻格按 draft 亮度差加权 ----
    vec2 gp = uv * uGridDim - 0.5;
    vec2 gi = floor(gp);
    float lumaC = luma(texture(uDraft, uv).rgb);
    vec2 fsum = vec2(0.0);
    float wsum = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = clamp(gi + vec2(float(i), float(j)), vec2(0.0), uGridDim - 1.0);
            vec2 uvG = (g + 0.5) / uGridDim;
            float lw = luma(texture(uDraft, uvG).rgb);
            float w = exp(-abs(lumaC - lw) * uGuideW);
            fsum += (texture(uFlowF, uvG).rg * 128.0 - 64.0) * w;
            wsum += w;
        }
    }
    vec2 f = fsum / max(wsum, 1e-4);

    // ---- t 参数化双向 warp + 融合 ----
    vec2 b = texture(uFlowB, uv + f / uFullDim).rg * 128.0 - 64.0;
    float incons = length(f + b);
    // 采样方向必须与 [FLOW_SHADER] 的存储约定一致：uFlowF 存的是"前向流"
    // f = p_cur − p_prev（A 的格子往前指到 B）。t 时刻内容位于 p_t = (1−t)p_prev + t·p_cur，
    // 于是像素 y=p_t 要取 A 的 p_prev = y − t·f、B 的 p_cur = y + (1−t)·f。
    // v1.0.2 及之前这里写反（A 取 y + t·f、B 取 y − (1−t)·f）：静止区无感，
    // 一有运动 A/B 两样本就同时落在错误位置 → 软双影（拖影），且遮挡判定恒定超标。
    vec3 a = texture(uTexA, uv - uT * f / uFullDim).rgb;
    vec3 c = texture(uTexB, uv + (1.0 - uT) * f / uFullDim).rgb;
    float d = distance(a, c);
    float cost = texture(uFlowF, uv).b;
    float bad = smoothstep(uOcclW.x, uOcclW.y, max(d, max(cost * uOcclW.z, incons / uOcclW.w)));
    outColor = vec4(mix(mix(a, c, uT), c, bad), 1.0);
}
"""

        /**
         * 真实帧直绘：FBO 纹理（v=0 = 画面底部）→ 屏幕（gl_FragCoord 同为底部起点），
         * 不翻转；点采样保持与直绘通路一致的像素精确度。
         */
        private const val DRAW_SHADER = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform ivec2 uDim;
uniform vec2 uOrigin;
uniform vec2 uSize;
out vec4 outColor;

void main() {
    vec2 uv = (gl_FragCoord.xy - uOrigin) / uSize;
    ivec2 p = clamp(ivec2(uv * vec2(uDim)), ivec2(0), uDim - 1);
    outColor = texelFetch(uTex, p, 0);
}
"""
    }
}
