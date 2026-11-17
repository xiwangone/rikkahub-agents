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
import me.rerere.rikkahub.utils.listBundledIconNames
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
            modifier = Modifier.padding(4.dp),
        )
    }
}

/**
 * 提供商/模型图标：优先级为覆盖表 > 品牌匹配 > 首字母。
 *
 * @param iconOverride 该提供商在 [me.rerere.rikkahub.data.datastore.Settings.providerIconOverrides]
 * 里的存储值（`asset:` / `file:` 前缀）；为 null 时不查覆盖表。其他调用点保持默认行为，
 * 只有提供商列表页传入覆盖值。
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
                // 覆盖指向的文件已不在磁盘上：回退到品牌匹配，再不行才显示首字母。
                val brandPath = remember(name, availableIcons) { computeAIIconByName(name, availableIcons) }
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
