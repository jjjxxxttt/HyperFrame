package dev.padlink.ui

import android.content.Context

/**
 * 调试模式开关（v0.26.0）：调试按钮（取流测试/重新扫描/请求 USB 授权/导出日志/
 * 复位卡/检查更新）与日志区默认全部隐藏，普通用户/外人看不到调试入口。
 *
 * 解锁方式：长按首页或预览页的版本徽标切换（持久化到 SharedPreferences）。
 * 布局风格对齐盖世模拟器：日常界面只留播放入口与状态，工具全部收进调试层。
 */
object DebugMode {

    private const val PREFS = "padlink"
    private const val KEY = "debug_mode"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }

    /** 切换并返回新状态 */
    fun toggle(ctx: Context): Boolean {
        val next = !isEnabled(ctx)
        set(ctx, next)
        return next
    }
}
