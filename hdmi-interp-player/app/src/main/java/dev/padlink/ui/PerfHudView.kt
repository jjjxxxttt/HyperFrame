package dev.padlink.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Debug
import android.view.View
import java.io.File
import java.util.Locale

/**
 * 画面顶部性能检测 HUD（v0.30.0）：参照盖世游戏的悬浮 HUD 样式 —— 深色胶囊条 +
 * 一排彩色小指标 + 末尾帧率波形，压在画面顶部居中。
 *
 * **指标全部取真数据，取不到显示「—」，不拿别的数字顶替**（同「显示帧率取不到标未测」原则）：
 * - 帧率 / 源 / 延迟 / 丢帧：预览页每拍喂入（上屏渲染、FID 源帧率、提交→上屏延迟、丢帧计数）；
 * - CPU / 内存 / 处理器 / 电池：后台采样线程读 /proc/self/stat（本进程 CPU 占用）、
 *   Debug.getPss()（PSS 内存）、/sys/class/thermal（处理器温度，SELinux 拒读则「—」）、
 *   电池广播（电池温度）。
 *
 * 只是一个自绘 View，零第三方依赖；采样线程随 View 挂载/卸载自动启停。
 */
class PerfHudView(context: Context) : View(context) {

    /** 一格指标：灰白标签 + 彩色数值 */
    private class Metric(val label: String, val color: Int)

    private val metrics = listOf(
        Metric("帧率", Color.parseColor("#FF34C759")),
        Metric("源", Color.parseColor("#FF64D2FF")),
        Metric("延迟", Color.parseColor("#FFFF9F0A")),
        Metric("丢帧", Color.parseColor("#FFFF453A")),
        Metric("CPU", Color.parseColor("#FFFF6482")),
        Metric("内存", Color.parseColor("#FFFFD60A")),
        Metric("处理器", Color.parseColor("#FFBF5AF2")),
        Metric("电池", Color.parseColor("#FF30D158"))
    )

    private val lock = Any()

    /** 各指标当前显示值（与 metrics 同序；后台线程和 UI 线程都会写，加锁） */
    private val values = Array(metrics.size) { "—" }

    /** 版本徽标文字（左起第一格，满足"界面必须显示版本号"约定） */
    private var versionText = "HyperFrame"

    /** 帧率波形历史（环形，末尾一段帧率曲线） */
    private val hist = FloatArray(48)
    private var histCount = 0

    // ---- 画笔 ----
    // sp 换算（API 34 起 DisplayMetrics.scaledDensity 已废弃，手算等价值）
    private val sp =
        context.resources.displayMetrics.density * context.resources.configuration.fontScale
    private val density = context.resources.displayMetrics.density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AFFFFFF")
        textSize = 9f * sp
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.5f * sp
        isFakeBoldText = true
    }
    private val versionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassStyle.color(GlassStyle.ACCENT)
        textSize = 10f * sp
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B30B0C10")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1FFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF34C759")
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // ---- 后台采样（CPU / 内存 / 温度 / 电池） ----
    @Volatile private var sampling = false
    private var samplerThread: Thread? = null

    /** CPU 占用（本进程，占全部核的百分比，0-100）；-1 = 未测 */
    @Volatile private var cpuPct = -1f

    /** 设备核数：CPU% 按"占总 CPU"口径（v1.1.2 起；以前是单核归一、多线程会超 100%，用户误读） */
    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** PSS 内存 MB；-1 = 未测 */
    @Volatile private var memMb = -1f

    /** 处理器温度 ℃；-1 = 未测 */
    @Volatile private var cpuTempC = -1f

    /** 电池温度 ℃；-1 = 未测 */
    @Volatile private var battTempC = -1f

    /** 缓存热区温度文件路径（找到一个可读的 cpu/soc/skin 区后固定用它） */
    private var cpuTempPath: String? = null

    /** 设置版本徽标 */
    fun setVersion(text: String) {
        versionText = text
        postInvalidateOnAnimation()
    }

    /**
     * 喂入实时链路指标（UI 线程调用）。
     *
     * @param renderFps 上屏帧率
     * @param sourceFps 源帧率（FID）
     * @param latencyMs 提交→上屏延迟（毫秒，&lt;0 = 未测）
     * @param dropped   累计丢帧
     */
    fun setLive(renderFps: Double, sourceFps: Double, latencyMs: Double, dropped: Long) {
        synchronized(lock) {
            values[0] = fmtFps(renderFps)
            values[1] = fmtFps(sourceFps)
            values[2] = if (latencyMs < 0) "—" else String.format(Locale.US, "%.0fms", latencyMs)
            values[3] = dropped.toString()
            // 波形历史：上屏帧率
            if (histCount < hist.size) {
                hist[histCount++] = renderFps.toFloat()
            } else {
                System.arraycopy(hist, 1, hist, 0, hist.size - 1)
                hist[hist.size - 1] = renderFps.toFloat()
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (samplerThread == null) {
            sampling = true
            samplerThread = Thread({ sampleLoop() }, "padlink-hud").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        sampling = false
        samplerThread = null
        super.onDetachedFromWindow()
    }

    /** 采样循环：500ms 一拍，全在后台线程，不占 UI 线程 */
    private fun sampleLoop() {
        var lastTicks = -1L
        var lastNs = 0L
        while (sampling) {
            val ticks = readSelfCpuTicks()
            val now = System.nanoTime()
            if (lastTicks >= 0 && ticks >= 0 && now > lastNs) {
                val sec = (now - lastNs) / 1e9
                if (sec > 0.05) {
                    // utime+stime 以 100Hz jiffy 计：差值/100 = 忙碌秒数，再除墙钟 = 单核占比；
                    // 除以核数得到"占总 CPU"口径（多线程合计不再超 100%）
                    cpuPct = ((ticks - lastTicks) / 100.0 / sec * 100.0 / coreCount).toFloat()
                }
            }
            if (ticks >= 0) {
                lastTicks = ticks
                lastNs = now
            }
            memMb = try {
                Debug.getPss() / 1024f
            } catch (_: Throwable) {
                -1f
            }
            cpuTempC = readCpuTemp()
            battTempC = readBatteryTemp()
            synchronized(lock) {
                values[4] = if (cpuPct < 0) "—" else String.format(Locale.US, "%.0f%%", cpuPct)
                values[5] = if (memMb < 0) "—" else String.format(Locale.US, "%.0fMB", memMb)
                values[6] = if (cpuTempC < 0) "—" else String.format(Locale.US, "%.1f°C", cpuTempC)
                values[7] = if (battTempC < 0) "—" else String.format(Locale.US, "%.1f°C", battTempC)
            }
            postInvalidateOnAnimation()
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** 本进程 CPU 时间（utime+stime，jiffy）；读不到返回 -1 */
    private fun readSelfCpuTicks(): Long = try {
        val s = File("/proc/self/stat").readText()
        // comm 字段可能含空格/括号，从最后一个 ')' 之后再切
        val tail = s.substring(s.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
        tail[11].toLong() + tail[12].toLong()
    } catch (_: Throwable) {
        -1L
    }

    /** 处理器温度：读 thermal 区（cpu/soc/skin 优先）；SELinux 拒读返回 -1 */
    private fun readCpuTemp(): Float {
        val cached = cpuTempPath
        if (cached != null) {
            val v = readTempFile(cached)
            if (v > -273f) return v
        }
        for (i in 0 until 32) {
            try {
                val type = File("/sys/class/thermal/thermal_zone$i/type").readText().trim().lowercase()
                if (type.contains("cpu") || type.contains("soc") || type.contains("skin")) {
                    val v = readTempFile("/sys/class/thermal/thermal_zone$i/temp")
                    if (v > -273f) {
                        cpuTempPath = "/sys/class/thermal/thermal_zone$i/temp"
                        return v
                    }
                }
            } catch (_: Throwable) {
                // 不可读就试下一个区
            }
        }
        return -1f
    }

    private fun readTempFile(path: String): Float = try {
        File(path).readText().trim().toFloat() / 1000f
    } catch (_: Throwable) {
        -1f
    }

    /** 电池温度（粘性广播，不用注册接收器） */
    private fun readBatteryTemp(): Float = try {
        val it = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = it?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) -1f else t / 10f
    } catch (_: Throwable) {
        -1f
    }

    private fun fmtFps(v: Double): String =
        if (v <= 0) "—" else String.format(Locale.US, "%.0f", v)

    // ==================== 绘制 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val snap = synchronized(lock) { values.copyOf() }
        var w = 10f * density + versionPaint.measureText(versionText) + 9f * density
        for (i in metrics.indices) {
            w += labelPaint.measureText(metrics[i].label) + 3f * density +
                valuePaint.measureText(snap[i]) + 9f * density
        }
        w += 80f * density + 10f * density // 波形 + 右内边距
        val h = 26f * density
        setMeasuredDimension(
            resolveSize(w.toInt() + 2, widthMeasureSpec),
            resolveSize(h.toInt() + 2, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snap = synchronized(lock) { values.copyOf() }
        val h = height.toFloat()
        val rect = RectF(1f, 1f, width - 1f, h - 1f)
        canvas.drawRoundRect(rect, 9f * density, 9f * density, bgPaint)
        canvas.drawRoundRect(rect, 9f * density, 9f * density, borderPaint)

        val cy = h / 2f
        val baselineLabel = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        val baselineValue = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
        val baselineVer = cy - (versionPaint.descent() + versionPaint.ascent()) / 2f
        var x = 10f * density

        // 版本徽标（左起第一格）
        canvas.drawText(versionText, x, baselineVer, versionPaint)
        x += versionPaint.measureText(versionText) + 9f * density

        // 指标格：灰白标签 + 彩色数值
        for (i in metrics.indices) {
            canvas.drawText(metrics[i].label, x, baselineLabel, labelPaint)
            x += labelPaint.measureText(metrics[i].label) + 3f * density
            valuePaint.color = metrics[i].color
            canvas.drawText(snap[i], x, baselineValue, valuePaint)
            x += valuePaint.measureText(snap[i]) + 9f * density
        }
        valuePaint.color = Color.WHITE

        // 末尾：帧率波形
        drawWave(canvas, x, cy)
    }

    /** 帧率波形：最近一段上屏帧率曲线（绿线），无数据时只画基线 */
    private fun drawWave(canvas: Canvas, x: Float, cy: Float) {
        val w = 80f * density
        val halfH = 7f * density
        val baseY = cy + halfH
        canvas.drawLine(x, baseY, x + w, baseY, borderPaint)
        synchronized(lock) {
            if (histCount < 2) return
            var maxV = 20f
            for (i in 0 until histCount) if (hist[i] > maxV) maxV = hist[i]
            val path = Path()
            for (i in 0 until histCount) {
                val px = x + w * i / (hist.size - 1)
                val py = baseY - (hist[i] / maxV) * (halfH * 2f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, wavePaint)
        }
    }
}
