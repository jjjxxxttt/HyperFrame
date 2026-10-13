package dev.padlink.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * PadLink 统一视觉样式（v0.20.0 起）：深空底 + 透明玻璃卡片 + 胶囊按钮，
 * 质感方向对齐小米 HyperOS —— 大圆角、低对比玻璃层、克制的蓝色点缀。
 *
 * 纯代码构建、零第三方依赖：全部效果用 GradientDrawable（渐变 + 描边 + 圆角）拼出，
 * 不引入主题/资源文件。"玻璃感"的来源是深色页面上叠 8%~10% 的白色玻璃层
 * （上亮下暗的微渐变）+ 12% 白细描边，肉眼就是磨砂玻璃叠在深空底上的效果。
 */
object GlassStyle {

    // ---- 色板 ----

    /** 页面背景（深空蓝黑，上浅下深） */
    const val BG_TOP = "#0A0B10"
    const val BG_BOTTOM = "#0D1018"

    /** 主强调色（HyperOS 蓝） */
    const val ACCENT = "#FF4C8DFF"
    const val ACCENT_DEEP = "#FF3B6FE0"

    /** 文字层级：主 / 次 / 弱 */
    const val TEXT_PRIMARY = "#FFFFFFFF"
    const val TEXT_SECONDARY = "#A6FFFFFF"
    const val TEXT_TERTIARY = "#66FFFFFF"

    /** 状态色 */
    const val OK = "#FF34C759"
    const val WARN = "#FFFF9F0A"
    const val ERROR = "#FFFF453A"

    fun color(hex: String): Int = Color.parseColor(hex)

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 整页背景：深空垂直渐变 */
    fun windowBackground(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color(BG_TOP), color(BG_BOTTOM))
        )

    /** 玻璃卡片：22dp 圆角、上亮下暗的白玻璃层 + 细描边 */
    fun glassCard(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color("#1AFFFFFF"), color("#0AFFFFFF"))
        ).apply {
            cornerRadius = dp(ctx, 22).toFloat()
            setStroke(dp(ctx, 1), color("#1FFFFFFF"))
        }

    /** 玻璃面板（顶部圆角、底部方角）：全屏播放页底部控制条用 */
    fun glassPanelTop(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color("#D90C0E14"), color("#F20C0E14"))
        ).apply {
            val r = dp(ctx, 26).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(ctx, 1), color("#1FFFFFFF"))
        }

    /** 胶囊按钮：primary = 蓝渐变实心；secondary = 玻璃 */
    fun pill(ctx: Context, primary: Boolean): GradientDrawable =
        if (primary) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(color("#FF5A96FF"), color(ACCENT_DEEP))
            ).apply { cornerRadius = 999f }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(color("#1AFFFFFF"), color("#0DFFFFFF"))
            ).apply {
                cornerRadius = 999f
                setStroke(dp(ctx, 1), color("#22FFFFFF"))
            }
        }

    /** 分辨率档位 chip：选中实心蓝，未选玻璃 */
    fun chip(ctx: Context, active: Boolean): GradientDrawable =
        if (active) {
            GradientDrawable().apply {
                setColor(color(ACCENT))
                cornerRadius = 999f
            }
        } else {
            GradientDrawable().apply {
                setColor(color("#12FFFFFF"))
                cornerRadius = 999f
                setStroke(dp(ctx, 1), color("#1EFFFFFF"))
            }
        }

    /** 首页 Hero 大卡（v1.0.1，盖世主页风格）：深蓝紫渐变 + 细描边 */
    fun heroCard(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color("#FF1B2450"), color("#FF101426"))
        ).apply {
            cornerRadius = dp(ctx, 26).toFloat()
            setStroke(dp(ctx, 1), color("#2EFFFFFF"))
        }

    /** Hero 内部光晕：右上角径向渐变（封面质感的来源） */
    fun heroGlow(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color("#664C8DFF"), color("#004C8DFF"))
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(ctx, 170).toFloat()
            setGradientCenter(0.85f, 0.12f)
        }

    /** 状态圆点 */
    fun dot(ctx: Context, hex: String): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(hex))
            cornerRadius = 999f
        }
}
