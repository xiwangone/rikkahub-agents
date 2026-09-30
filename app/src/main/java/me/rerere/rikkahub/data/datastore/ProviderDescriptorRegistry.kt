package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.AICORE_PROVIDER_ID
import me.rerere.ai.provider.LITERT_PROVIDER_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

/**
 * Provider UI 描述。
 *
 * ProviderSetting（ai 模块）是纯数据层，不再携带 @Composable 描述；
 * 内置预设的展示文案集中在此注册表，按 provider id 索引。
 * 用户自建 provider 无注册项时回退为空描述（与此前 data class 默认值 {} 行为一致）。
 */
data class ProviderDescriptor(
    val description: @Composable () -> Unit = {},
    val shortDescription: @Composable () -> Unit = {},
)

object ProviderDescriptors {
    private val descriptors: Map<Uuid, ProviderDescriptor> = mapOf(
        AICORE_PROVIDER_ID to ProviderDescriptor(
            description = {
                Text(stringResource(R.string.aicore_provider_description))
            },
            shortDescription = {
                Text(stringResource(R.string.aicore_provider_short_description))
            },
        ),
        LITERT_PROVIDER_ID to ProviderDescriptor(
            description = {
                Text(stringResource(R.string.litert_provider_description))
            },
            shortDescription = {
                Text(stringResource(R.string.litert_provider_short_description))
            },
        ),
        DEFAULT_GEMINI_OAUTH_PROVIDER_ID to ProviderDescriptor(
            shortDescription = {
                Text(stringResource(R.string.gemini_provider_short_description))
            },
        ),
        Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953") to ProviderDescriptor( // AiHubMix
            description = {
                Text(
                    text = buildAnnotatedString {
                        append("提供 OpenAI、Claude、Google Gemini 等主流模型的高并发和稳定服务")
                        appendLine()
                        append("官网：")
                        withLink(LinkAnnotation.Url("https://aihubmix.com?aff=pG7r")) {
                            withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                                append("https://aihubmix.com")
                            }
                        }
                        appendLine()
                        append("充值: ")
                        withLink(LinkAnnotation.Url("https://console.aihubmix.com/topup")) {
                            withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                                append("https://console.aihubmix.com/topup")
                            }
                        }
                    }
                )
            },
            shortDescription = {
                Text(
                    text = "支持gpt, claude, gemini等200+模型"
                )
            },
        ),
        Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8") to ProviderDescriptor( // 硅基流动
            description = {
                MarkdownBlock(
                    content = """
                    ${stringResource(R.string.silicon_flow_description)}
                    ${stringResource(R.string.silicon_flow_website)}
                """.trimIndent()
                )
            },
        ),
        Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630") to ProviderDescriptor( // 小马算力
            description = {
                MarkdownBlock(
                    content = """
                    小马算力是一家提供国产模型的API网关服务，使用统一接口接入多种模型
                    官网: [tokenpony.cn](https://www.tokenpony.cn/79clb)
                """.trimIndent()
                )
            },
        ),
        Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7") to ProviderDescriptor( // 302.AI
            description = {
                Text(
                    text = buildAnnotatedString {
                        append("企业级AI服务, 官网：")
                        withLink(LinkAnnotation.Url("https://302.ai/")) {
                            withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                                append("https://302.ai/")
                            }
                        }
                    }
                )
            },
        ),
        Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd") to ProviderDescriptor( // 随想AI网关
            description = {
                Text(
                    text = buildAnnotatedString {
                        append("可靠高效的 API 中继服务，提供 Claude、Codex、Gemini 等中继服务。注重隐私·无数据倒卖·无模型掺水，充值额度 1:1，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。\n")
                        append("官网：")
                        withLink(LinkAnnotation.Url("https://sui-xiang.com")) {
                            withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                                append("https://sui-xiang.com")
                            }
                        }
                    }
                )
            },
            shortDescription = {
                Text(
                    text = "Claude、Codex、Gemini 等中继服务，1:1 充值"
                )
            },
        ),
        Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8") to ProviderDescriptor( // AckAI
            description = {
                Text(
                    text = buildAnnotatedString {
                        append(
                            "所有AI大模型全都可以用！无需翻墙！价格是官方5折！\n" +
                                "官网："
                        )
                        withLink(LinkAnnotation.Url("https://ackai.fun/register?aff=jxpP")) {
                            withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                                append("https://ackai.fun")
                            }
                        }
                    }
                )
            },
        ),
        Uuid.parse("4da09554-8844-4cc8-a4a9-fe1b2515e91b") to ProviderDescriptor( // UnifyLLM
            description = {
                Text(
                    text = buildAnnotatedString {
                        append("一站式LLM API中转平台货源站\n官网：")
                        withLink(LinkAnnotation.Url("https://www.unifyllm.com/")) {
                            withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                                append("https://www.unifyllm.com/")
                            }
                        }
                    }
                )
            },
        ),
    )

    fun get(id: Uuid): ProviderDescriptor = descriptors[id] ?: ProviderDescriptor()
}
