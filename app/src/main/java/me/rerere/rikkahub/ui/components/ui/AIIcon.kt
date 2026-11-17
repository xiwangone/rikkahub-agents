package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.css
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.rememberAvatarShape
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.ProviderIconSource
import me.rerere.rikkahub.utils.computeAIIconByName
import me.rerere.rikkahub.utils.computeModelIconByName
import me.rerere.rikkahub.utils.listBundledIconNames
import me.rerere.rikkahub.utils.resolveModelIcon
import me.rerere.rikkahub.utils.resolveProviderIcon
import me.rerere.rikkahub.utils.toCssHex

@Composable
private fun AIIcon(
    data: Any,
    name: String,
    isSvg: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val contentColor = LocalContentColor.current
    val context = LocalContext.current
    val model =
        remember(data, isSvg, contentColor, context) {
            val builder = ImageRequest.Builder(context).data(data)
            if (isSvg) {
                builder.css(
                    """
                    svg {
                      fill: ${contentColor.toCssHex()};
                    }
                    """.trimIndent(),
                )
            }
            builder.build()
        }
    Surface(
        modifier = modifier.size(24.dp),
        shape = rememberAvatarShape(loading),
        color = color,
    ) {
        AsyncImage(
            model = model,
            contentDescription = name,
            // 4.dp 是**必须保持的**：外框 32.dp − 4×2 = 24.dp 绘制区，与同排线条图标一致。
            // 「看起来小」的根因不是这里的留白，而是 SVG 自身内容没铺满 viewBox
            // （deepseek 只占 74%、hunyuan 左右不对称）→ 已由 icons/*.svg 的 viewBox 归一化修复。
            modifier = Modifier.padding(4.dp),
        )
    }
}

/**
 * 按已解析的图标来源渲染：Asset/LocalFile 走图片，null 走首字母头像。
 * 覆盖指向的文件已不在磁盘上时，用 [fallbackBrandFile] 回退到品牌匹配，
 * 再不行才显示首字母（不静默吞掉）。
 */
@Composable
private fun AIIconBySource(
    source: ProviderIconSource?,
    name: String,
    fallbackBrandFile: () -> String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val context = LocalContext.current
    when (source) {
        is ProviderIconSource.Asset -> {
            AIIcon(
                data = "file:///android_asset/icons/${source.fileName}",
                name = name,
                isSvg = source.fileName.endsWith(".svg", ignoreCase = true),
                modifier = modifier,
                loading = loading,
                color = color,
            )
        }
        is ProviderIconSource.LocalFile -> {
            val file = remember(source) { java.io.File(context.filesDir, source.relativePath) }
            if (file.exists()) {
                AIIcon(
                    data = file,
                    name = name,
                    isSvg = source.relativePath.endsWith(".svg", ignoreCase = true),
                    modifier = modifier,
                    loading = loading,
                    color = color,
                )
            } else {
                val brandPath = remember(source, name) { fallbackBrandFile() }
                if (brandPath != null) {
                    AIIcon(
                        data = "file:///android_asset/icons/$brandPath",
                        name = name,
                        isSvg = brandPath.endsWith(".svg", ignoreCase = true),
                        modifier = modifier,
                        loading = loading,
                        color = color,
                    )
                } else {
                    TextAvatar(text = name, modifier = modifier, loading = loading, color = color)
                }
            }
        }
        null -> {
            TextAvatar(text = name, modifier = modifier, loading = loading, color = color)
        }
    }
}

/**
 * 提供商/模型图标：优先级为覆盖表 > 品牌匹配 > 首字母。
 *
 * @param iconOverride 该提供商在 [me.rerere.rikkahub.data.datastore.Settings.providerIconOverrides]
 * 里的存储值（`asset:` / `file:` 前缀）；为 null 时不查覆盖表。调用方传入覆盖值
 * 时优先用覆盖（如提供商列表页、模型列表的提供商筛选 chips）。
 */
@Composable
fun AutoAIIcon(
    name: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconOverride: String? = null,
    /** 端点地址。名字是自定义短名时（`CC` 指向 commandcode），端点才是可靠的品牌依据。 */
    baseUrl: String? = null,
) {
    val context = LocalContext.current
    val availableIcons = remember(context) { listBundledIconNames(context) }
    val source = remember(name, iconOverride, availableIcons, baseUrl) {
        resolveProviderIcon(name, iconOverride, availableIcons, baseUrl)
    }
    AIIconBySource(
        source = source,
        name = name,
        fallbackBrandFile = { computeAIIconByName(name, availableIcons) },
        modifier = modifier,
        loading = loading,
        color = color,
    )
}

/**
 * 模型图标：与提供商图标同一条优先级链——覆盖表 > 模型名品牌匹配 >
 * 所属提供商名/端点品牌匹配 > 首字母。
 *
 * @param modelId 模型 ID（如 `qwen3.8-max`、`gpt-6-luna`），品牌词在前，
 * 按 [me.rerere.rikkahub.utils.computeModelIconByName] 解析。
 * @param providerName 所属提供商名；模型名解析不到时用它兜底（如决策模型
 * `d3` 在 AMD 列表里显示 AMD 标）。
 * @param iconOverride 该模型所属提供商在覆盖表里的存储值；为 null 时不查覆盖表。
 * @param baseUrl 所属提供商的端点地址；名字是自定义短名时，端点才是可靠的品牌依据。
 */
@Composable
fun AutoModelIcon(
    modelId: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    providerName: String? = null,
    iconOverride: String? = null,
    baseUrl: String? = null,
) {
    val context = LocalContext.current
    val availableIcons = remember(context) { listBundledIconNames(context) }
    val source = remember(modelId, iconOverride, availableIcons, providerName, baseUrl) {
        resolveModelIcon(modelId, iconOverride, availableIcons, providerName, baseUrl)
    }
    AIIconBySource(
        source = source,
        name = modelId,
        fallbackBrandFile = {
            computeModelIconByName(modelId, availableIcons)
                ?: providerName?.let { computeAIIconByName(it, availableIcons, baseUrl) }
        },
        modifier = modifier,
        loading = loading,
        color = color,
    )
}

@Preview
@Composable
private fun PreviewAutoAIIcon() {
    Column {
        AutoAIIcon("测试")
    }
}

@Composable
fun SiliconFlowPowerByIcon(modifier: Modifier = Modifier) {
    val darkMode = LocalDarkMode.current
    if (!darkMode) {
        AsyncImage(model = R.drawable.siliconflow_light, contentDescription = null, modifier = modifier)
    } else {
        AsyncImage(model = R.drawable.siliconflow_dark, contentDescription = null, modifier = modifier)
    }
}
