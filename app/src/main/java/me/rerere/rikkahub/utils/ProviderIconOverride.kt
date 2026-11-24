package me.rerere.rikkahub.utils

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.vault.VaultProviderKeyRefs

/**
 * 提供商图标覆盖值的编解码与解析（纯函数，可在 JVM 单测里跑）。
 *
 * 覆盖表以提供商名作为键，值是带前缀的图标来源：
 * - `asset:<文件名>`：`assets/icons/` 下已有的品牌文件，如 `asset:openrouter.svg`
 * - `file:<相对路径>`：app filesDir 下的下载文件，如 `file:provider-icons/mazal.svg`
 *
 * 渲染优先级：覆盖表 > 品牌匹配（[computeAIIconByName]）> 首字母兜底。
 * 值无法解析（前缀未知、文件名/路径为空）时当作无覆盖处理，不抛错、不静默吞掉
 * 整张表 —— 旧数据或手写脏值只影响单个提供商的图标回退。
 */
sealed interface ProviderIconSource {
    /** `assets/icons/` 下的品牌文件。 */
    data class Asset(val fileName: String) : ProviderIconSource

    /** app filesDir 下的相对路径。 */
    data class LocalFile(val relativePath: String) : ProviderIconSource
}

object ProviderIconOverrides {
    const val ASSET_PREFIX = "asset:"
    const val FILE_PREFIX = "file:"

    fun encodeAsset(fileName: String): String = ASSET_PREFIX + fileName

    fun encodeFile(relativePath: String): String = FILE_PREFIX + relativePath

    /** 解析单个覆盖值；无法解析返回 null（调用方回退到品牌匹配）。 */
    fun parse(value: String?): ProviderIconSource? {
        if (value.isNullOrBlank()) return null
        return when {
            value.startsWith(ASSET_PREFIX) -> {
                val fileName = value.removePrefix(ASSET_PREFIX)
                if (fileName.isBlank() || fileName.contains('/')) null else ProviderIconSource.Asset(fileName)
            }
            value.startsWith(FILE_PREFIX) -> {
                val path = value.removePrefix(FILE_PREFIX)
                if (path.isBlank() || path.startsWith('/') || path.contains("..")) {
                    null
                } else {
                    ProviderIconSource.LocalFile(path)
                }
            }
            else -> null
        }
    }
}

/**
 * 按优先级解析一个提供商应渲染的图标来源：
 * 覆盖表（[override] 为该提供商的存储值）> 品牌匹配 > 无（首字母兜底）。
 */
fun resolveProviderIcon(
    name: String,
    override: String?,
    availableIcons: Set<String>,
    baseUrl: String? = null,
): ProviderIconSource? {
    ProviderIconOverrides.parse(override)?.let { return it }
    return computeAIIconByName(name, availableIcons, baseUrl)?.let { ProviderIconSource.Asset(it) }
}

/**
 * 按优先级解析一个模型应渲染的图标来源，与提供商图标同一条链：
 * 覆盖表（[override] 为该模型所属提供商的存储值）> 模型名品牌匹配
 * （[computeModelIconByName]）> 提供商名/端点品牌匹配（模型名解析不到时
 * 用所属提供商的标兜底，如决策模型 d3 在 AMD 列表里显示 AMD 标）>
 * 无（首字母兜底）。
 *
 * 覆盖表以提供商名为键：模型图标用的就是所属提供商的那一项，列表页与
 * 详情页改了覆盖，模型列表与输入框当前模型会跟着变，不用再单独登记模型。
 */
fun resolveModelIcon(
    modelId: String,
    override: String?,
    availableIcons: Set<String>,
    providerName: String? = null,
    baseUrl: String? = null,
): ProviderIconSource? {
    ProviderIconOverrides.parse(override)?.let { return it }
    computeModelIconByName(modelId, availableIcons)?.let { return ProviderIconSource.Asset(it) }
    if (!providerName.isNullOrBlank()) {
        computeAIIconByName(providerName, availableIcons, baseUrl)
            ?.let { return ProviderIconSource.Asset(it) }
    }
    return null
}

/**
 * 取提供商的端点地址（用于按域名匹配品牌图标）。
 *
 * 只有基于 HTTP 的提供商有端点；本地模型（LiteRt / LlamaCpp）没有，
 * 返回 null 时匹配逻辑会退回按名字匹配。
 * 端点可能是 `$$名字.endpoint` 引用：先解析再返回，否则域名匹配不到。
 */
fun ProviderSetting.baseUrlOrNull(): String? = when (this) {
    is ProviderSetting.OpenAI -> VaultProviderKeyRefs.resolveValue(baseUrl)
    is ProviderSetting.Google -> VaultProviderKeyRefs.resolveValue(baseUrl)
    is ProviderSetting.Claude -> VaultProviderKeyRefs.resolveValue(baseUrl)
    is ProviderSetting.Backend -> VaultProviderKeyRefs.resolveValue(baseUrl)
    else -> null
}
