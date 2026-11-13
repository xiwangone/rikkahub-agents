package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Activity01
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Archive02
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Contact
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Database01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.Location01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Notification02
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Sun03
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Wifi01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalToaster

@Composable
internal fun KeyboardStatusRowSubtitle() {
    val ctx = LocalContext.current

    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val installed =
        remember(resumeTick) {
            runCatching {
                ctx.packageManager.getPackageInfo(KEYBOARD_PACKAGE, 0)
                true
            }.getOrDefault(false)
        }
    val isActiveIme =
        remember(resumeTick) {
            installed &&
                runCatching {
                    android.provider.Settings.Secure
                        .getString(
                            ctx.contentResolver,
                            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                        )?.startsWith("$KEYBOARD_PACKAGE/") == true
                }.getOrDefault(false)
        }

    val (dotColor, label) =
        when {
            !installed -> {
                androidx.compose.ui.graphics
                    .Color(0xFFEF4444) to
                    stringResource(R.string.assistant_page_local_tools_keyboard_status_not_installed)
            }

            !isActiveIme -> {
                androidx.compose.ui.graphics
                    .Color(0xFFF59E0B) to
                    stringResource(R.string.assistant_page_local_tools_keyboard_status_installed)
            }

            else -> {
                androidx.compose.ui.graphics
                    .Color(0xFF22C55E) to
                    stringResource(R.string.assistant_page_local_tools_keyboard_desc)
            }
        }

    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = dotColor)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val KEYBOARD_PACKAGE = "dev.patrickgold.florisboard"

@Composable
internal fun PermissionedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    requiredRuntimePerms: List<String> = emptyList(),
    requiresWriteSettings: Boolean = false,
    requiresDndAccess: Boolean = false,
    requiresAccessibilityService: Boolean = false,
    requiresNotificationListener: Boolean = false,
    requiresAllFilesAccess: Boolean = false,
    enabled: Boolean = true,
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val deniedToastFmt = stringResource(R.string.assistant_page_local_tools_perm_denied_toast)
    val permWriteSettingsLabel = stringResource(R.string.assistant_page_local_tools_perm_write_settings)
    val permDndLabel = stringResource(R.string.assistant_page_local_tools_perm_dnd_access)
    val permAccessibilityLabel = stringResource(R.string.assistant_page_local_tools_perm_accessibility_service)
    val permNotificationLabel = stringResource(R.string.assistant_page_local_tools_perm_notification_access)
    val permAllFilesLabel = stringResource(R.string.assistant_page_local_tools_perm_all_files_access)

    var showDialog by remember { mutableStateOf(false) }
    var pendingSpecialResume by remember { mutableStateOf(false) }
    var resumeTrigger by remember { mutableStateOf(0) }

    val runtimePermLauncher =
        rememberSafeLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isEmpty()) {
                onCheckedChange(true)
            } else {
                toaster.show(
                    message = String.format(deniedToastFmt, denied.joinToString(", ")),
                    type = ToastType.Error,
                )
            }
        }

    val specialPermLauncher =
        rememberSafeLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    resumeTrigger++
                    if (pendingSpecialResume) {
                        val granted =
                            when {
                                requiresWriteSettings -> PermissionHelper.hasWriteSettings(ctx)
                                requiresDndAccess -> PermissionHelper.hasDndAccess(ctx)
                                requiresAccessibilityService -> PermissionHelper.hasAccessibilityService(ctx)
                                requiresNotificationListener -> PermissionHelper.hasNotificationListener(ctx)
                                requiresAllFilesAccess -> PermissionHelper.hasAllFilesAccess(ctx)
                                else -> false
                            }
                        pendingSpecialResume = false
                        if (granted) {
                            onCheckedChange(true)
                        } else {
                            val name =
                                when {
                                    requiresWriteSettings -> permWriteSettingsLabel
                                    requiresDndAccess -> permDndLabel
                                    requiresAccessibilityService -> permAccessibilityLabel
                                    requiresNotificationListener -> permNotificationLabel
                                    requiresAllFilesAccess -> permAllFilesLabel
                                    else -> ""
                                }
                            toaster.show(
                                message = String.format(deniedToastFmt, name),
                                type = ToastType.Error,
                            )
                        }
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun requestPermission() {
        when {
            requiresNotificationListener -> {
                if (PermissionHelper.hasNotificationListener(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresAccessibilityService -> {
                if (PermissionHelper.hasAccessibilityService(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresWriteSettings -> {
                if (PermissionHelper.hasWriteSettings(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresDndAccess -> {
                if (PermissionHelper.hasDndAccess(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresAllFilesAccess -> {
                if (PermissionHelper.hasAllFilesAccess(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiredRuntimePerms.isNotEmpty() -> {
                if (PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)) {
                    onCheckedChange(true)
                } else {
                    runtimePermLauncher.launch(requiredRuntimePerms.toTypedArray())
                }
            }

            else -> {
                onCheckedChange(true)
            }
        }
    }

    val permsKey = remember(requiredRuntimePerms) { requiredRuntimePerms.joinToString(",") }
    val permissionMissing =
        remember(
            checked,
            resumeTrigger,
            permsKey,
            requiresWriteSettings,
            requiresDndAccess,
            requiresAccessibilityService,
            requiresNotificationListener,
            requiresAllFilesAccess,
        ) {
            checked &&
                when {
                    requiredRuntimePerms.isNotEmpty() -> !PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)
                    requiresWriteSettings -> !PermissionHelper.hasWriteSettings(ctx)
                    requiresDndAccess -> !PermissionHelper.hasDndAccess(ctx)
                    requiresAccessibilityService -> !PermissionHelper.hasAccessibilityService(ctx)
                    requiresNotificationListener -> !PermissionHelper.hasNotificationListener(ctx)
                    requiresAllFilesAccess -> !PermissionHelper.hasAllFilesAccess(ctx)
                    else -> false
                }
        }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(stringResource(R.string.assistant_page_local_tools_perm_special_dialog_title))
            },
            text = {
                Text(stringResource(R.string.assistant_page_local_tools_perm_special_dialog_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    val intent =
                        when {
                            requiresWriteSettings -> PermissionHelper.writeSettingsIntent(ctx)
                            requiresDndAccess -> PermissionHelper.dndAccessIntent(ctx)
                            requiresAccessibilityService -> PermissionHelper.accessibilitySettingsIntent()
                            requiresNotificationListener -> PermissionHelper.notificationListenerSettingsIntent()
                            requiresAllFilesAccess -> PermissionHelper.allFilesAccessIntent(ctx)
                            else -> null
                        }
                    if (intent != null) {
                        pendingSpecialResume = true
                        specialPermLauncher.launch(intent)
                    }
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Column(horizontalAlignment = Alignment.End) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { newChecked ->
                if (!newChecked) {
                    onCheckedChange(false)
                    return@Switch
                }
                requestPermission()
            },
        )
        if (permissionMissing) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_perm_needed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { requestPermission() },
            )
        }
    }
}

@Composable
internal fun TermuxStatusRowSubtitle(enabled: Boolean) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val staticState = remember(resumeTick) { TermuxIntegration.state(ctx) }

    var verifying by remember { mutableStateOf(false) }
    var lastVerifyError by remember { mutableStateOf<String?>(null) }

    val lastVerifiedOkAt = remember(resumeTick) { TermuxIntegration.lastVerifiedOkAtMs }
    val verifiedRecently =
        lastVerifiedOkAt > 0 &&
            (System.currentTimeMillis() - lastVerifiedOkAt) < 60L * 60 * 1000

    val (dotColor, label) =
        when {
            staticState == TermuxIntegration.State.NOT_INSTALLED -> {
                androidx.compose.ui.graphics
                    .Color(0xFFEF4444) to
                    stringResource(R.string.assistant_page_local_tools_termux_status_not_installed)
            }

            staticState == TermuxIntegration.State.NO_PERMISSION -> {
                androidx.compose.ui.graphics
                    .Color(0xFFF59E0B) to
                    stringResource(R.string.assistant_page_local_tools_termux_status_no_permission)
            }

            verifiedRecently -> {
                androidx.compose.ui.graphics
                    .Color(0xFF22C55E) to
                    stringResource(R.string.assistant_page_local_tools_termux_status_ok)
            }

            lastVerifyError != null -> {
                androidx.compose.ui.graphics
                    .Color(0xFFEF4444) to (lastVerifyError ?: "")
            }

            else -> {
                androidx.compose.ui.graphics
                    .Color(0xFFEAB308) to
                    stringResource(R.string.assistant_page_local_tools_termux_status_untested)
            }
        }

    val verifyHint = stringResource(R.string.assistant_page_local_tools_termux_verify)
    val verifyingHint = stringResource(R.string.assistant_page_local_tools_termux_verifying)
    val verifyOkMsg = stringResource(R.string.assistant_page_local_tools_termux_verify_ok)
    val verifyPropsMissingMsg = stringResource(R.string.assistant_page_local_tools_termux_verify_props_missing)
    val verifyNoPermissionMsg = stringResource(R.string.assistant_page_local_tools_termux_verify_no_permission)
    val verifyNotInstalledMsg = stringResource(R.string.assistant_page_local_tools_termux_status_not_installed)
    val verifyUnexpectedFmt = stringResource(R.string.assistant_page_local_tools_termux_verify_unexpected)

    val canVerify = !verifying && enabled && staticState == TermuxIntegration.State.READY

    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .let {
                    if (canVerify) {
                        it.clickable {
                            if (verifying) return@clickable
                            verifying = true
                            lastVerifyError = null
                            scope.launch {
                                val result = TermuxIntegration.verify(ctx)
                                verifying = false
                                when (result) {
                                    TermuxIntegration.VerifyResult.Ok -> {
                                        TermuxIntegration.markVerifiedOk()
                                        resumeTick++
                                        toaster.show(
                                            verifyOkMsg,
                                        )
                                    }

                                    TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> {
                                        TermuxIntegration.clearVerified()
                                        resumeTick++
                                        lastVerifyError = verifyPropsMissingMsg
                                        toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                                    }

                                    TermuxIntegration.VerifyResult.NoPermission -> {
                                        TermuxIntegration.clearVerified()
                                        resumeTick++
                                        lastVerifyError = verifyNoPermissionMsg
                                        toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                                    }

                                    TermuxIntegration.VerifyResult.NotInstalled -> {
                                        TermuxIntegration.clearVerified()
                                        resumeTick++
                                        lastVerifyError = verifyNotInstalledMsg
                                    }

                                    is TermuxIntegration.VerifyResult.UnexpectedOutput -> {
                                        TermuxIntegration.clearVerified()
                                        resumeTick++
                                        lastVerifyError =
                                            verifyUnexpectedFmt.format(result.stdout.take(60))
                                        toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                                    }

                                    is TermuxIntegration.VerifyResult.OtherError -> {
                                        TermuxIntegration.clearVerified()
                                        resumeTick++
                                        lastVerifyError = result.message
                                        toaster.show(result.message, type = ToastType.Error)
                                    }
                                }
                            }
                        }
                    } else {
                        it
                    }
                },
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(10.dp),
        ) {
            drawCircle(color = dotColor)
        }
        Text(
            text =
                if (verifying) {
                    verifyingHint
                } else if (canVerify && !verifiedRecently) {
                    "$label · $verifyHint"
                } else {
                    label
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// 58 个本地工具枚举项一对一映射图标，分支数与密封类子类数一致
@Suppress("CyclomaticComplexMethod")
internal fun iconForLocalToolOption(option: LocalToolOption): ImageVector =
    when (option) {
        LocalToolOption.JavascriptEngine -> HugeIcons.JavaScript
        LocalToolOption.TimeInfo -> HugeIcons.Clock01
        LocalToolOption.Clipboard -> HugeIcons.Clipboard
        LocalToolOption.Tts -> HugeIcons.VolumeHigh
        LocalToolOption.AskUser -> HugeIcons.MessageQuestion
        LocalToolOption.ScreenTime -> HugeIcons.SmartPhone01
        LocalToolOption.Diagnostics -> HugeIcons.Bug01
        LocalToolOption.ModelTesting -> HugeIcons.Activity01
        LocalToolOption.DeviceInfo -> HugeIcons.SmartPhone01
        LocalToolOption.SystemIntents -> HugeIcons.Calendar03
        LocalToolOption.Toast -> HugeIcons.Message01
        LocalToolOption.Notification -> HugeIcons.Notification02
        LocalToolOption.Share -> HugeIcons.Share01
        LocalToolOption.ChartDisplay -> HugeIcons.ChartLineData01
        LocalToolOption.Location -> HugeIcons.Location01
        LocalToolOption.Contacts -> HugeIcons.Contact
        LocalToolOption.CallLog -> HugeIcons.SmartPhone01
        LocalToolOption.SmsInbox -> HugeIcons.Message01
        LocalToolOption.CameraPhoto -> HugeIcons.Camera01
        LocalToolOption.MicRecorder -> HugeIcons.Mic01
        LocalToolOption.SpeechToText -> HugeIcons.Mic01
        LocalToolOption.Fingerprint -> HugeIcons.FingerPrint
        LocalToolOption.NotificationListener -> HugeIcons.Notification02
        LocalToolOption.Files -> HugeIcons.Folder01
        LocalToolOption.Download -> HugeIcons.Download04
        LocalToolOption.MediaPlayer -> HugeIcons.Play
        LocalToolOption.MediaScanner -> HugeIcons.Search01
        LocalToolOption.Ssh -> HugeIcons.Connect
        LocalToolOption.TelegramBot -> HugeIcons.Telegram
        LocalToolOption.Browser -> HugeIcons.GlobalSearch
        LocalToolOption.WebFetch -> HugeIcons.GlobalSearch
        LocalToolOption.CronJobs -> HugeIcons.Clock02
        LocalToolOption.Workflows -> HugeIcons.Activity01
        LocalToolOption.SubAgents -> HugeIcons.AiBrain01
        LocalToolOption.SkillImport -> HugeIcons.Sparkles
        LocalToolOption.JsSkills -> HugeIcons.Code
        LocalToolOption.McpControl -> HugeIcons.Connect
        LocalToolOption.ExternalAutomation -> HugeIcons.Rocket01
        LocalToolOption.Reliability -> HugeIcons.SecurityCheck
        LocalToolOption.CostGuards -> HugeIcons.CoinsDollar
        LocalToolOption.ScreenAutomation -> HugeIcons.CursorPointer01
        LocalToolOption.PhoneUse -> HugeIcons.SmartPhone01
        LocalToolOption.AppLauncher -> HugeIcons.Rocket01
        LocalToolOption.Termux -> HugeIcons.ComputerTerminal01
        LocalToolOption.KeyboardControl -> HugeIcons.Keyboard
        LocalToolOption.Torch -> HugeIcons.Flash
        LocalToolOption.Vibrate -> HugeIcons.SmartPhone01
        LocalToolOption.Brightness -> HugeIcons.Sun03
        LocalToolOption.Volume -> HugeIcons.VolumeHigh
        LocalToolOption.SmsSend -> HugeIcons.Message01
        LocalToolOption.Wallpaper -> HugeIcons.Image02
        LocalToolOption.Keystore -> HugeIcons.Key01
        LocalToolOption.Shizuku -> HugeIcons.Bash
        LocalToolOption.VaultTools -> HugeIcons.Database01
        LocalToolOption.VaultExportEnv -> HugeIcons.Key01
        LocalToolOption.Nfc -> HugeIcons.Wifi01
        LocalToolOption.ExternalStorage -> HugeIcons.Folder01
        LocalToolOption.Archive -> HugeIcons.Archive02
        LocalToolOption.AppBackup -> HugeIcons.File01
    }

@Composable
internal fun ToolOptionGroupIcon(
    option: LocalToolOption,
    checked: Boolean = false,
) {
    CardGroupIcon(
        icon = iconForLocalToolOption(option),
        containerColor =
            if (checked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (checked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

internal fun CardGroupScope.toolSwitchItem(
    titleRes: Int,
    descRes: Int,
    checked: Boolean,
    option: LocalToolOption? = null,
    icon: ImageVector? = null,
    permissions: List<String> = emptyList(),
    usePermissionedSwitch: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val resolvedIcon = icon ?: option?.let(::iconForLocalToolOption) ?: HugeIcons.Tools
    item(
        leadingContent = {
            CardGroupIcon(
                icon = resolvedIcon,
                containerColor =
                    if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                contentColor =
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(descRes)) },
        trailingContent = {
            if (usePermissionedSwitch) {
                PermissionedSwitch(
                    checked = checked,
                    requiredRuntimePerms = permissions,
                    onCheckedChange = onCheckedChange,
                )
            } else {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
    )
}
