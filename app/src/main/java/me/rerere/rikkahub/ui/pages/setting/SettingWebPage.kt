package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Web 设置二级入口页。
 *
 * 两个入口分别进入三级页：
 * - Web 服务：手机本地 Web 服务（端口/JWT/密码/地址）
 * - Web 桥：反向隧道到 ECS（供 Reasonix 等访问手机能力）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWebPage() {
    val webServerManager: WebServerManager = koinInject()
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_web_capability)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Web 服务入口
            item {
                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_web_server)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingWebServer) },
                        leadingContent = {
                            Text(
                                text =
                                    if (serverState.isRunning) stringResource(R.string.setting_web_server_status_running)
                                    else stringResource(R.string.setting_web_server_status_stopped),
                                color =
                                    if (serverState.isRunning) Color(0xFF22C55E)
                                    else Color.Gray,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                    )
                }
            }

            // Web 桥入口
            item {
                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_web_bridge_title)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingWebBridge) },
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_desc_detail)) },
                    )
                }
            }
        }
    }
}
