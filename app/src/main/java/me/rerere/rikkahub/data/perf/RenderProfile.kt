package me.rerere.rikkahub.data.perf

import android.app.ActivityManager
import android.content.Context
import me.rerere.rikkahub.data.datastore.RenderPerformance
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "RenderProfile"

/** 设备能力档位。 */
enum class RenderTier {
    HIGH,
    MID,
    LOW,
}

/**
 * 一个档位对应的渲染 / 合并阈值。
 *
 * 约定：
 * - 档位只影响「中间帧的频率与数量」，不改变最终结果，也不改变模型上下文；
 * - [RenderProfile.FULL] 为「完整优先」手动档：不做任何合并（窗口为 0），等价于关闭
 *   全部性能合并的回退通道；
 * - 所有阈值在使用前都会经过钳制，异常值不得导致内容不更新。
 */
data class RenderProfile(
    /** 大 diff 默认渲染的行数，[Int.MAX_VALUE] 表示不限 */
    val diffDefaultLines: Int,
    /** JSON 树单个容器默认组合的子节点数，[Int.MAX_VALUE] 表示不限 */
    val jsonVisibleChildren: Int,
    /** 流式输出变换的合并窗口（毫秒），0 表示不合并 */
    val outputFlushIntervalMs: Long,
    /** 流式分块应用的合并窗口（毫秒），0 表示不合并 */
    val streamApplyIntervalMs: Long,
    /** 代码高亮结果的缓存条目上限 */
    val highlightCacheEntries: Int,
) {
    companion object {
        /** 合并窗口的允许范围：超出范围的值一律钳制回来 */
        val WINDOW_RANGE = 0L..1_000L

        val HIGH = RenderProfile(
            diffDefaultLines = Int.MAX_VALUE,
            jsonVisibleChildren = Int.MAX_VALUE,
            outputFlushIntervalMs = 100L,
            streamApplyIntervalMs = 100L,
            highlightCacheEntries = 24,
        )

        /** 与引入档位之前的默认行为保持一致 */
        val MID = RenderProfile(
            diffDefaultLines = 300,
            jsonVisibleChildren = 100,
            outputFlushIntervalMs = 200L,
            streamApplyIntervalMs = 150L,
            highlightCacheEntries = 16,
        )

        val LOW = RenderProfile(
            diffDefaultLines = 120,
            jsonVisibleChildren = 40,
            outputFlushIntervalMs = 300L,
            streamApplyIntervalMs = 300L,
            highlightCacheEntries = 16,
        )

        /** 完整优先：不合并、不限行，行为与未经优化的原始路径一致 */
        val FULL = RenderProfile(
            diffDefaultLines = Int.MAX_VALUE,
            jsonVisibleChildren = Int.MAX_VALUE,
            outputFlushIntervalMs = 0L,
            streamApplyIntervalMs = 0L,
            highlightCacheEntries = 16,
        )
    }
}

/** 设备能力探测结果；未知时各项为 0，会被解析为 [RenderTier.MID]。 */
data class DeviceProfile(
    val cpuCores: Int,
    val memoryClassMb: Int,
    val isLowRam: Boolean,
) {
    companion object {
        val UNKNOWN = DeviceProfile(cpuCores = 0, memoryClassMb = 0, isLowRam = false)
    }
}

/**
 * 设备能力探测结果缓存：设备能力在进程生命周期内不变，避免在组合期被反复查询系统服务。
 * 只在拿到 context 且探测成功时缓存；探测失败不缓存，下次可重试。
 */
@Volatile
private var cachedDeviceProfile: DeviceProfile? = null

/**
 * 探测设备能力。任何异常（取不到 ActivityManager、系统返回异常值等）都必须吞掉并回落
 * [DeviceProfile.UNKNOWN]（最终解析为 [RenderTier.MID]），绝不抛出、绝不禁用功能。
 */
fun detectDeviceProfile(context: Context?): DeviceProfile {
    cachedDeviceProfile?.let { return it }
    val detected = runCatching {
        val manager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@runCatching DeviceProfile.UNKNOWN
        DeviceProfile(
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(0),
            memoryClassMb = manager.memoryClass.coerceAtLeast(0),
            isLowRam = manager.isLowRamDevice,
        )
    }.getOrElse { error ->
        AppLog.w(TAG, "device profile detection failed; falling back to MID: ${error.message}")
        DeviceProfile.UNKNOWN
    }
    if (context != null) cachedDeviceProfile = detected
    return detected
}

/** 由探测结果给出硬件档位；探测不到或异常一律 [RenderTier.MID]。 */
fun tierOf(profile: DeviceProfile): RenderTier = when {
    profile.isLowRam -> RenderTier.LOW
    profile.cpuCores <= 0 && profile.memoryClassMb <= 0 -> RenderTier.MID
    profile.memoryClassMb in 1..192 -> RenderTier.LOW
    profile.cpuCores in 1..3 -> RenderTier.LOW
    profile.cpuCores >= 8 && profile.memoryClassMb >= 512 -> RenderTier.HIGH
    else -> RenderTier.MID
}

/**
 * 解析当前生效的渲染档位。
 *
 * 手动选择优先于自动探测：用户选定后自动探测不再推翻它。
 */
fun resolveRenderProfile(
    performance: RenderPerformance,
    device: DeviceProfile,
): RenderProfile = when (performance) {
    RenderPerformance.SMOOTH -> RenderProfile.LOW
    RenderPerformance.FULL -> RenderProfile.FULL
    RenderPerformance.AUTO -> when (tierOf(device)) {
        RenderTier.HIGH -> RenderProfile.HIGH
        RenderTier.MID -> RenderProfile.MID
        RenderTier.LOW -> RenderProfile.LOW
    }
}.sanitized()

/** 钳制到安全范围。 */
private fun RenderProfile.sanitized(): RenderProfile = copy(
    diffDefaultLines = diffDefaultLines.coerceAtLeast(1),
    jsonVisibleChildren = jsonVisibleChildren.coerceAtLeast(1),
    outputFlushIntervalMs = outputFlushIntervalMs.coerceIn(RenderProfile.WINDOW_RANGE),
    streamApplyIntervalMs = streamApplyIntervalMs.coerceIn(RenderProfile.WINDOW_RANGE),
    highlightCacheEntries = highlightCacheEntries.coerceIn(1, 64),
)

/** 档位变化的诊断留痕；同一档位重复调用只记录一次，避免刷爆日志。 */
object RenderTierDiagnostics {
    @Volatile
    private var lastSignature: String? = null

    fun logIfChanged(
        performance: RenderPerformance,
        device: DeviceProfile,
        profile: RenderProfile,
    ) {
        val signature = "$performance/${device.cpuCores}/${device.memoryClassMb}/${device.isLowRam}"
        if (signature == lastSignature) return
        lastSignature = signature
        AppLog.i(
            TAG,
            "render profile: preference=$performance cores=${device.cpuCores} " +
                "memoryClass=${device.memoryClassMb}MB lowRam=${device.isLowRam} -> " +
                "diffLines=${profile.diffDefaultLines} jsonChildren=${profile.jsonVisibleChildren} " +
                "outputFlush=${profile.outputFlushIntervalMs}ms " +
                "streamApply=${profile.streamApplyIntervalMs}ms",
        )
    }
}
