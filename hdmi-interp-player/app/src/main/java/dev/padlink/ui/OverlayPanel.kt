package dev.padlink.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView

/**
 * 盖世风格右侧悬浮面板（v0.30.0）：深色磨砂面板贴画面右缘，左缘一列图标栏
 * （操作 / 性能 / 设置，底部独立「退出」），点图标切页 —— 对齐盖世游戏内悬浮面板的结构。
 *
 * 只负责搭 UI 和回调，不持有任何业务状态：预览页通过 [Listener] 收动作，
 * 再通过 setXxx 系列方法回推状态（开关/档位/读数）。
 *
 * 页面与内容（对应 HyperFrame 现有功能）：
 * - **操作**：开始/停止、声音开关、插帧开关、插帧质量（点行循环档位）、复位采集卡；
 * - **性能**：性能检测 HUD 开关 + 实时读数明细；
 * - **设置**：分辨率（进子页选档，仿「画质增强」二级页）、导出日志、调试模式、版本；
 * - **退出**：底部独立项，退出播放页。
 */
class OverlayPanel(
    context: Context,
    private val listener: Listener
) : FrameLayout(context) {

    /** 面板动作回调（由预览页实现，映射到既有处理逻辑） */
    interface Listener {
        fun onPanelStartStop(start: Boolean)
        fun onPanelSoundToggle()
        fun onPanelInterpToggle()
        fun onPanelInterpQualityCycle()
        fun onPanelInterpGhostingCycle()
        fun onPanelResetCard()
        fun onPanelExportLog()
        fun onPanelProfileIndex(index: Int)
        fun onPanelHudToggle(show: Boolean)
        fun onPanelDebugToggle()
        fun onPanelImportDll()
        fun onPanelLsfgModeCycle()
        fun onPanelLsfgMultiplierCycle()
        fun onPanelLsfgFlowCycle()
        fun onPanelLsfgFpsCycle()
        fun onPanelLsfgQueueCycle()
        fun onPanelExit()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /** sp 换算（API 34 起 DisplayMetrics.scaledDensity 已废弃，手算等价值） */
    private fun sp(v: Float): Float =
        v * context.resources.displayMetrics.density * context.resources.configuration.fontScale

    private val textColor = GlassStyle.color(GlassStyle.TEXT_PRIMARY)
    private val textDim = GlassStyle.color(GlassStyle.TEXT_SECONDARY)
    private val accent = GlassStyle.color(GlassStyle.ACCENT)

    // ---- 状态控件引用（供 setXxx 回推） ----
    private lateinit var swSound: Switch
    private lateinit var swInterp: Switch
    private lateinit var interpModeValue: TextView
    private lateinit var swHud: Switch
    private lateinit var swDebug: Switch
    private lateinit var startStopRow: LinearLayout
    private lateinit var startButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var qualityValue: TextView
    private lateinit var ghostValue: TextView
    private lateinit var lsfgMultValue: TextView
    private lateinit var lsfgFlowValue: TextView
    private lateinit var lsfgFpsValue: TextView
    private lateinit var lsfgQueueValue: TextView
    private lateinit var resValue: TextView
    private lateinit var statsDetail: TextView
    private lateinit var resList: LinearLayout
    private lateinit var lsfgValue: TextView
    private lateinit var lsfgStatusText: TextView

    private val railItems = LinkedHashMap<TextView, RailIconView>()
    private var activePage = 0

    /**
     * 程序化回推（setXxx → Switch.isChecked）期间抑制 onChange 回调：
     * CompoundButton 的 setChecked 会照常触发 OnCheckedChangeListener，而本面板的
     * 回调语义多是「翻转」（声音/插帧/调试）—— 回火一次就把状态又翻回去，
     * 与预览页的 sync 形成无限互调（ANR）。状态回推必须单向：只许用户点按触发回调。
     */
    private var suppressSwitchCallback = false

    // 页面容器
    private lateinit var pageOperate: ScrollView
    private lateinit var pagePerf: ScrollView
    private lateinit var pageSettings: ScrollView
    private lateinit var pageRes: LinearLayout
    private lateinit var pageLsfg: LinearLayout

    init {
        val panelWidth = minOf(dp(372), (context.resources.displayMetrics.widthPixels * 0.42f).toInt())
        layoutParams = LayoutParams(panelWidth, LayoutParams.MATCH_PARENT, Gravity.END)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F216171B"))
            val r = dp(22).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) // 左侧圆角、贴右缘
            setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
        }

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(buildRail())
        row.addView(buildContent(), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showPage(0)
    }

    // ==================== 左侧图标栏 ====================

    private fun buildRail(): LinearLayout {
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(14), dp(4), dp(10))
            setBackgroundColor(Color.parseColor("#14000000"))
        }
        rail.addView(railItem(0, RailIconView.KIND_PAD, "操作"))
        rail.addView(railItem(1, RailIconView.KIND_PULSE, "性能"))
        rail.addView(railItem(2, RailIconView.KIND_GEAR, "设置"))
        rail.addView(Space(context), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        rail.addView(railItem(3, RailIconView.KIND_EXIT, "退出"))
        return rail
    }

    private fun railItem(page: Int, kind: Int, label: String): View {
        val icon = RailIconView(context, kind)
        val text = TextView(context).apply {
            this.text = label
            textSize = 10f
            setTextColor(textDim)
            gravity = Gravity.CENTER
        }
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(8), dp(2), dp(8))
            addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(16)))
            setOnClickListener {
                if (page == 3) listener.onPanelExit() else showPage(page)
            }
        }
        railItems[text] = icon
        // 用 item 本身做高亮底
        val highlight = GradientDrawable().apply { cornerRadius = dp(13).toFloat() }
        item.background = highlight
        item.tag = page
        return item
    }

    private fun showPage(page: Int) {
        activePage = page
        pageOperate.visibility = if (page == 0) View.VISIBLE else View.GONE
        pagePerf.visibility = if (page == 1) View.VISIBLE else View.GONE
        pageSettings.visibility = if (page == 2) View.VISIBLE else View.GONE
        pageRes.visibility = View.GONE
        pageLsfg.visibility = View.GONE
        refreshRail()
    }

    private fun refreshRail() {
        for ((text, icon) in railItems) {
            val item = text.parent as? LinearLayout ?: continue
            val page = item.tag as? Int ?: continue
            val active = page == activePage
            (item.background as? GradientDrawable)?.setColor(
                if (active) Color.parseColor("#24FFFFFF") else Color.TRANSPARENT
            )
            icon.active = active
            icon.invalidate()
            text.setTextColor(if (active) textColor else textDim)
        }
    }

    // ==================== 右侧页面区 ====================

    private fun buildContent(): View {
        val host = FrameLayout(context)
        pageOperate = ScrollView(context).apply { visibility = View.GONE }
        pagePerf = ScrollView(context).apply { visibility = View.GONE }
        pageSettings = ScrollView(context).apply { visibility = View.GONE }
        pageRes = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pageLsfg = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pageOperate.addView(buildOperatePage())
        pagePerf.addView(buildPerfPage())
        pageSettings.addView(buildSettingsPage())
        buildResPage()
        buildLsfgPage()
        host.addView(pageOperate, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        host.addView(pagePerf, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        host.addView(pageSettings, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        host.addView(pageRes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        host.addView(pageLsfg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return host
    }

    private fun pageColumn(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(18))
    }

    private fun pageTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(textColor)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), dp(2), dp(2), dp(10))
    }

    /** 设置行容器：深色圆角行 + 上下留白 */
    private fun rowCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0FFFFFFF"))
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    /** 开关行：标题 + 可选副标题 + Switch */
    private fun rowToggle(title: String, sub: String?, onChange: (Boolean) -> Unit): Pair<LinearLayout, Switch> {
        val sw = makeSwitch(false, onChange)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTitle(title))
            if (sub != null) addView(rowSub(sub))
        }
        val row = rowCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        return row to sw
    }

    /** 可点行：标题 + 可选副标题 + 右侧当前值/箭头 */
    private fun rowLink(title: String, sub: String?, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val value = TextView(context).apply {
            textSize = 12f
            setTextColor(textDim)
            text = ""
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTitle(title))
            if (sub != null) addView(rowSub(sub))
        }
        val arrow = TextView(context).apply {
            text = "›"
            textSize = 16f
            setTextColor(textDim)
        }
        val row = rowCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(arrow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            })
            setOnClickListener { onClick() }
        }
        return row to value
    }

    /** 说明文字行（非交互） */
    private fun rowText(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(textDim)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0FFFFFFF"))
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun rowTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(textColor)
    }

    private fun rowSub(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(textDim)
        setPadding(0, dp(2), 0, 0)
    }

    /** 胶囊按钮（文字版，面板内用） */
    private fun pillButton(text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setTextColor(if (primary) Color.WHITE else textDim)
            background = if (primary) GlassStyle.pill(context, true) else GlassStyle.pill(context, false)
            setOnClickListener { onClick() }
        }

    private fun makeSwitch(initial: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(context).apply {
            isChecked = initial
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = tintFor(initial)
            setOnCheckedChangeListener { _, checked ->
                trackTintList = tintFor(checked)
                if (!suppressSwitchCallback) onChange(checked)
            }
        }

    /** 程序化回推开关状态（抑制回调，见 [suppressSwitchCallback]），顺带同步着色 */
    private fun setSwitchChecked(sw: Switch, on: Boolean) {
        if (sw.isChecked == on) return
        suppressSwitchCallback = true
        sw.isChecked = on
        sw.trackTintList = tintFor(on)
        suppressSwitchCallback = false
    }

    private fun tintFor(on: Boolean): ColorStateList =
        ColorStateList.valueOf(if (on) accent else Color.parseColor("#33FFFFFF"))

    // ---------- 操作页 ----------

    private fun buildOperatePage(): View {
        val col = pageColumn()
        col.addView(pageTitle("操作"))

        startButton = pillButton("开始", true) { listener.onPanelStartStop(true) }
        stopButton = pillButton("停止", false) { listener.onPanelStartStop(false) }
        startStopRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            addView(stopButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        col.addView(startStopRow)

        val (soundRow, sw1) = rowToggle("声音", "UAC 音频 → 扬声器") { listener.onPanelSoundToggle() }
        swSound = sw1
        col.addView(soundRow)

        val (interpRow, sw2) = rowToggle("插帧", "开 / 关插帧；模式与参数见下") { listener.onPanelInterpToggle() }
        swInterp = sw2
        col.addView(interpRow)

        val (modeRow, modeVal) = rowLink("插帧模式", "点按循环：关 / 自动 / LSFG / 经典") {
            listener.onPanelLsfgModeCycle()
        }
        interpModeValue = modeVal
        col.addView(modeRow)

        val (qualityRow, qualityVal) = rowLink("插帧质量", "点按循环：清晰 / 均衡 / 流畅") {
            listener.onPanelInterpQualityCycle()
        }
        qualityValue = qualityVal
        col.addView(qualityRow)

        val (ghostRow, ghostVal) = rowLink("抗拖影", "点按循环：弱 / 标准 / 强（经典插帧用）") {
            listener.onPanelInterpGhostingCycle()
        }
        ghostValue = ghostVal
        col.addView(ghostRow)

        val resetRow = rowCard().apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTitle("复位采集卡"))
            addView(rowSub("等效重新插拔：固件卡死 / 换分辨率断链时用"))
            addView(pillButton("立即复位", false) { listener.onPanelResetCard() }.apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            })
        }
        col.addView(resetRow)
        return col
    }

    // ---------- 性能页 ----------

    private fun buildPerfPage(): View {
        val col = pageColumn()
        col.addView(pageTitle("性能"))

        val (hudRow, sw) = rowToggle("性能检测", "画面顶部 HUD：帧率 / 源 / 延迟 / 丢帧 / 温度") {
            listener.onPanelHudToggle(it)
        }
        swHud = sw
        col.addView(hudRow)

        col.addView(rowText("读数加载中…").also { statsDetail = it })
        col.addView(rowSub("CPU 为本进程占用；处理器温度取系统热区，取不到显示 —").apply {
            setPadding(dp(2), dp(2), dp(2), 0)
        })
        return col
    }

    // ---------- 设置页 ----------

    private fun buildSettingsPage(): View {
        val col = pageColumn()
        col.addView(pageTitle("设置"))

        val (resRow, resVal) = rowLink("分辨率", "切换采集档位（点进子页）") {
            pageRes.visibility = View.VISIBLE
            pageSettings.visibility = View.GONE
        }
        resValue = resVal
        col.addView(resRow)

        val (lsfgRow, lsfgVal) = rowLink("插帧引擎", "LSFG 帧生成 / 着色器导入（点进子页）") {
            pageLsfg.visibility = View.VISIBLE
            pageSettings.visibility = View.GONE
        }
        lsfgValue = lsfgVal
        col.addView(lsfgRow)

        col.addView(rowCard().apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTitle("导出日志"))
            addView(rowSub("完整日志导出到 Download/HyperFrame，定位问题靠它"))
            addView(pillButton("导出日志", false) { listener.onPanelExportLog() }.apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            })
        })

        val (debugRow, sw) = rowToggle("调试模式", "调试按钮与画面底部日志区") { listener.onPanelDebugToggle() }
        swDebug = sw
        col.addView(debugRow)

        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        col.addView(rowText("版本 v$versionName（每次构建递增）"))
        return col
    }

    // ---------- 分辨率子页 ----------

    private fun buildResPage() {
        val back = TextView(context).apply {
            text = "‹ 分辨率"
            textSize = 15f
            setTextColor(textColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(16), dp(14), dp(10))
            setOnClickListener {
                pageRes.visibility = View.GONE
                pageSettings.visibility = View.VISIBLE
            }
        }
        resList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(18))
        }
        val scroll = ScrollView(context).apply { addView(resList) }
        pageRes.addView(back)
        pageRes.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ---------- 插帧引擎子页 ----------

    private fun buildLsfgPage() {
        val back = TextView(context).apply {
            text = "‹ 插帧引擎"
            textSize = 15f
            setTextColor(textColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(16), dp(14), dp(10))
            setOnClickListener {
                pageLsfg.visibility = View.GONE
                pageSettings.visibility = View.VISIBLE
            }
        }
        val col = pageColumn().apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }

        col.addView(rowCard().apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTitle("LSFG 引擎状态"))
            addView(rowSub("读取中…").also { lsfgStatusText = it })
        })

        col.addView(rowCard().apply {
            orientation = LinearLayout.VERTICAL
            addView(rowTitle("导入 Lossless.dll"))
            addView(rowSub("从 Lossless Scaling 安装目录选择 Lossless.dll，抽取着色器后立即删除副本"))
            addView(pillButton("选择 Lossless.dll", true) { listener.onPanelImportDll() }.apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            })
        })

        val (multRow, multVal) = rowLink("帧生成倍率", "点按循环 2x ~ 8x（输出帧率 = 源帧率 × 倍率）") {
            listener.onPanelLsfgMultiplierCycle()
        }
        lsfgMultValue = multVal
        col.addView(multRow)

        val (flowRow, flowVal) = rowLink("光流精度", "点按循环：高 / 中 / 低（越低越快）") {
            listener.onPanelLsfgFlowCycle()
        }
        lsfgFlowValue = flowVal
        col.addView(flowRow)

        val (fpsRow, fpsVal) = rowLink("目标帧率上限", "点按循环：关闭 / 30 / 60 / 120") {
            listener.onPanelLsfgFpsCycle()
        }
        lsfgFpsValue = fpsVal
        col.addView(fpsRow)

        val (queueRow, queueVal) = rowLink("待处理帧队列深度", "点按循环 2 ~ 6（越大越抗抖、延迟略增）") {
            listener.onPanelLsfgQueueCycle()
        }
        lsfgQueueValue = queueVal
        col.addView(queueRow)

        col.addView(rowSub("LSFG 帧生成引擎（lsfg-vk）按 MIT 许可集成；Lossless.dll 内的着色器属 Lossless Scaling 专有资产，需自行购买正版，App 不随包分发、抽取后不留存副本。").apply {
            setPadding(dp(2), dp(2), dp(2), 0)
        })

        val scroll = ScrollView(context).apply { addView(col) }
        pageLsfg.addView(back)
        pageLsfg.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ==================== 状态回推 ====================

    /** 会话运行状态：运行中只能点停止，反之只能点开始 */
    fun setSessionRunning(running: Boolean) {
        startButton.alpha = if (running) 0.45f else 1f
        startButton.isEnabled = !running
        stopButton.alpha = if (running) 1f else 0.45f
        stopButton.isEnabled = running
    }

    fun setSoundEnabled(on: Boolean) {
        setSwitchChecked(swSound, on)
    }

    fun setInterpState(on: Boolean, modeLabel: String, qualityLabel: String, ghostLabel: String) {
        setSwitchChecked(swInterp, on)
        interpModeValue.text = modeLabel
        qualityValue.text = qualityLabel
        ghostValue.text = ghostLabel
    }

    fun setHudVisible(on: Boolean) {
        setSwitchChecked(swHud, on)
    }

    fun setDebugMode(on: Boolean) {
        setSwitchChecked(swDebug, on)
    }

    /** 性能页读数明细（多行文本） */
    fun setStatsText(text: String) {
        statsDetail.text = text
    }

    /** 设置页「分辨率」行的当前档位文字 */
    fun setCurrentProfileLabel(label: String) {
        resValue.text = label
    }

    /** 设置页「插帧引擎」行短标签 + 子页里的详细状态 */
    fun setLsfgStatus(short: String, detail: String) {
        lsfgValue.text = short
        lsfgStatusText.text = detail
    }

    /** 「插帧引擎」子页的帧生成参数当前值 */
    fun setLsfgParams(multLabel: String, flowLabel: String, fpsLabel: String, queueLabel: String) {
        lsfgMultValue.text = multLabel
        lsfgFlowValue.text = flowLabel
        lsfgFpsValue.text = fpsLabel
        lsfgQueueValue.text = queueLabel
    }

    /** 重建分辨率子页列表 */
    fun setProfiles(labels: List<String>, selectedIndex: Int) {
        resList.removeAllViews()
        for ((i, label) in labels.withIndex()) {
            val active = i == selectedIndex
            resList.addView(TextView(context).apply {
                text = label
                textSize = 12.5f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextColor(if (active) textColor else textDim)
                background = if (active) GlassStyle.chip(context, true) else GlassStyle.chip(context, false)
                setOnClickListener { listener.onPanelProfileIndex(i) }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
    }

    /**
     * 图标栏小图标（零依赖自绘）：手柄 / 心跳 / 齿轮 / 退出。
     * active = 高亮（白），否则弱化。
     */
    private class RailIconView(context: Context, private val kind: Int) : View(context) {

        companion object {
            const val KIND_PAD = 0
            const val KIND_PULSE = 1
            const val KIND_GEAR = 2
            const val KIND_EXIT = 3
        }

        var active = true

        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = if (active) Color.WHITE else Color.parseColor("#8AFFFFFF")
            val c = width / 2f
            val m = height / 2f
            when (kind) {
                KIND_PAD -> {
                    val body = android.graphics.RectF(
                        c - 10f * density, m - 5.5f * density,
                        c + 10f * density, m + 5.5f * density
                    )
                    canvas.drawRoundRect(body, 5f * density, 5f * density, paint)
                    canvas.drawCircle(c + 5f * density, m - 1.5f * density, 1.2f * density, paint)
                    canvas.drawCircle(c + 5f * density, m + 2.5f * density, 1.2f * density, paint)
                    canvas.drawLine(
                        c - 6f * density, m, c - 2f * density, m, paint
                    )
                    canvas.drawLine(
                        c - 4f * density, m - 2f * density, c - 4f * density, m + 2f * density, paint
                    )
                }
                KIND_PULSE -> {
                    val path = Path()
                    path.moveTo(c - 10f * density, m)
                    path.lineTo(c - 5f * density, m)
                    path.lineTo(c - 2.5f * density, m - 6f * density)
                    path.lineTo(c + 1f * density, m + 5f * density)
                    path.lineTo(c + 4f * density, m - 3f * density)
                    path.lineTo(c + 6.5f * density, m)
                    path.lineTo(c + 10f * density, m)
                    canvas.drawPath(path, paint)
                }
                KIND_GEAR -> {
                    canvas.drawCircle(c, m, 6.5f * density, paint)
                    canvas.drawCircle(c, m, 2.2f * density, paint)
                    for (i in 0 until 8) {
                        val a = Math.PI / 4 * i
                        val x1 = c + 6.5f * density * Math.cos(a).toFloat()
                        val y1 = m + 6.5f * density * Math.sin(a).toFloat()
                        val x2 = c + 9.5f * density * Math.cos(a).toFloat()
                        val y2 = m + 9.5f * density * Math.sin(a).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
                KIND_EXIT -> {
                    // 门框 + 出去的箭头
                    canvas.drawLine(c - 1f * density, m - 9f * density, c - 8f * density, m - 9f * density, paint)
                    canvas.drawLine(c - 8f * density, m - 9f * density, c - 8f * density, m + 9f * density, paint)
                    canvas.drawLine(c - 8f * density, m + 9f * density, c - 1f * density, m + 9f * density, paint)
                    canvas.drawLine(c - 4f * density, m, c + 8f * density, m, paint)
                    canvas.drawLine(c + 4f * density, m - 4f * density, c + 8.5f * density, m, paint)
                    canvas.drawLine(c + 4f * density, m + 4f * density, c + 8.5f * density, m, paint)
                }
            }
        }
    }
}
