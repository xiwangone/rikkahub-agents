package me.rerere.rikkahub.data.vault

import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting

/**
 * Resolve a stored literal or `$$vault-name` only for a runtime request.
 * Missing references fail closed instead of sending the reference text to a vendor.
 * A literal value remains unchanged, preserving pre-vault settings.
 */
internal fun resolveVaultCredentialValue(
    raw: String,
    resolve: (String) -> String? = { VaultProviderKeyRefs.resolveOrNull(it) },
): String = resolve(raw).orEmpty()

internal fun MediaGenerationProviderSetting.withResolvedVaultCredentials(
    resolve: (String) -> String? = { VaultProviderKeyRefs.resolveOrNull(it) },
): MediaGenerationProviderSetting = copyProvider(
    apiKey = resolveVaultCredentialValue(apiKey, resolve),
)

internal fun ASRProviderSetting.withResolvedVaultCredentials(
    resolve: (String) -> String? = { VaultProviderKeyRefs.resolveOrNull(it) },
): ASRProviderSetting = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is ASRProviderSetting.DashScope -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is ASRProviderSetting.Volcengine -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is ASRProviderSetting.MiMo -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is ASRProviderSetting.Step -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
}

internal fun TTSProviderSetting.withResolvedVaultCredentials(
    resolve: (String) -> String? = { VaultProviderKeyRefs.resolveOrNull(it) },
): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.Gemini -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.SystemTTS -> this
    is TTSProviderSetting.MiniMax -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.Qwen -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.Groq -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.XAI -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.MiMo -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.ElevenLabs -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.FishAudio -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.Step -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is TTSProviderSetting.Volcengine -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
}

@Suppress("CyclomaticComplexMethod")  // 凭证/重试链路本质是分支枚举，拆分反而打散可读性；复杂度优化列入后续专项
internal fun SearchServiceOptions.withResolvedVaultCredentials(
    resolve: (String) -> String? = { VaultProviderKeyRefs.resolveOrNull(it) },
): SearchServiceOptions = when (this) {
    is SearchServiceOptions.BingLocalOptions -> this
    is SearchServiceOptions.DuckDuckGoOptions -> this
    is SearchServiceOptions.ZhipuOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.DoubaoOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.TavilyOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.ExaOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.SearXNGOptions -> copy(password = resolveVaultCredentialValue(password, resolve))
    is SearchServiceOptions.LinkUpOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.BraveOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.MetasoOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.OllamaOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.PerplexityOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.FirecrawlOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.JinaOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.BochaOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.RikkaHubOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.GrokOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.TinyfishOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.SerperOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.DeepSeekOptions -> copy(apiKey = resolveVaultCredentialValue(apiKey, resolve))
    is SearchServiceOptions.CustomJsOptions -> this
}
