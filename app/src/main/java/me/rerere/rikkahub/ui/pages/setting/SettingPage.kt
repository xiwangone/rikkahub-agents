package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.WavingHand01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val secretsStore: SkillSecretsStore = koinInject()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            },
                        ) {
                            Icon(HugeIcons.Developer, "Developer")
                        }
                    }
                },
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
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            if (secretsStore.isSecurityDegraded()) {
                item {
                    SecurityDegradedWarningCard()
                }
            }

            // 分组渲染完全由 SettingCatalog 驱动：组顺序 = SettingGroup 枚举顺序，
            // 组内顺序 = 注册表顺序。带内联控件或动作型的项在此单独处理。
            item("settingsGroups") {
                SettingGroup.entries.forEach { group ->
                    val entries = SettingCatalog.entriesOf(group)
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(group.titleRes)) },
                    ) {
                        when (group) {
                            SettingGroup.GENERAL -> ColorModeRow(navController)
                            SettingGroup.ABOUT -> {
                                AboutRow(navController)
                                DocumentationRow()
                            }
                            else -> Unit
                        }

                        entries.forEach { entry ->
                            item(
                                onClick = { navController.navigate(entry.screen) },
                                leadingContent = { Icon(entry.icon, null) },
                                supportingContent = { Text(stringResource(entry.descRes)) },
                                headlineContent = { Text(stringResource(entry.titleRes)) },
                            )
                        }

                        when (group) {
                            SettingGroup.DATA -> ChatStorageRow(filesManager, navController)
                            SettingGroup.ABOUT -> ShareRow()
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                },
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

@Composable
private fun SecurityDegradedWarningCard() {
    Card(
        modifier = Modifier.padding(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.setting_page_security_degraded_title))
            },
            supportingContent = {
                Text(stringResource(R.string.setting_page_security_degraded_desc))
            },
            leadingContent = {
                Icon(HugeIcons.Shield01, null)
            },
            colors =
                ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
        )
    }
}


/**
 * 带内联控件或动作型的设置项（因含交互控件/外部跳转，未进入 [SettingCatalog]）。
 */
@Composable
private fun CardGroupScope.ColorModeRow(navController: Navigator) {
    var colorMode by rememberColorMode()
    val selectedColorModeText =
        when (colorMode) {
            ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
            ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
            ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
        }
    item(
        leadingContent = { Icon(HugeIcons.Sun01, null) },
        trailingContent = {
            Select(
                options = ColorMode.entries,
                selectedOption = colorMode,
                onOptionSelected = {
                    colorMode = it
                    navController.navigate(Screen.Setting) {
                        popUpTo(Screen.Setting) {
                            inclusive = true
                        }
                    }
                },
                optionToString = {
                    when (it) {
                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                    }
                },
                modifier = Modifier.width(150.dp),
            )
        },
        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
        supportingContent = { Text(selectedColorModeText) },
    )
}

@Composable
private fun CardGroupScope.ChatStorageRow(filesManager: FilesManager, navController: Navigator) {
    val storageState by produceState(-1 to 0L) {
        value = filesManager.countChatFiles()
    }
    item(
        onClick = { navController.navigate(Screen.SettingFiles) },
        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
        supportingContent = {
            if (storageState.first == -1) {
                Text(stringResource(R.string.calculating))
            } else {
                Text(
                    stringResource(
                        R.string.setting_page_chat_storage_desc,
                        storageState.first,
                        storageState.second / 1024 / 1024.0,
                    ),
                )
            }
        },
        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
    )
}

@Composable
private fun CardGroupScope.AboutRow(navController: Navigator) {
    val context = LocalContext.current
    item(
        onClick = { navController.navigate(Screen.SettingAbout) },
        leadingContent = { Icon(HugeIcons.Clapping01, null) },
        supportingContent = { Text(stringResource(R.string.setting_page_about_desc)) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        context.openUrl("https://discord.gg/9weBqxe5c4")
                    },
                ) {
                    Icon(
                        imageVector = DiscordIcon,
                        contentDescription = "Discord",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        headlineContent = { Text(stringResource(R.string.setting_page_about)) },
    )
}

@Composable
private fun CardGroupScope.DocumentationRow() {
    val context = LocalContext.current
    item(
        onClick = {
            val docUrl =
                if (java.util.Locale
                        .getDefault()
                        .language == "zh"
                ) {
                    "https://docs.rikka-ai.com/zh/introduction"
                } else {
                    "https://docs.rikka-ai.com/introduction"
                }
            context.openUrl(docUrl)
        },
        leadingContent = { Icon(HugeIcons.Book01, null) },
        supportingContent = { Text(stringResource(R.string.setting_page_documentation_desc)) },
        headlineContent = { Text(stringResource(R.string.setting_page_documentation)) },
    )
}

@Composable
private fun CardGroupScope.ShareRow() {
    val context = LocalContext.current
    val shareText = stringResource(R.string.setting_page_share_text)
    val share = stringResource(R.string.setting_page_share)
    val noShareApp = stringResource(R.string.setting_page_no_share_app)
    item(
        onClick = {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, shareText)
            try {
                context.startActivity(Intent.createChooser(intent, share))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
            }
        },
        leadingContent = { Icon(HugeIcons.Share04, null) },
        supportingContent = { Text(stringResource(R.string.setting_page_share_desc)) },
        headlineContent = { Text(stringResource(R.string.setting_page_share)) },
    )
}
