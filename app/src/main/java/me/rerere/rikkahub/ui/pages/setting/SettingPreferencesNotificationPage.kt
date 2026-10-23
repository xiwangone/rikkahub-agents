package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var hasOverlayAccess by remember { mutableStateOf(runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasOverlayAccess = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        val old = displaySetting
        displaySetting = setting
        vm.updateSettings { current ->
            // Preserve concurrent nested preference edits by applying only values changed by this event.
            val currentDisplay = current.displaySetting
            current.copy(displaySetting = currentDisplay.copy(
                enableNotificationOnMessageGeneration = if (old.enableNotificationOnMessageGeneration != setting.enableNotificationOnMessageGeneration) setting.enableNotificationOnMessageGeneration else currentDisplay.enableNotificationOnMessageGeneration,
                enableLiveUpdateNotification = if (old.enableLiveUpdateNotification != setting.enableLiveUpdateNotification) setting.enableLiveUpdateNotification else currentDisplay.enableLiveUpdateNotification,
                enableFloatingChatWindow = if (old.enableFloatingChatWindow != setting.enableFloatingChatWindow) setting.enableFloatingChatWindow else currentDisplay.enableFloatingChatWindow,
            ))
        }
    }
    var showPauseUpdatesDialog by remember { mutableStateOf(false) }
    fun pauseUpdatesFor(days: Int) {
        val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
        vm.updateSettings { current ->
            current.copy(displaySetting = current.displaySetting.copy(showUpdates = true, showUpdatesUntilEpochMillis = until))
        }
        displaySetting = displaySetting.copy(showUpdates = true, showUpdatesUntilEpochMillis = until)
        showPauseUpdatesDialog = false
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val permissionState =
        rememberPermissionState(
            permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setOf(
                        PermissionNotification,
                    )
                } else {
                    emptySet()
                },
        )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_updates_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUpdates,
                                onCheckedChange = {
                                    displaySetting = displaySetting.copy(showUpdates = it)
                                    vm.updateSettings { current -> current.copy(displaySetting = current.displaySetting.copy(showUpdates = it)) }
                                },
                            )
                        },
                    )
                    item(
                        onClick = { showPauseUpdatesDialog = true },
                        headlineContent = { Text(stringResource(R.string.setting_updates_pause_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_updates_pause_desc)) },
                    )
                    item(
                        headlineContent = {
                            Text(
                                stringResource(R.string.setting_display_page_notification_message_generated),
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.setting_display_page_notification_message_generated_desc),
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(
                                        displaySetting.copy(enableNotificationOnMessageGeneration = it),
                                    )
                                },
                            )
                        },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.setting_display_page_live_update_notification),
                                )
                            },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.setting_display_page_live_update_notification_desc),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                    },
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.floating_chat_setting_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (hasOverlayAccess) R.string.floating_chat_setting_desc
                                    else R.string.floating_chat_setting_permission_desc,
                                ),
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableFloatingChatWindow,
                                onCheckedChange = { enabled ->
                                    updateDisplaySetting(displaySetting.copy(enableFloatingChatWindow = enabled))
                                    if (enabled && !runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}"),
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
