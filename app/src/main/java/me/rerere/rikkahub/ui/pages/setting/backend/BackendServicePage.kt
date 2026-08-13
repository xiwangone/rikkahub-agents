package me.rerere.rikkahub.ui.pages.setting.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 后端服务页（连接管理中枢 P2 落地）。
 *
 * 定位：reasonix 从「模型提供商」语义移出，作为「执行后端」统一管理。
 * 当前 MVP：显示 reasonix 连接配置 + 健康状态；后续（P4）扩展为
 * BackendConnection 统一连接模型（reasonix / ECS / PC / 自建服务）。
 */
@Composable
fun BackendServicePage() {
    val settingsStore: SettingsStore = koinInject()
    val reasonix = remember {
        settingsStore.settingsFlow.value.providers.filterIsInstance<me.rerere.ai.provider.ProviderSetting.Reasonix>().firstOrNull()
    }
    var health by remember { mutableStateOf("unknown") }

    // 健康探测：reasonix serve 入口（baseUrl 的 host:port）
    LaunchedEffect(reasonix?.baseUrl) {
        val base = reasonix?.baseUrl ?: return@LaunchedEffect
        health = withContext(Dispatchers.IO) {
            runCatching {
                val url = java.net.URI(base).toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.connect()
                conn.responseCode
            }.fold(
                onSuccess = { code -> if (code in 200..499) "reachable" else "unreachable($code)" },
                onFailure = { "unreachable" },
            )
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.backend_service_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // reasonix 后端卡片
            CardGroup {
                item(
                    headlineContent = { Text("Reasonix（执行后端）") },
                    supportingContent = {
                        Text(
                            if (reasonix == null) {
                                "未配置——reasonix 作为 AI 执行后端（任务/带 MCP），连接地址在模型提供商配置"
                            } else {
                                buildString {
                                    append("状态: ")
                                    append(
                                        when (health) {
                                            "reachable" -> "✅ 可达"
                                            "unreachable" -> "❌ 不可达"
                                            else -> "检测中…"
                                        }
                                    )
                                    if (reasonix.baseUrl.isNotBlank()) {
                                        append("\n地址: ").append(reasonix.baseUrl)
                                    }
                                    append("\n连接模式: ").append(
                                        if (reasonix.connectionMode == "ssh") "SSH 反向隧道" else "serve（HTTP/SSE）"
                                    )
                                    append("\nWeb 桥: ").append(if (reasonix.webBridgeEnabled) "已启用" else "未启用")
                                }
                            }
                        )},
                )
            }

            // 后端说明
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backend_service_about_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.backend_service_about_desc))
                    },
                )
            }
        }
    }
}
