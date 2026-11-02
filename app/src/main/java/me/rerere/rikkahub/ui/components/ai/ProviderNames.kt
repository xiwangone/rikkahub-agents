package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R

/**
 * Provider 显示名：内置英文默认名在中文界面映射为中文；用户自定义名原样返回。
 * 品牌名（OpenAI/Google/Claude/Codex/Grok/Gemini）保留原文。
 */
@Composable
fun providerDisplayName(provider: ProviderSetting): String =
    when (provider.name) {
        "AICore (on-device)" -> stringResource(R.string.provider_display_name_aicore)
        "Local · LiteRT" -> stringResource(R.string.provider_display_name_litert)
        "Local · llama.cpp" -> stringResource(R.string.provider_display_name_llamacpp)
        stringResource(R.string.backend_service) -> stringResource(R.string.provider_display_name_backend)
        else -> provider.name
    }