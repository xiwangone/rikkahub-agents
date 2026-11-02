package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Tiktok
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.icons.DiscordIcon
import me.rerere.rikkahub.ui.components.ui.icons.TencentQQIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.joinQQGroup
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
private fun SettingGroup.iconColors(): Pair<Color, Color> =
    when (this) {
        SettingGroup.GENERAL -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SettingGroup.MODEL -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SettingGroup.CONNECTION -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SettingGroup.TOOLS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SettingGroup.SECURITY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SettingGroup.AUTOMATION -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SettingGroup.DATA -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SettingGroup.ABOUT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shortcutEntries by vm.shortcutEntries.collectAsStateWithLifecycle(initialValue = vm.shortcutEntriesNow())
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
                    IconButton(
                        onClick = { navController.navigate(Screen.SettingsSearch) },
                    ) {
                        Icon(
                            HugeIcons.GlobalSearch,
                            contentDescription = stringResource(R.string.settings_search_page_title),
                        )
                    }
                    if (settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            },
                        ) {
                            Icon(
                                HugeIcons.Developer,
                                contentDescription = stringResource(R.string.accessibility_developer_options),
                            )
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
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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

            item("settingsShortcuts") {
                SettingsShortcutBar(
                    settings = settings,
                    entries = shortcutEntries,
                    onUpdate = { transform -> vm.updateSettings(transform) },
                    navController = navController,
                )
            }

            item("settingsGroups") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingGroup.entries.forEach { group ->
                        val entries = SettingCatalog.entriesOf(group)
                        val (containerColor, contentColor) = group.iconColors()
                        CardGroup(
                            title = { Text(stringResource(group.titleRes)) },
                        ) {
                            when (group) {
                                SettingGroup.GENERAL -> ColorModeRow(navController, containerColor, contentColor)
                                SettingGroup.ABOUT -> {
                                    AboutRow(navController, containerColor, contentColor)
                                    DocumentationRow(containerColor, contentColor)
                                }
                                else -> Unit
                            }

                            entries.forEach { entry ->
                                item(
                                    modifier = Modifier.testTag("setting_entry_${entry.id}"),
                                    onClick = { navController.navigate(entry.screen) },
                                    leadingContent = {
                                        CardGroupIcon(
                                            icon = entry.icon,
                                            containerColor = containerColor,
                                            contentColor = contentColor,
                                        )
                                    },
                                    supportingContent = { Text(stringResource(entry.descRes)) },
                                    headlineContent = { Text(stringResource(entry.titleRes)) },
                                )
                            }

                            when (group) {
                                SettingGroup.DATA -> ChatStorageRow(filesManager, navController, containerColor, contentColor)
                                SettingGroup.ABOUT -> ShareRow(containerColor, contentColor)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ColorMode.icon(): ImageVector =
    when (this) {
        ColorMode.SYSTEM -> HugeIcons.SmartPhone01
        ColorMode.LIGHT -> HugeIcons.Sun01
        ColorMode.DARK -> HugeIcons.Moon02
    }

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    val onClick = { navController.navigate(Screen.SettingProvider) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.error, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Alert01,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_config_api_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_config_api_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

@Composable
private fun SecurityDegradedWarningCard() {
    Card(
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

@Composable
private fun CardGroupScope.ColorModeRow(
    navController: Navigator,
    containerColor: Color,
    contentColor: Color,
) {
    var colorMode by rememberColorMode()
    formItem {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroupIcon(colorMode.icon(), containerColor = containerColor, contentColor = contentColor)
            Text(stringResource(R.string.setting_page_color_mode))
        }
        ConnectedChoiceButtons(
            options = ColorMode.entries,
            selected = colorMode,
            onSelect = {
                colorMode = it
                navController.navigate(Screen.Setting) {
                    popUpTo(Screen.Setting) {
                        inclusive = true
                    }
                }
            },
        ) { mode, selected ->
            ConnectedChoiceLabel(
                text =
                    when (mode) {
                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                    },
                selected = selected,
                icon = mode.icon(),
            )
        }
    }
}

@Composable
private fun CardGroupScope.ChatStorageRow(
    filesManager: FilesManager,
    navController: Navigator,
    containerColor: Color,
    contentColor: Color,
) {
    val storageState by produceState(-1 to 0L) {
        value = filesManager.countChatFiles()
    }
    item(
        onClick = { navController.navigate(Screen.SettingFiles) },
        leadingContent = { CardGroupIcon(HugeIcons.ImageUpload, containerColor = containerColor, contentColor = contentColor) },
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
private fun CardGroupScope.AboutRow(
    navController: Navigator,
    containerColor: Color,
    contentColor: Color,
) {
    val context = LocalContext.current
    item(
        onClick = { navController.navigate(Screen.SettingAbout) },
        leadingContent = { CardGroupIcon(HugeIcons.Clapping01, containerColor = containerColor, contentColor = contentColor) },
        supportingContent = { Text(stringResource(R.string.setting_page_about_desc)) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var showQQGroupSheet by remember { mutableStateOf(false) }
                IconButton(onClick = { showQQGroupSheet = true }) {
                    Icon(
                        imageVector = TencentQQIcon,
                        contentDescription = "QQ",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (showQQGroupSheet) {
                    QQGroupBottomSheet(onDismiss = { showQQGroupSheet = false })
                }
                IconButton(
                    onClick = { context.openUrl("https://discord.gg/9weBqxe5c4") },
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
private fun CardGroupScope.DocumentationRow(
    containerColor: Color,
    contentColor: Color,
) {
    val context = LocalContext.current
    item(
        onClick = {
            val docUrl =
                if (java.util.Locale.getDefault().language == "zh") {
                    "https://docs.rikka-ai.com/zh/introduction"
                } else {
                    "https://docs.rikka-ai.com/introduction"
                }
            context.openUrl(docUrl)
        },
        leadingContent = { CardGroupIcon(HugeIcons.Book01, containerColor = containerColor, contentColor = contentColor) },
        supportingContent = { Text(stringResource(R.string.setting_page_documentation_desc)) },
        headlineContent = { Text(stringResource(R.string.setting_page_documentation)) },
    )
}

@Composable
private fun CardGroupScope.ShareRow(
    containerColor: Color,
    contentColor: Color,
) {
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
        leadingContent = { CardGroupIcon(HugeIcons.Share04, containerColor = containerColor, contentColor = contentColor) },
        supportingContent = { Text(stringResource(R.string.setting_page_share_desc)) },
        headlineContent = { Text(stringResource(R.string.setting_page_share)) },
    )
}

@Composable
private fun SettingsShortcutBar(
    settings: Settings,
    entries: List<SettingEntry>,
    onUpdate: ((Settings) -> Settings) -> Unit,
    navController: Navigator,
) {
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        SettingShortcutEditor(
            settings = settings,
            onUpdate = onUpdate,
            onDismiss = { showEditor = false },
        )
    }

    CardGroup(
        title = { Text(stringResource(R.string.setting_page_shortcuts)) },
    ) {
        item(
            supportingContent = {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.setting_page_shortcuts_empty))
                } else {
                    Text(
                        stringResource(
                            R.string.setting_page_shortcuts_count,
                            entries.size,
                            SETTING_SHORTCUT_LIMIT,
                        ),
                    )
                }
            },
            headlineContent = { Text(stringResource(R.string.setting_page_shortcuts)) },
            trailingContent = {
                TextButton(onClick = { showEditor = true }) {
                    Text(stringResource(R.string.setting_page_shortcuts_edit))
                }
            },
        )

        if (entries.isNotEmpty()) {
            formItem {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { entry ->
                                ShortcutTile(
                                    entry = entry,
                                    modifier = Modifier.weight(1f),
                                    onClick = { navController.navigate(entry.screen) },
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(
    entry: SettingEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (containerColor, contentColor) = entry.group.iconColors()
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CardGroupIcon(
                icon = entry.icon,
                containerColor = containerColor,
                contentColor = contentColor,
                containerSize = 32.dp,
                iconSize = 16.dp,
            )
            Text(
                text = stringResource(entry.titleRes),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingShortcutEditor(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = settings.settingShortcutIds

    fun toggle(
        id: String,
        on: Boolean,
    ) {
        onUpdate { current ->
            val ids = current.settingShortcutIds
            val next =
                if (on) {
                    if (id in ids || ids.size >= SETTING_SHORTCUT_LIMIT) ids else ids + id
                } else {
                    ids - id
                }
            current.copy(settingShortcutIds = next)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_shortcuts_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingGroup.entries.forEach { group ->
                    val entries = SettingCatalog.entriesOf(group)
                    if (entries.isEmpty()) return@forEach
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    entries.forEach { entry ->
                        val checked = entry.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = checked || selected.size < SETTING_SHORTCUT_LIMIT,
                                onCheckedChange = { toggle(entry.id, it) },
                            )
                            Text(
                                text = stringResource(entry.titleRes),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}

private data class QQGroup(
    val name: String,
    val key: String? = null,
    val number: String? = null,
    val icon: ImageVector = TencentQQIcon,
)

private val QQ_GROUPS =
    listOf(
        QQGroup("RikkaHub 一群", "4POE46u9e_zoy1TkNfWdCvueR9CKFJdk"),
        QQGroup("RikkaHub 二群", "Qsm0whzbPsm1UyNpR683ulLyMZ2Pqrw0"),
        QQGroup("RikkaHub 三群", "Qc9oP-9tXioZeQEvEvI2_owWtBAIx3lS"),
        QQGroup("抖音一群", number = "569655479852", icon = HugeIcons.Tiktok),
    )

@Composable
private fun QQGroupBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.copied_to_clipboard)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QQ_GROUPS.forEach { group ->
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = group.number?.let { number -> { Text(number) } },
                    leadingContent = {
                        Icon(
                            imageVector = group.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (group.number != null) {
                                    context.writeClipboardText(group.number)
                                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                                } else {
                                    context.joinQQGroup(group.key)
                                }
                                onDismiss()
                            },
                )
            }
        }
    }
}
