package dev.padlink.lsfg

import android.content.Context
import android.net.Uri

/**
 * LSFG 帧生成引擎的状态机（骨架，M3/M4 扩展会话生命周期）。
 *
 * 状态迁移：
 * ```
 * NO_DLL ──导入抽取成功──▶ EXTRACTED ──initContext 成功──▶ ACTIVE
 *    ▲                        │                              │
 *    │      抽取/校验失败      ▼                              ▼
 *    └──────重新导入────── FAILED ◀────会话失败/销毁──────────┘
 * ```
 * - **NO_DLL**：设备上还没有可用的 SPIR-V 着色器缓存，需要用户导入 Lossless.dll；
 * - **EXTRACTED**：着色器已抽取并通过驱动校验，可以起 LSFG 会话；
 * - **ACTIVE**：帧生成会话运行中（M3/M4 起）；
 * - **FAILED**：最近一次抽取/校验失败，[lastError] 为中文原因。
 *
 * 着色器缓存本身就是持久化：`filesDir/spirv/` 里有产物即视为已抽取，
 * 不依赖 SharedPreferences，卸载 App 自动清除。
 */
object LsfgPipeline {

    enum class State(val label: String) {
        /** 尚未导入 Lossless.dll（无着色器缓存） */
        NO_DLL("未导入"),

        /** 着色器已就绪，可启用 LSFG 插帧 */
        EXTRACTED("已就绪"),

        /** LSFG 帧生成会话运行中 */
        ACTIVE("运行中"),

        /** 最近一次抽取/校验失败 */
        FAILED("不可用"),
    }

    @Volatile
    var state: State = State.NO_DLL
        private set

    /** 最近一次失败的中文原因（state == FAILED 时有值） */
    @Volatile
    var lastError: String? = null
        private set

    /** 抽取进行中（瞬态，仅供 UI 显示"抽取中…"） */
    @Volatile
    var busy: Boolean = false
        private set

    /** 状态变化回调（在触发变化的线程上调用，UI 侧自行切主线程） */
    var onStateChanged: (() -> Unit)? = null

    /** 启动时同步检查缓存，恢复状态（不跑驱动校验，成本高的 probe 留给导入时） */
    fun refresh(ctx: Context) {
        // FAILED（抽取/会话失败，含 lastError 原因）与 ACTIVE（会话运行中）是"已定型"状态，
        // 刷新不得覆盖 —— 光凭"缓存文件还在"就改写成 EXTRACTED，会把失败原因抹掉并
        // 宣称"着色器已就绪：可启用 LSFG 插帧"，用户据此以为 LSFG 正在跑，
        // 于是把经典 GSFG 通路的拖影误判成 LSFG 的问题（v1.0.2 实证）。
        if (state == State.FAILED || state == State.ACTIVE) return
        val next = if (ShaderExtractor.hasCache(ctx)) State.EXTRACTED else State.NO_DLL
        if (next == State.EXTRACTED) lastError = null
        if (state != next) setState(next)
    }

    /** 着色器是否就绪（EXTRACTED/ACTIVE 都算可用） */
    fun isReady(): Boolean = state == State.EXTRACTED || state == State.ACTIVE

    /**
     * 导入 Lossless.dll 并抽取着色器（同步阻塞，含 DXBC→SPIR-V 转译，
     * 必须在后台线程调用）。成功后 DLL 副本已被删除，只留 SPIR-V 缓存。
     */
    fun importDll(ctx: Context, uri: Uri): ExtractResult {
        busy = true
        onStateChanged?.invoke()
        val result = try {
            ShaderExtractor.extract(ctx, uri)
        } finally {
            busy = false
        }
        when (result) {
            is ExtractResult.Success -> {
                lastError = null
                setState(State.EXTRACTED)
            }
            is ExtractResult.Failure -> {
                lastError = result.message
                setState(State.FAILED)
            }
        }
        return result
    }

    /** 帧生成会话启动成功（M3/M4 起由管线调用） */
    fun markActive() = setState(State.ACTIVE)

    /** 会话结束（正常停止或失败销毁），着色器仍在 → 回到 EXTRACTED */
    fun markSessionEnded(failed: Boolean, error: String? = null) {
        if (failed) {
            lastError = error
            setState(State.FAILED)
        } else {
            lastError = null
            setState(State.EXTRACTED)
        }
    }

    /** 面板设置页「插帧引擎」行右侧的短标签 */
    fun statusShort(): String = when {
        busy -> "抽取中…"
        else -> state.label
    }

    /** 插帧引擎子页里的详细中文状态 */
    fun statusDetail(): String = when {
        busy -> "正在从 Lossless.dll 抽取着色器并校验，请稍候…"
        state == State.EXTRACTED -> "着色器已就绪：可启用 LSFG 插帧"
        state == State.ACTIVE -> "LSFG 帧生成运行中"
        state == State.FAILED -> "不可用：${lastError ?: "未知原因"}（可重新导入 Lossless.dll）"
        else -> "未导入 Lossless.dll：需先抽取着色器才能启用 LSFG 插帧"
    }

    private fun setState(next: State) {
        if (state == next) {
            onStateChanged?.invoke()
            return
        }
        state = next
        onStateChanged?.invoke()
    }
}
