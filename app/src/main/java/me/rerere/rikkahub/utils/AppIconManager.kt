package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.R

/** Drawable resources bundled with one icon preset. */
data class AppIconArtwork(
    @DrawableRes val mipmapRes: Int,
    @DrawableRes val statIconRes: Int,
    @DrawableRes val loadingAnimRes: Int,
    @DrawableRes val fgLayerRes: Int,
    @DrawableRes val monoLayerRes: Int,
    @DrawableRes val floatingBubbleRes: Int? = null,
)

@Serializable
enum class AppIconPreset(
    val id: String,
    val aliasClassName: String,
    @StringRes val labelRes: Int,
    val artwork: AppIconArtwork,
    val styleSummary: String,
) {
    @SerialName("flow_yao")
    FLOW_YAO(
        id = "flow_yao",
        aliasClassName = ".MainActivityFlowYao",
        labelRes = R.string.app_icon_preset_flow_yao,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_flow_yao,
            statIconRes = R.drawable.ic_stat_flow_yao,
            loadingAnimRes = R.drawable.ic_loading_flow_yao,
            fgLayerRes = R.drawable.ic_launcher_flow_yao_fg,
            monoLayerRes = R.drawable.ic_launcher_flow_yao_mono,
        ),
        styleSummary = "Default: twilight nebula gradient with flowing curved Yao arcs",
    ),

    @SerialName("minimal_yao")
    MINIMAL_YAO(
        id = "minimal_yao",
        aliasClassName = ".MainActivityMinimalYao",
        labelRes = R.string.app_icon_preset_minimal_yao,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_minimal_yao,
            statIconRes = R.drawable.ic_stat_minimal_yao,
            loadingAnimRes = R.drawable.ic_loading_minimal_yao,
            fgLayerRes = R.drawable.ic_launcher_minimal_yao_fg,
            monoLayerRes = R.drawable.ic_launcher_minimal_yao_mono,
        ),
        styleSummary = "Minimalist geometric Yao character on deep navy gradient",
    ),

    @SerialName("dual_chat")
    DUAL_CHAT(
        id = "dual_chat",
        aliasClassName = ".MainActivityDualChat",
        labelRes = R.string.app_icon_preset_dual_chat,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_dual_chat,
            statIconRes = R.drawable.ic_stat_dual_chat,
            loadingAnimRes = R.drawable.ic_loading_dual_chat,
            fgLayerRes = R.drawable.ic_launcher_dual_chat_fg,
            monoLayerRes = R.drawable.ic_launcher_dual_chat_mono,
        ),
        styleSummary = "Diagonal blue gradient with overlapping dual chat bubbles and AI sparkle",
    ),

    @SerialName("agent_core")
    AGENT_CORE(
        id = "agent_core",
        aliasClassName = ".MainActivityAgentCore",
        labelRes = R.string.app_icon_preset_agent_core,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_agent_core,
            statIconRes = R.drawable.ic_stat_agent_core,
            loadingAnimRes = R.drawable.ic_loading_agent_core,
            fgLayerRes = R.drawable.ic_launcher_agent_core_fg,
            monoLayerRes = R.drawable.ic_launcher_agent_core_mono,
        ),
        styleSummary = "Modern indigo badge with four-point AI star",
    ),

    @SerialName("terminal_cli")
    TERMINAL_CLI(
        id = "terminal_cli",
        aliasClassName = ".MainActivityTerminalCli",
        labelRes = R.string.app_icon_preset_terminal_cli,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_terminal_cli,
            statIconRes = R.drawable.ic_stat_terminal_cli,
            loadingAnimRes = R.drawable.ic_loading_terminal_cli,
            fgLayerRes = R.drawable.ic_launcher_terminal_cli_fg,
            monoLayerRes = R.drawable.ic_launcher_terminal_cli_mono,
        ),
        styleSummary = "Charcoal dark terminal prompt badge (>_)",
    ),

    @SerialName("obsidian_rabbit")
    OBSIDIAN_RABBIT(
        id = "obsidian_rabbit",
        aliasClassName = ".MainActivityObsidianRabbit",
        labelRes = R.string.app_icon_preset_obsidian_rabbit,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_obsidian_rabbit,
            statIconRes = R.drawable.ic_stat_obsidian_rabbit,
            loadingAnimRes = R.drawable.ic_loading_obsidian_rabbit,
            fgLayerRes = R.drawable.ic_launcher_obsidian_rabbit_fg,
            monoLayerRes = R.drawable.ic_launcher_obsidian_rabbit_mono,
        ),
        styleSummary = "Obsidian dark background with minimalist rabbit silhouette",
    ),

    @SerialName("legacy_yao")
    LEGACY_YAO(
        id = "legacy_yao",
        aliasClassName = ".MainActivityLegacyYao",
        labelRes = R.string.app_icon_preset_legacy_yao,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_legacy_yao,
            statIconRes = R.drawable.ic_stat_yao,
            loadingAnimRes = R.drawable.yao_loading,
            fgLayerRes = R.mipmap.ic_launcher_foreground,
            monoLayerRes = R.mipmap.ic_launcher_monochrome,
            floatingBubbleRes = R.drawable.ic_floating_bubble,
        ),
        styleSummary = "Legacy orbital Yao launcher icon",
    ),

    @SerialName("rikkahub_classic")
    RIKKAHUB_CLASSIC(
        id = "rikkahub_classic",
        aliasClassName = ".MainActivityRikkahubClassic",
        labelRes = R.string.app_icon_preset_rikkahub_classic,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_rikkahub_classic,
            statIconRes = R.drawable.ic_stat_rikkahub_classic,
            loadingAnimRes = R.drawable.ic_loading_rikkahub_classic,
            fgLayerRes = R.drawable.ic_launcher_rikkahub_classic_fg,
            monoLayerRes = R.drawable.ic_launcher_rikkahub_classic_mono,
        ),
        styleSummary = "Classic RikkaHub tilted white rabbit on dark background",
    ),

    @SerialName("rikkahub_outline")
    RIKKAHUB_OUTLINE(
        id = "rikkahub_outline",
        aliasClassName = ".MainActivityRikkahubOutline",
        labelRes = R.string.app_icon_preset_rikkahub_outline,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_rikkahub_outline,
            statIconRes = R.drawable.ic_stat_rikkahub_outline,
            loadingAnimRes = R.drawable.ic_loading_rikkahub_outline,
            fgLayerRes = R.drawable.ic_launcher_rikkahub_outline_fg,
            monoLayerRes = R.drawable.ic_launcher_rikkahub_outline_mono,
        ),
        styleSummary = "RikkaHub monochrome line-art rabbit on white background",
    ),
    ;

    companion object {
        val DEFAULT_PRESET: AppIconPreset = FLOW_YAO

        fun fromIdOrNull(raw: String?): AppIconPreset? {
            val normalized = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
            if (normalized.isEmpty()) return null
            if (normalized == "default") return DEFAULT_PRESET
            return entries.firstOrNull { it.id == normalized }
        }
    }
}

sealed interface AppIconResolution {
    data class Found(val preset: AppIconPreset) : AppIconResolution
    data class NotFound(val availableIds: List<String>) : AppIconResolution
    data class Ambiguous(val matches: List<String>) : AppIconResolution
}

object AppIconManager {
    @Volatile
    var currentStatIconRes: Int = R.drawable.ic_stat_flow_yao

    private val aliasesByPreset: Map<String, List<String>> = mapOf(
        "flow_yao" to listOf("default", "flow", "nebula", "curve"),
        "minimal_yao" to listOf("minimal", "geometric"),
        "dual_chat" to listOf("chat", "bubble", "dialogue"),
        "agent_core" to listOf("agent", "star", "sparkle", "core"),
        "terminal_cli" to listOf("terminal", "cli", "console", "geek"),
        "obsidian_rabbit" to listOf("obsidian", "dark"),
        "legacy_yao" to listOf("legacy", "old", "orbital"),
        "rikkahub_classic" to listOf("classic", "rikkahub"),
        "rikkahub_outline" to listOf("outline", "line", "white"),
    )

    fun resolvePreset(
        query: String,
        localizedLabels: Map<AppIconPreset, String> = emptyMap(),
    ): AppIconResolution {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return AppIconResolution.NotFound(AppIconPreset.entries.map { it.id })
        }
        AppIconPreset.fromIdOrNull(trimmed)?.let { return AppIconResolution.Found(it) }

        val folded = trimmed.lowercase()
        fun termsFor(preset: AppIconPreset): List<String> =
            aliasesByPreset[preset.id].orEmpty() + listOfNotNull(localizedLabels[preset])

        val aliasMatches = AppIconPreset.entries.filter { preset ->
            termsFor(preset).any { it.equals(folded, ignoreCase = true) }
        }
        if (aliasMatches.size == 1) return AppIconResolution.Found(aliasMatches.first())
        if (aliasMatches.size > 1) return AppIconResolution.Ambiguous(aliasMatches.map { it.id })

        val partialMatches = AppIconPreset.entries.filter { preset ->
            preset.id.contains(folded.replace('-', '_')) ||
                termsFor(preset).any { it.contains(folded, ignoreCase = true) }
        }
        return when {
            partialMatches.isEmpty() -> AppIconResolution.NotFound(AppIconPreset.entries.map { it.id })
            partialMatches.size == 1 -> AppIconResolution.Found(partialMatches.first())
            else -> AppIconResolution.Ambiguous(partialMatches.map { it.id })
        }
    }

    /**
     * Switches the active launcher `<activity-alias>` to [preset] and updates notification icon.
     * Enables the target alias first before disabling all others so the app never ends up
     * with zero active launcher entries.
     */
    fun applyLauncherIcon(context: Context, preset: AppIconPreset) {
        currentStatIconRes = preset.artwork.statIconRes
        val pm = context.packageManager
        val pkg = context.packageName
        val targetComponent = ComponentName(pkg, "$pkg${preset.aliasClassName}")
        pm.setComponentEnabledSetting(
            targetComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIconPreset.entries.forEach { other ->
            if (other != preset) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "$pkg${other.aliasClassName}"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
