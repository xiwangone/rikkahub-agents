package me.rerere.rikkahub.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.rerere.rikkahub.R
import java.util.concurrent.atomic.AtomicBoolean

/** Per-preset tailored loading indicator animation style. */
enum class AppIconLoadingStyle {
    DIAMOND_ROTATE_PULSE,
    AURA_ORBIT_BREATHE,
    PRISM_BREATHE_SCALE,
    CHAT_FLOAT_ROCK,
    STAR_TWINKLE_SPIN,
    TERMINAL_CURSOR_BLINK,
    ORBITAL_SPIN,
    RABBIT_HOP_TILT,
}

/** Drawable resources bundled with one icon preset. */
data class AppIconArtwork(
    @DrawableRes val mipmapRes: Int,
    @DrawableRes val statIconRes: Int,
    @DrawableRes val fgLayerRes: Int,
    @DrawableRes val monoLayerRes: Int,
    @DrawableRes val floatingBubbleRes: Int? = null,
)

/**
 * Lenient serializer so upgraded installs whose stored JSON still holds retired preset ids
 * (e.g. `flow_yao`, `minimal_yao`, `obsidian_rabbit`) fall back cleanly to [AppIconPreset.DEFAULT_PRESET]
 * instead of failing Settings deserialization.
 */
object LenientAppIconPresetSerializer : KSerializer<AppIconPreset> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AppIconPreset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AppIconPreset) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): AppIconPreset {
        val raw = runCatching { decoder.decodeString() }.getOrNull()
        return AppIconPreset.fromIdOrNull(raw) ?: AppIconPreset.DEFAULT_PRESET
    }
}

@Serializable(with = LenientAppIconPresetSerializer::class)
enum class AppIconPreset(
    val id: String,
    val aliasClassName: String,
    @StringRes val labelRes: Int,
    val artwork: AppIconArtwork,
    val loadingStyle: AppIconLoadingStyle,
    val styleSummary: String,
) {
    YAO_DIAMOND(
        id = "yao_diamond",
        aliasClassName = ".MainActivityYaoDiamond",
        labelRes = R.string.app_icon_preset_yao_diamond,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_yao_diamond,
            statIconRes = R.drawable.ic_stat_yao_diamond,
            fgLayerRes = R.drawable.ic_launcher_yao_diamond_fg,
            monoLayerRes = R.drawable.ic_launcher_yao_diamond_mono,
        ),
        loadingStyle = AppIconLoadingStyle.DIAMOND_ROTATE_PULSE,
        styleSummary = "Default: bold interlocked sky-blue and white geometric Yao diamonds with cyan core",
    ),

    YAO_AURA(
        id = "yao_aura",
        aliasClassName = ".MainActivityYaoAura",
        labelRes = R.string.app_icon_preset_yao_aura,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_yao_aura,
            statIconRes = R.drawable.ic_stat_yao_aura,
            fgLayerRes = R.drawable.ic_launcher_yao_aura_fg,
            monoLayerRes = R.drawable.ic_launcher_yao_aura_mono,
        ),
        loadingStyle = AppIconLoadingStyle.AURA_ORBIT_BREATHE,
        styleSummary = "Deep-sea radial glow with layered translucent Yao diamonds and orbital anchor dots",
    ),

    YAO_PRISM(
        id = "yao_prism",
        aliasClassName = ".MainActivityYaoPrism",
        labelRes = R.string.app_icon_preset_yao_prism,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_yao_prism,
            statIconRes = R.drawable.ic_stat_yao_prism,
            fgLayerRes = R.drawable.ic_launcher_yao_prism_fg,
            monoLayerRes = R.drawable.ic_launcher_yao_prism_mono,
        ),
        loadingStyle = AppIconLoadingStyle.PRISM_BREATHE_SCALE,
        styleSummary = "Framed midnight backdrop with solid blue-and-white prism Yao diamonds",
    ),

    DUAL_CHAT(
        id = "dual_chat",
        aliasClassName = ".MainActivityDualChat",
        labelRes = R.string.app_icon_preset_dual_chat,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_dual_chat,
            statIconRes = R.drawable.ic_stat_dual_chat,
            fgLayerRes = R.drawable.ic_launcher_dual_chat_fg,
            monoLayerRes = R.drawable.ic_launcher_dual_chat_mono,
        ),
        loadingStyle = AppIconLoadingStyle.CHAT_FLOAT_ROCK,
        styleSummary = "Diagonal blue gradient with overlapping dual chat bubbles and AI sparkle",
    ),

    AGENT_CORE(
        id = "agent_core",
        aliasClassName = ".MainActivityAgentCore",
        labelRes = R.string.app_icon_preset_agent_core,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_agent_core,
            statIconRes = R.drawable.ic_stat_agent_core,
            fgLayerRes = R.drawable.ic_launcher_agent_core_fg,
            monoLayerRes = R.drawable.ic_launcher_agent_core_mono,
        ),
        loadingStyle = AppIconLoadingStyle.STAR_TWINKLE_SPIN,
        styleSummary = "Modern indigo badge with four-point AI star",
    ),

    TERMINAL_CLI(
        id = "terminal_cli",
        aliasClassName = ".MainActivityTerminalCli",
        labelRes = R.string.app_icon_preset_terminal_cli,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_terminal_cli,
            statIconRes = R.drawable.ic_stat_terminal_cli,
            fgLayerRes = R.drawable.ic_launcher_terminal_cli_fg,
            monoLayerRes = R.drawable.ic_launcher_terminal_cli_mono,
        ),
        loadingStyle = AppIconLoadingStyle.TERMINAL_CURSOR_BLINK,
        styleSummary = "Charcoal dark terminal prompt badge (>_)",
    ),

    LEGACY_YAO(
        id = "legacy_yao",
        aliasClassName = ".MainActivityLegacyYao",
        labelRes = R.string.app_icon_preset_legacy_yao,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_legacy_yao,
            statIconRes = R.drawable.ic_stat_yao,
            fgLayerRes = R.mipmap.ic_launcher_yao_ring_foreground,
            monoLayerRes = R.mipmap.ic_launcher_yao_ring_monochrome,
            floatingBubbleRes = R.drawable.ic_floating_bubble_yao_ring,
        ),
        loadingStyle = AppIconLoadingStyle.ORBITAL_SPIN,
        styleSummary = "Orbital Yao ring emblem",
    ),

    RIKKAHUB_CLASSIC(
        id = "rikkahub_classic",
        aliasClassName = ".MainActivityRikkahubClassic",
        labelRes = R.string.app_icon_preset_rikkahub_classic,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_rikkahub_classic,
            statIconRes = R.drawable.ic_stat_rikkahub_classic,
            fgLayerRes = R.mipmap.ic_launcher_rikkahub_classic_fg,
            monoLayerRes = R.mipmap.ic_launcher_rikkahub_classic_mono,
        ),
        loadingStyle = AppIconLoadingStyle.RABBIT_HOP_TILT,
        styleSummary = "Official RikkaHub tilted white rabbit on dark background",
    ),

    RIKKAHUB_OUTLINE(
        id = "rikkahub_outline",
        aliasClassName = ".MainActivityRikkahubOutline",
        labelRes = R.string.app_icon_preset_rikkahub_outline,
        artwork = AppIconArtwork(
            mipmapRes = R.mipmap.ic_launcher_rikkahub_outline,
            statIconRes = R.drawable.ic_stat_rikkahub_outline,
            fgLayerRes = R.mipmap.ic_launcher_rikkahub_outline_fg,
            monoLayerRes = R.mipmap.ic_launcher_rikkahub_outline_mono,
        ),
        loadingStyle = AppIconLoadingStyle.RABBIT_HOP_TILT,
        styleSummary = "Classic monochrome line-art rabbit on white background",
    ),
    ;

    companion object {
        val DEFAULT_PRESET: AppIconPreset = YAO_DIAMOND

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
    var currentStatIconRes: Int = AppIconPreset.DEFAULT_PRESET.artwork.statIconRes

    @Volatile
    private var pendingLauncherPreset: AppIconPreset? = null

    private val lifecycleObserverInstalled = AtomicBoolean(false)

    private val aliasesByPreset: Map<String, List<String>> = mapOf(
        "yao_diamond" to listOf("default", "diamond", "geometric", "b"),
        "yao_aura" to listOf("aura", "deep", "glow", "b1"),
        "yao_prism" to listOf("prism", "solid", "b2"),
        "dual_chat" to listOf("chat", "bubble", "dialogue"),
        "agent_core" to listOf("agent", "star", "sparkle", "core"),
        "terminal_cli" to listOf("terminal", "cli", "console", "geek"),
        "legacy_yao" to listOf("orbital", "ring", "legacy"),
        "rikkahub_classic" to listOf("rikkahub", "rabbit"),
        "rikkahub_outline" to listOf("classic", "outline", "line", "white"),
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
     * Registers a process-level lifecycle observer so launcher `<activity-alias>` switches
     * happen cleanly when the app moves to the background (`ON_STOP`), preventing Android/MIUI
     * from killing the foreground task while the user is still in Settings or chatting.
     */
    fun registerLifecycleFlush(context: Context) {
        if (!lifecycleObserverInstalled.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        val install = {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        flushPendingLauncherIcon(appContext)
                    }
                },
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            install()
        } else {
            Handler(Looper.getMainLooper()).post(install)
        }
    }

    /** Flushes any deferred launcher icon change (called automatically on background `ON_STOP`). */
    fun flushPendingLauncherIcon(context: Context): Boolean {
        val target = pendingLauncherPreset ?: return false
        pendingLauncherPreset = null
        applyLauncherIconNow(context.applicationContext ?: context, target)
        return true
    }

    /**
     * Records a launcher icon change after settings have been persisted.
     * Updates in-app surfaces immediately and defers the `<activity-alias>` swap until
     * the app goes to background (`ON_STOP`) so the foreground process is never killed mid-flow.
     */
    fun scheduleLauncherIcon(context: Context, preset: AppIconPreset) {
        currentStatIconRes = preset.artwork.statIconRes
        registerLifecycleFlush(context)
        pendingLauncherPreset = preset
    }

    /**
     * Updates the current Activity's Recents card icon to match [preset].
     *
     * The [ActivityManager.TaskDescription] constructors are deprecated, so the
     * [ActivityManager.TaskDescription.Builder] (API 33+) is used instead. On
     * API 28–32 the Builder does not exist and there is no non-deprecated
     * replacement, so the Recents card icon is left unchanged there (the
     * launcher icon swap itself is unaffected).
     */
    fun syncTaskDescription(activity: Activity, preset: AppIconPreset) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val label = activity.getString(R.string.app_name)
                val desc =
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setIcon(preset.artwork.mipmapRes)
                        .build()
                activity.setTaskDescription(desc)
            }
        }
    }

    /**
     * Ensures the enabled launcher `<activity-alias>` matches [savedPreset] on cold start
     * (recovering if the system killed the process before an earlier deferred flush ran).
     */
    fun reconcileOnStartup(context: Context, savedPreset: AppIconPreset) {
        currentStatIconRes = savedPreset.artwork.statIconRes
        val pm = context.packageManager
        val pkg = context.packageName
        val target = ComponentName(pkg, "$pkg${savedPreset.aliasClassName}")
        val targetState = pm.getComponentEnabledSetting(target)
        val isTargetEnabled = targetState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (targetState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && savedPreset == AppIconPreset.DEFAULT_PRESET)
        val anyOtherEnabled = AppIconPreset.entries.any { other ->
            if (other == savedPreset) {
                false
            } else {
                val state = pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg${other.aliasClassName}"))
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && other == AppIconPreset.DEFAULT_PRESET)
            }
        }
        if (!isTargetEnabled || anyOtherEnabled) {
            pendingLauncherPreset = savedPreset
            registerLifecycleFlush(context)
        }
    }

    /**
     * Immediately switches the active launcher `<activity-alias>` to [preset].
     * Enables the target alias first before disabling all others so the app never ends up
     * with zero active launcher entries.
     */
    fun applyLauncherIconNow(context: Context, preset: AppIconPreset) {
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
                val comp = ComponentName(pkg, "$pkg${other.aliasClassName}")
                val cur = pm.getComponentEnabledSetting(comp)
                if (cur != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
    }
}
