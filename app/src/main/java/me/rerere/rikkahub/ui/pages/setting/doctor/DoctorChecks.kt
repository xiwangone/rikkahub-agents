package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import java.io.File
import java.net.InetAddress

/**
 * Each row that depends on a system capability (a permission, an OS-level service binding,
 * Termux being installed) is "tool-aware": if no enabled tool needs the capability, the
 * row drops to INFO with a "not required" subtitle so the screen doesn't drown the user
 * in WARN noise about features they don't use.
 *
 * The map below records which [LocalToolOption] groups depend on which capability. The
 * answer comes from the tool registration code in `LocalTools.kt` — when a new tool is
 * added that needs a capability, also add its option here.
 */
private object Capability {
    val Notifications: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Notification, // post_notification tool
            LocalToolOption.TelegramBot, // FGS notification
            LocalToolOption.CronJobs, // CronJobWorker FGS notification
            LocalToolOption.Workflows, // WorkflowTimeCronWorker FGS notification
        )
    val FineLocation: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Location, // get_location, geocode tools
            LocalToolOption.WifiInfo, // SSID/BSSID on Android 10+
            LocalToolOption.Workflows, // geofence_enter / geofence_exit triggers
        )
    val NotificationListener: Set<LocalToolOption> =
        setOf(
            LocalToolOption.NotificationListener,
            LocalToolOption.Workflows, // notification_received trigger
        )
    val Accessibility: Set<LocalToolOption> =
        setOf(
            LocalToolOption.ScreenAutomation, // take_screenshot, swipe, click_at, scroll, gesture
        )
    val Termux: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Termux,
            LocalToolOption.SpeechToText, // transcribe_audio_file uses Termux + whisper.cpp
            LocalToolOption.Ssh, // ssh_exec calls into termux ssh
        )
    val BatteryWhitelist: Set<LocalToolOption> =
        setOf(
            LocalToolOption.TelegramBot, // long-poll loop
            LocalToolOption.CronJobs, // worker fires
            LocalToolOption.Workflows, // trigger receivers + cron worker
        )
    val AllFiles: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Files, // file_read / file_write to arbitrary paths
        )
    val Browser: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Browser, // 17 browser tools (in-app WebView)
        )

    // Phase 25 — Phase 3 second cut.
    val SendSms: Set<LocalToolOption> =
        setOf(
            LocalToolOption.SmsSend,
        )
    val Nfc: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Nfc,
        )

    // Permissions that previously had no Doctor check at all. Each is gated on the tool that
    // actually needs it, so a denied perm only WARNs when its feature is enabled (opt-in) and
    // stays INFO otherwise. Closes the "诊断报告显示一切正常，但悬浮窗等权限被拒绝"
    // gap.
    val Overlay: Set<LocalToolOption> =
        setOf(
            LocalToolOption.ScreenAutomation, // "agent is working" overlay during automation
        )
    val WriteSettings: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Brightness, // set_brightness writes Settings.System
        )
    val BluetoothConnect: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Workflows, // workflow Bluetooth triggers read paired-device state
        )
    val NearbyWifi: Set<LocalToolOption> =
        setOf(
            LocalToolOption.WifiInfo, // WiFi scan/info on Android 13+
        )
    val BackgroundLocation: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Workflows, // geofence triggers fire while the app is closed
        )
}

/** Friendly name for the row's "needed by:" subtitle. */
private fun LocalToolOption.shortName(): String =
    when (this) {
        LocalToolOption.Location -> "定位"
        LocalToolOption.WifiInfo -> "WiFi 信息"
        LocalToolOption.NotificationListener -> "通知监听"
        LocalToolOption.ScreenAutomation -> "屏幕自动化"
        LocalToolOption.Termux -> "Termux"
        LocalToolOption.SpeechToText -> "语音转文字"
        LocalToolOption.Ssh -> "SSH"
        LocalToolOption.TelegramBot -> "Telegram 机器人"
        LocalToolOption.CronJobs -> "定时任务"
        LocalToolOption.Workflows -> "工作流"
        LocalToolOption.Notification -> "通知"
        LocalToolOption.Files -> "文件"
        LocalToolOption.Browser -> "浏览器"
        LocalToolOption.SmsSend -> "发送短信"
        LocalToolOption.Wallpaper -> "壁纸"
        LocalToolOption.Keystore -> "密钥库"
        LocalToolOption.Nfc -> "NFC"
        LocalToolOption.ExternalStorage -> "外部存储"
        LocalToolOption.Archive -> "压缩文件 (zip)"
        else -> this::class.simpleName ?: "?"
    }

/**
 * Run every diagnostic check. Returns the flat list — the Doctor screen groups by
 * [DoctorCheck.category].
 *
 * Most checks are cheap (Settings.Secure reads, package manager queries, in-memory state)
 * but a few do I/O (DB integrity PRAGMA, DNS resolve). Run on Dispatchers.IO at the call
 * site; the function itself is suspending so individual probes can withTimeoutOrNull.
 *
 * Adding a new check: append to the appropriate `runXxxChecks` block. Each helper function
 * returns either a single check or a list. Keep checks short — one concern per row.
 */
class DoctorChecks(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val telegramPrefs: TelegramBotPreferences,
    private val workflowRepository: WorkflowRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    private val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    private val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
) {
    suspend fun runAll(): List<DoctorCheck> =
        withContext(Dispatchers.IO) {
            // Aggregate enabled tools across every assistant. A tool is "in use" if at least
            // one assistant has its LocalToolOption switched on. The Doctor uses this to
            // decide whether a missing capability is actually a problem worth flagging.
            val enabled: Set<LocalToolOption> =
                runCatching {
                    settingsStore.settingsFlow
                        .first()
                        .assistants
                        .flatMap { it.localTools }
                        .toSet()
                }.getOrDefault(emptySet())

            buildList {
                addAll(permissionChecks(enabled))
                addAll(serviceChecks(enabled))
                addAll(assistantChecks())
                addAll(databaseChecks(enabled))
                addAll(networkChecks())
                addAll(termuxChecks(enabled))
                addAll(browserChecks(enabled))
                addAll(maintenanceChecks())
                addAll(diagnosticsChecks(enabled))
            }
        }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    private fun requirersOf(
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
    ): List<LocalToolOption> = cap.filter { it in enabled }

    // ----- Permissions ----------------------------------------------------------------

    private fun permissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            add(
                capabilityRow(
                    id = "perm.notifications",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_01,
                    cap = Capability.Notifications,
                    enabled = enabled,
                    granted =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            PermissionHelper.hasRuntime(context, listOf(Manifest.permission.POST_NOTIFICATIONS)),
                    grantedDetail = "已授予。",
                    missingDetail = "前台服务通知、工具审批和工作流告警需要此权限。",
                    fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                ),
            )
            add(
                capabilityRow(
                    id = "perm.location",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_05,
                    cap = Capability.FineLocation,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
                    grantedDetail = "已授予。",
                    missingDetail = "Android 10+ 地理围栏触发和读取 WiFi SSID 需要此权限。",
                    fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                ),
            )
            add(
                capabilityRow(
                    id = "perm.battery_opt",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_07,
                    cap = Capability.BatteryWhitelist,
                    enabled = enabled,
                    granted = PermissionHelper.ignoresBatteryOptimizations(context),
                    grantedDetail = "应用已加入白名单 — 后台服务可稳定运行。",
                    missingDetail = "Doze 省电模式可能会终止 Telegram 机器人、定时任务和工作流。",
                    fix =
                        FixAction.OpenIntent(
                            labelRes = R.string.doctor_perm_10,
                            intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context),
                        ),
                ),
            )
            add(
                capabilityRow(
                    id = "perm.notification_listener",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_11,
                    cap = Capability.NotificationListener,
                    enabled = enabled,
                    granted = PermissionHelper.hasNotificationListener(context),
                    grantedDetail = "已授予 — 监听器可读取通知。",
                    missingDetail = "未授予。通知触发器和通知工具将无法工作。",
                    fix =
                        FixAction.OpenIntent(
                            labelRes = R.string.doctor_perm_14,
                            intent = PermissionHelper.notificationListenerSettingsIntent(),
                        ),
                ),
            )
            add(
                capabilityRow(
                    id = "perm.accessibility",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_15,
                    cap = Capability.Accessibility,
                    enabled = enabled,
                    granted = PermissionHelper.hasAccessibilityService(context),
                    grantedDetail = "已在系统设置中启用。",
                    missingDetail = "未启用。截图、滑动、滚动、点击和手势工具将无法工作。",
                    fix =
                        FixAction.OpenIntent(
                            labelRes = R.string.doctor_perm_14,
                            intent = PermissionHelper.accessibilitySettingsIntent(),
                        ),
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    capabilityRow(
                        id = "perm.all_files",
                        category = DoctorCategory.Permissions,
                        labelRes = R.string.doctor_perm_18,
                        cap = Capability.AllFiles,
                        enabled = enabled,
                        granted = PermissionHelper.hasAllFilesAccess(context),
                        grantedDetail = "已授予 — 文件读写工具可访问任意路径。",
                        missingDetail = "未授予。文件工具仅限于分区存储。",
                        fix =
                            FixAction.OpenIntent(
                                labelRes = R.string.doctor_perm_14,
                                intent = PermissionHelper.allFilesAccessIntent(context),
                            ),
                    ),
                )
            }
            // Phase 25 — SEND_SMS runtime permission row for the send_sms tool.
            add(
                capabilityRow(
                    id = "perm.send_sms",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_21,
                    cap = Capability.SendSms,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS)),
                    grantedDetail = "已授予。",
                    missingDetail = "send_sms tool needs this to send messages.",
                    fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                ),
            )
            // Previously-unchecked permissions, now covered. Each is tool-aware: it only WARNs when
            // the feature that needs it is enabled, so the opt-in philosophy holds (a denied perm for
            // a disabled tool stays INFO). This is what fixes the "诊断报告显示一切正常，但悬浮窗等权限未授予" report.
            add(
                capabilityRow(
                    id = "perm.overlay",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_23,
                    cap = Capability.Overlay,
                    enabled = enabled,
                    granted = android.provider.Settings.canDrawOverlays(context),
                    grantedDetail = "已授予。",
                    missingDetail = "屏幕自动化期间无法显示「智能体工作中」悬浮提示。",
                    fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                ),
            )
            add(
                capabilityRow(
                    id = "perm.write_settings",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_perm_25,
                    cap = Capability.WriteSettings,
                    enabled = enabled,
                    granted = PermissionHelper.hasWriteSettings(context),
                    grantedDetail = "已授予。",
                    missingDetail = "set_brightness can't change screen brightness without it.",
                    fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    capabilityRow(
                        id = "perm.bluetooth_connect",
                        category = DoctorCategory.Permissions,
                        labelRes = R.string.doctor_perm_26,
                        cap = Capability.BluetoothConnect,
                        enabled = enabled,
                        granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.BLUETOOTH_CONNECT)),
                        grantedDetail = "已授予。",
                        missingDetail = "工作流蓝牙触发器无法读取已配对设备状态。",
                        fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    capabilityRow(
                        id = "perm.nearby_wifi",
                        category = DoctorCategory.Permissions,
                        labelRes = R.string.doctor_perm_28,
                        cap = Capability.NearbyWifi,
                        enabled = enabled,
                        granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.NEARBY_WIFI_DEVICES)),
                        grantedDetail = "已授予。",
                        missingDetail = "Android 13+ 上缺少此权限可能导致 WiFi 扫描/信息受限。",
                        fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(
                    capabilityRow(
                        id = "perm.background_location",
                        category = DoctorCategory.Permissions,
                        labelRes = R.string.doctor_perm_30,
                        cap = Capability.BackgroundLocation,
                        enabled = enabled,
                        granted =
                            PermissionHelper.hasRuntime(
                                context,
                                listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            ),
                        grantedDetail = "已授予。",
                        missingDetail = "应用关闭时地理围栏工作流触发器不会触发。",
                        fix = FixAction.OpenAppRoute(R.string.doctor_perm_04, AppRouteKey.SettingPermissions),
                    ),
                )
            }
            // Phase 25 — NFC combined hardware + system-toggle row. Tri-state: no hardware
            // (INFO, no fix), hardware present but disabled (WARN, open NFC settings), on (OK).
            run {
                val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
                val nfcNeeders = requirersOf(Capability.Nfc, enabled)
                when {
                    adapter == null -> {
                        add(
                            DoctorCheck(
                                id = "perm.nfc_enabled",
                                category = DoctorCategory.Permissions,
                                labelRes = R.string.doctor_common_18,
                                detail = "此设备无 NFC 硬件。",
                                severity = Severity.INFO,
                            ),
                        )
                    }

                    !adapter.isEnabled -> {
                        add(
                            DoctorCheck(
                                id = "perm.nfc_enabled",
                                category = DoctorCategory.Permissions,
                                labelRes = R.string.doctor_common_18,
                                detail =
                                    if (nfcNeeders.isEmpty()) {
                                        "NFC 已在系统设置中关闭。当前未启用任何需要它的工具。"
                                    } else {
                                        "NFC 已在系统设置中关闭。需求方: " +
                                            nfcNeeders.joinToString(", ") { it.shortName() } + "."
                                    },
                                severity = if (nfcNeeders.isEmpty()) Severity.INFO else Severity.WARN,
                                fix =
                                    if (nfcNeeders.isEmpty()) {
                                        null
                                    } else {
                                        FixAction.OpenIntent(
                                            labelRes = R.string.doctor_perm_35,
                                            intent =
                                                android.content
                                                    .Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    },
                            ),
                        )
                    }

                    else -> {
                        add(
                            DoctorCheck(
                                id = "perm.nfc_enabled",
                                category = DoctorCategory.Permissions,
                                labelRes = R.string.doctor_common_18,
                                detail = "NFC 硬件已启用。",
                                severity = Severity.OK,
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Build a capability-aware Doctor row.
     *   granted = true                                  -> Severity.OK
     *   granted = false AND no enabled tool needs cap   -> Severity.INFO ("not required")
     *   granted = false AND some enabled tool needs cap -> Severity.WARN ("needed by: …")
     *
     * The Fix button is offered only when granted=false AND at least one tool needs the
     * capability — we don't push the user to grant a permission they don't currently use.
     */
    private fun capabilityRow(
        id: String,
        category: DoctorCategory,
        @StringRes labelRes: Int,
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        fix: FixAction,
    ): DoctorCheck {
        val needers = requirersOf(cap, enabled)
        val severity =
            when {
                granted -> Severity.OK
                needers.isEmpty() -> Severity.INFO
                else -> Severity.WARN
            }
        val detail =
            when {
                granted -> grantedDetail
                needers.isEmpty() -> "当前未启用任何需要此功能的工具。"
                else -> "$missingDetail Needed by: ${needers.joinToString(", ") { it.shortName() }}."
            }
        return DoctorCheck(
            id = id,
            category = category,
            labelRes = labelRes,
            detail = detail,
            severity = severity,
            fix = if (!granted && needers.isNotEmpty()) fix else null,
        )
    }

    // ----- Background services ---------------------------------------------------------

    private suspend fun serviceChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            val tg = telegramPrefs.current()
            // Telegram bot: token, enabled flag, FGS state should agree.
            if (tg.enabled) {
                add(
                    DoctorCheck(
                        id = "service.telegram_token",
                        category = DoctorCategory.Services,
                        labelRes = R.string.doctor_svc_01,
                        // Don't render any portion of the token — Telegram bot tokens are
                        // formatted "<bot_id>:<secret>" and even the first 6 chars reveal the
                        // bot id, which an attacker could use to enumerate bot endpoints.
                        detail =
                            if (tg.token.isNotBlank()) {
                                "已配置令牌（${tg.token.length} 字符，已隐藏）。"
                            } else {
                                "Telegram 机器人已启用但未设置令牌 — 服务将在启动时失败。"
                            },
                        severity = if (tg.token.isNotBlank()) Severity.OK else Severity.FAIL,
                        fix =
                            if (tg.token.isBlank()) {
                                FixAction.OpenAppRoute(R.string.doctor_svc_04, AppRouteKey.SettingTelegram)
                            } else {
                                null
                            },
                    ),
                )
                add(
                    DoctorCheck(
                        id = "service.telegram_running",
                        category = DoctorCategory.Services,
                        labelRes = R.string.doctor_svc_05,
                        detail =
                            if (TelegramBotService.isRunning) {
                                "服务正在运行。"
                            } else {
                                "服务已停止。Telegram 消息将无法送达助手。看门狗将在下次 30 分钟健康检查时重试。"
                            },
                        severity =
                            when {
                                TelegramBotService.isRunning -> Severity.OK

                                tg.token.isBlank() -> Severity.INFO

                                // token issue covers this
                                else -> Severity.FAIL
                            },
                    ),
                )
            } else {
                add(
                    DoctorCheck(
                        id = "service.telegram_off",
                        category = DoctorCategory.Services,
                        labelRes = R.string.doctor_common_07,
                        detail = "已禁用 — 如果不使用 Telegram 则无影响。",
                        severity = Severity.INFO,
                    ),
                )
            }
            // AccessibilityService binding — only flagged if a tool that needs it is enabled.
            val accNeeders = requirersOf(Capability.Accessibility, enabled)
            if (accNeeders.isNotEmpty()) {
                add(
                    DoctorCheck(
                        id = "service.accessibility_bound",
                        category = DoctorCategory.Services,
                        labelRes = R.string.doctor_svc_09,
                        detail =
                            if (AccessibilityServiceHandle.isRunning()) {
                                "服务实例活跃 — ${accNeeders.joinToString(", ") { it.shortName() }} can run."
                            } else if (PermissionHelper.hasAccessibilityService(context)) {
                                "已在设置中启用但未绑定（Android 已终止服务或尚未启动）。请关闭后重新打开。"
                            } else {
                                "未启用。需求方: ${accNeeders.joinToString(", ") { it.shortName() }}."
                            },
                        severity =
                            when {
                                AccessibilityServiceHandle.isRunning() -> Severity.OK
                                else -> Severity.WARN
                            },
                        fix =
                            if (!AccessibilityServiceHandle.isRunning()) {
                                FixAction.OpenIntent(
                                    labelRes = R.string.doctor_perm_14,
                                    intent = PermissionHelper.accessibilitySettingsIntent(),
                                )
                            } else {
                                null
                            },
                    ),
                )
            }
            // NotificationListener binding — same logic.
            val nlNeeders = requirersOf(Capability.NotificationListener, enabled)
            if (nlNeeders.isNotEmpty()) {
                add(
                    DoctorCheck(
                        id = "service.notification_listener_bound",
                        category = DoctorCategory.Services,
                        labelRes = R.string.doctor_svc_13,
                        detail =
                            if (NotificationListenerHandle.isBound()) {
                                "监听器已绑定 — ${nlNeeders.joinToString(", ") { it.shortName() }} can run."
                            } else if (PermissionHelper.hasNotificationListener(context)) {
                                "已授予但当前未绑定。请在设置中关闭后重新打开。"
                            } else {
                                "未授予。需求方: ${nlNeeders.joinToString(", ") { it.shortName() }}."
                            },
                        severity =
                            when {
                                NotificationListenerHandle.isBound() -> Severity.OK
                                else -> Severity.WARN
                            },
                        fix =
                            if (!NotificationListenerHandle.isBound()) {
                                FixAction.OpenIntent(
                                    labelRes = R.string.doctor_perm_14,
                                    intent = PermissionHelper.notificationListenerSettingsIntent(),
                                )
                            } else {
                                null
                            },
                    ),
                )
            }
        }

    // ----- Active assistant ------------------------------------------------------------

    /**
     * Informational section. All rows are [Severity.INFO] — these are status rows, not
     * problem rows. The single "default assistant" row surfaces the assistant that:
     *   - New Telegram conversations use (when no explicit assistantId is configured).
     *   - Cron jobs run as (their assistantId is locked at job creation time, but new jobs
     *     inherit from the Settings default).
     *   - New in-app chats default to.
     *
     * A WARN row fires when the global assistant list is empty — that's a sign the settings
     * store was corrupted or a migration wiped the assistants list.
     *
     * A separate row shows the Telegram-bot-configured override if one is set.
     */
    private suspend fun assistantChecks(): List<DoctorCheck> =
        buildList {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val assistants = settings.assistants
                val defaultAssistant = settings.getCurrentAssistant()

                // Row 1: default assistant name + id
                add(
                    DoctorCheck(
                        id = "assistant.default",
                        category = DoctorCategory.AssistantInfo,
                        labelRes = R.string.doctor_assistant_01,
                        detail =
                            if (assistants.isEmpty()) {
                                "未配置助手 — 应用将无法发起对话。"
                            } else {
                                "\"${defaultAssistant.name.ifBlank { "（未命名）" }}\" " +
                                    "(id: ${defaultAssistant.id.toString().take(8)}…). " +
                                    "用于新对话、定时任务和 Telegram（未设置覆盖时）。"
                            },
                        severity = if (assistants.isEmpty()) Severity.WARN else Severity.INFO,
                        fix = FixAction.OpenAppRoute(R.string.doctor_assistant_05, AppRouteKey.Assistant),
                    ),
                )

                // Row 2: total assistant count
                add(
                    DoctorCheck(
                        id = "assistant.count",
                        category = DoctorCategory.AssistantInfo,
                        labelRes = R.string.doctor_assistant_06,
                        detail = "${assistants.size} assistant(s) configured.",
                        severity = Severity.INFO,
                        fix = FixAction.OpenAppRoute(R.string.doctor_assistant_05, AppRouteKey.Assistant),
                    ),
                )

                // Row 3: Telegram-bot assistant override (if set)
                val tg = telegramPrefs.current()
                if (tg.enabled && tg.assistantId != null) {
                    val tgAssistant =
                        tg.assistantId.let { id ->
                            runCatching {
                                val uuid = kotlin.uuid.Uuid.parse(id)
                                assistants.find { it.id == uuid }
                            }.getOrNull()
                        }
                    add(
                        DoctorCheck(
                            id = "assistant.telegram_override",
                            category = DoctorCategory.AssistantInfo,
                            labelRes = R.string.doctor_assistant_07,
                            detail =
                                when {
                                    tgAssistant != null -> {
                                        "Telegram 入站消息路由至 「${tgAssistant.name.ifBlank { "（未命名）" }}\" " +
                                            "(id: ${tgAssistant.id.toString().take(
                                                8,
                                            )}…) — overriding the global default."
                                    }

                                    else -> {
                                        "Telegram 助手覆盖已设置 (id: ${tg.assistantId.take(8)}…) 但未找到匹配的 " +
                                            "assistant was found. Messages will fall back to the global default."
                                    }
                                },
                            severity = if (tgAssistant != null) Severity.INFO else Severity.WARN,
                            fix =
                                if (tgAssistant == null) {
                                    FixAction.OpenAppRoute(R.string.doctor_svc_04, AppRouteKey.SettingTelegram)
                                } else {
                                    null
                                },
                        ),
                    )
                }
            }
        }

    // ----- Database --------------------------------------------------------------------

    private suspend fun databaseChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            // Migration version
            val version = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
            add(
                DoctorCheck(
                    id = "db.version",
                    category = DoctorCategory.Database,
                    labelRes = R.string.doctor_db_01,
                    // Room refuses to open the DB unless the stored version matches the compiled schema;
                    // if we got here, version is the live schema version (migrations ran successfully).
                    detail =
                        if (version > 0) {
                            "v$version — migrations completed, schema is consistent."
                        } else {
                            "无法读取数据库版本 — Room 可能无法打开数据库。"
                        },
                    severity = if (version > 0) Severity.OK else Severity.WARN,
                ),
            )
            // Integrity check
            val integrity =
                runCatching {
                    withTimeoutOrNull(5_000L) {
                        database.openHelper.readableDatabase
                            .query("PRAGMA integrity_check;")
                            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }
                }.getOrNull()
            // Offer an AutoFix only when the corruption mentions message_fts — that's the one
            // we know how to repair (DROP + recreate + reindex from the messages table). For
            // any other integrity failure, surface the message and let the user decide; we
            // don't blanket-rebuild things we don't know are safe.
            val mentionsFts =
                integrity != null && integrity != "ok" && integrity.contains("message_fts", ignoreCase = true)
            add(
                DoctorCheck(
                    id = "db.integrity",
                    category = DoctorCategory.Database,
                    labelRes = R.string.doctor_db_03,
                    detail =
                        when (integrity) {
                            null -> "完整性检查超时或失败。"
                            "ok" -> "PRAGMA integrity_check 返回正常。"
                            else -> "完整性检查结果: $integrity"
                        },
                    severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                    fix =
                        if (mentionsFts) {
                            FixAction.AutoFix(
                                labelRes = R.string.doctor_db_07,
                                run = {
                                    runCatching {
                                        val n = conversationRepository.repairAndRebuildIndexes()
                                        AutoFixResult(ok = true, message = "已从 $n 个对话重建 message_fts 索引。")
                                    }.getOrElse {
                                        AutoFixResult(
                                            ok = false,
                                            message = "修复失败: ${it::class.simpleName}: ${it.message ?: "?"}",
                                        )
                                    }
                                },
                            )
                        } else {
                            null
                        },
                ),
            )
            // Workflows summary
            runCatching {
                val all = workflowRepository.observeAll().first()
                val enabled = all.count { it.entity.enabled }
                add(
                    DoctorCheck(
                        id = "db.workflows",
                        category = DoctorCategory.Database,
                        labelRes = R.string.doctor_common_09,
                        detail = "${all.size} total, $enabled enabled.",
                        severity = Severity.INFO,
                        fix =
                            if (all.isNotEmpty()) {
                                FixAction.OpenAppRoute(R.string.doctor_db_10, AppRouteKey.SettingWorkflows)
                            } else {
                                null
                            },
                    ),
                )
            }
            // Scheduled jobs summary
            runCatching {
                val all = scheduledJobRepository.getAll()
                val enabled = all.count { it.enabled }
                add(
                    DoctorCheck(
                        id = "db.scheduled_jobs",
                        category = DoctorCategory.Database,
                        labelRes = R.string.doctor_common_08,
                        detail = "${all.size} total, $enabled enabled.",
                        severity = Severity.INFO,
                        fix =
                            if (all.isNotEmpty()) {
                                FixAction.OpenAppRoute(R.string.doctor_db_11, AppRouteKey.SettingScheduledJobs)
                            } else {
                                null
                            },
                    ),
                )
            }
            // Stranded run rows (started but never finished — process killed mid-run)
            runCatching {
                val stranded = scheduledJobRunRepository.getStranded(System.currentTimeMillis() - 30 * 60_000L)
                add(
                    DoctorCheck(
                        id = "db.stranded_runs",
                        category = DoctorCategory.Database,
                        labelRes = R.string.doctor_db_12,
                        detail =
                            if (stranded.isEmpty()) {
                                "无。Worker 所有运行均正常完成。"
                            } else {
                                "${stranded.size} run(s) started > 30 min ago and never reported back. Likely process kill mid-run."
                            },
                        severity = if (stranded.isEmpty()) Severity.OK else Severity.WARN,
                    ),
                )
            }
            // Phase 25 — SAF granted-directories live count for the ExternalStorage tool.
            // Reconciles against the OS persisted-permission list so revoked grants drop off.
            val store = storageVolumeGrantStore
            if (store != null) {
                runCatching {
                    val externalStorageEnabled = enabled.contains(LocalToolOption.ExternalStorage)
                    val grants = store.reconcile()
                    add(
                        DoctorCheck(
                            id = "storage.granted_directories",
                            category = DoctorCategory.Database,
                            labelRes = R.string.doctor_db_14,
                            detail =
                                when {
                                    !externalStorageEnabled && grants.isEmpty() -> {
                                        "外部存储工具未启用。无需关注。"
                                    }

                                    grants.isEmpty() -> {
                                        "尚未授予任何目录。请调用 grant_directory_access 添加。"
                                    }

                                    else -> {
                                        "${grants.size} directory(ies) granted: " +
                                            grants.joinToString(", ") { it.displayName } + "."
                                    }
                                },
                            severity =
                                if (externalStorageEnabled && grants.isNotEmpty()) {
                                    Severity.OK
                                } else {
                                    Severity.INFO
                                },
                        ),
                    )
                }
            }
        }

    // ----- Network & providers ---------------------------------------------------------

    private suspend fun networkChecks(): List<DoctorCheck> =
        buildList {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val provs = settings.providers
                val configured =
                    provs.count { p ->
                        when (p) {
                            is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()

                            is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()

                            is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()

                            is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled

                            // on-device, no API key
                            // Local provider (LiteRT): usable when enabled AND at least one model has
                            // been loaded/downloaded. A disabled provider with no models is the factory
                            // default — don't count it.
                            is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()

                            is me.rerere.ai.provider.ProviderSetting.Codex -> p.enabled

                            // OAuth, no API key
                            is me.rerere.ai.provider.ProviderSetting.Grok -> p.enabled // OAuth, no API key

                            // Basic Auth / Bearer token, no API key
                            is me.rerere.ai.provider.ProviderSetting.Reasonix -> p.enabled
                        }
                    }
                add(
                    DoctorCheck(
                        id = "net.providers",
                        category = DoctorCategory.Network,
                        labelRes = R.string.doctor_net_01,
                        detail = "$configured provider(s) configured (API key set, AICore enabled, or local model loaded) out of ${provs.size} total.",
                        severity = if (configured > 0) Severity.OK else Severity.WARN,
                        fix = FixAction.OpenAppRoute(R.string.doctor_net_02, AppRouteKey.SettingProvider),
                    ),
                )
            }
            // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
            // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
            // "CreateSharedMemoryManager 未实现" on some Adreno drivers), the
            // model loads on CPU and the user has no UI indication. LiteRtProvider now
            // persists the actually-chosen accelerator after every load; surface that here
            // so the user can confirm GPU is engaged.
            runCatching {
                val prefs = localRuntimePreferences
                if (prefs != null) {
                    val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                    val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                    val detail =
                        when {
                            accel == null -> {
                                "尚未探测。加速器将在首次模型加载时决定。"
                            }

                            forceCpu && accel == "CPU" -> {
                                "CPU（设置 -> 本地 LiteRT 中「尝试 GPU 加速」已关闭）" +
                                    "开启以在下次加载时重试设备 GPU。"
                            }

                            accel == "CPU" -> {
                                "CPU（备用：此设备 GPU delegate 初始化失败， " +
                                    "likely an MLDrift issue. Tap 'Re-detect' in Settings → Local LiteRT " +
                                    "to retry with a fresh probe.)"
                            }

                            accel == "GPU" -> {
                                "GPU（OpenCL 或 OpenGL，由 LiteRT 内部探测选择）。"
                            }

                            accel == "QNN" || accel == "NPU" -> {
                                "NPU（Qualcomm QNN delegate）。"
                            }

                            accel == "NNAPI" -> {
                                "NNAPI。"
                            }

                            else -> {
                                "后端标签: $accel"
                            }
                        }
                    val severity =
                        when {
                            accel == null -> Severity.INFO

                            accel == "CPU" && !forceCpu -> Severity.WARN

                            // unexpected fallback
                            else -> Severity.OK
                        }
                    add(
                        DoctorCheck(
                            id = "net.litert_accel",
                            category = DoctorCategory.Network,
                            labelRes = R.string.doctor_net_10,
                            detail = detail,
                            severity = severity,
                            fix =
                                FixAction.OpenAppRoute(
                                    R.string.doctor_net_11,
                                    AppRouteKey.SettingProvider,
                                ),
                        ),
                    )
                    // Performance telemetry — surface the last-known prefill/decode tok/s for
                    // each model so the user (and the support team triaging a slow report)
                    // can see at a glance whether the runtime is hitting expected rates. We
                    // INFO when present; WARN never (the model could legitimately be slow on a
                    // weak device — the user knows their hardware better than we do).
                    val perfMap = prefs.perfTelemetryFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                    if (perfMap.isNotEmpty()) {
                        val rows = perfMap.values.sortedByDescending { it.sampledAtMs }
                        val detail =
                            rows.joinToString("\n") { s ->
                                val spec = if (s.specDecodingEngaged) ", MTP on" else ""
                                "${s.modelId}: prefill ${"%.1f".format(s.prefillTps)} tok/s, " +
                                    "decode ${"%.1f".format(s.decodeTps)} tok/s$spec"
                            }
                        add(
                            DoctorCheck(
                                id = "net.litert_perf",
                                category = DoctorCategory.Network,
                                labelRes = R.string.doctor_net_12,
                                detail =
                                    "各模型最近已知速率（基于字符估算， " +
                                        "~10% accurate for English text):\n$detail",
                                severity = Severity.INFO,
                                fix =
                                    FixAction.OpenAppRoute(
                                        R.string.doctor_net_11,
                                        AppRouteKey.SettingProvider,
                                    ),
                            ),
                        )
                    }
                    // Vision-encoder availability — surface any models the runtime had to drop
                    // to text-only on this device's GPU. The provider's vision-CPU fallback
                    // means a multimodal model still works for chat, but the user has lost
                    // image input on this chip. Most common cause: Adreno 7xx + restrictive
                    // OEM linker namespace (One UI / OriginOS) hitting upstream LiteRT-LM
                    // issue #2292 (gpu_backend_opengl.cc:CreateSharedMemoryManager UNIMPLEMENTED).
                    val visionUnavailable =
                        prefs
                            .visionUnavailableFlow(me.rerere.locallm.LocalRuntime.LiteRT)
                            .first()
                    if (visionUnavailable.isNotEmpty()) {
                        add(
                            DoctorCheck(
                                id = "net.litert_vision",
                                category = DoctorCategory.Network,
                                labelRes = R.string.doctor_net_14,
                                detail =
                                    "此设备不支持以下模型的视觉编码器: " +
                                        visionUnavailable.joinToString(", ") +
                                        ". These multimodal models run in text-only mode — chat works, " +
                                        "image inputs don't. Often fixed by a future LiteRT-LM SDK update " +
                                        "(the OpenGL fallback path's CreateSharedMemoryManager is " +
                                        "currently UNIMPLEMENTED upstream). Tap 'Re-try vision' next to " +
                                        "the model in Settings -> Local LiteRT after a GPU driver update " +
                                        "to clear the flag.",
                                severity = Severity.WARN,
                                fix =
                                    FixAction.OpenAppRoute(
                                        R.string.doctor_net_11,
                                        AppRouteKey.SettingProvider,
                                    ),
                            ),
                        )
                    }
                }
            }
            // DNS sanity — confirms the OkHttp clients aren't stuck on a stale resolver.
            val dnsOk =
                withTimeoutOrNull(2_500L) {
                    runCatching { InetAddress.getByName("dns.google") != null }.getOrDefault(false)
                } == true
            add(
                DoctorCheck(
                    id = "net.dns",
                    category = DoctorCategory.Network,
                    labelRes = R.string.doctor_net_16,
                    detail =
                        if (dnsOk) {
                            "dns.google resolved within 2.5 s."
                        } else {
                            "DNS 解析失败或超时。网络变化时 NetworkChangeMonitor 会清空 OkHttp 连接池 — 如果持续红色，请检查网络连接。"
                        },
                    severity = if (dnsOk) Severity.OK else Severity.WARN,
                ),
            )
        }

    // ----- Termux ----------------------------------------------------------------------

    private fun termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            val needers = requirersOf(Capability.Termux, enabled)
            // Skip the entire category when no Termux-using tool is enabled — keeps the
            // Doctor screen focused on what the user actually configured.
            if (needers.isEmpty()) return@buildList

            val pm = context.packageManager
            val termuxInstalled =
                runCatching {
                    pm.getPackageInfo("com.termux", 0)
                    true
                }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.installed",
                    category = DoctorCategory.Termux,
                    labelRes = R.string.doctor_termux_01,
                    detail =
                        if (termuxInstalled) {
                            "com.termux is installed on this device."
                        } else {
                            "Termux 未安装。需求方: ${needers.joinToString(", ") { it.shortName() }}."
                        },
                    severity = if (termuxInstalled) Severity.OK else Severity.WARN,
                ),
            )
            if (termuxInstalled) {
                val runCommandPerm =
                    runCatching {
                        val perm = "com.termux.permission.RUN_COMMAND"
                        context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }.getOrDefault(false)
                add(
                    DoctorCheck(
                        id = "termux.run_command",
                        category = DoctorCategory.Termux,
                        labelRes = R.string.doctor_termux_03,
                        detail =
                            if (runCommandPerm) {
                                "已授予 — RikkaHub Agents 可向 Termux 派发 Shell 命令。"
                            } else {
                                "未授予。请在本地工具中重新切换 Termux 开关以查看授权后对话框。"
                            },
                        severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                    ),
                )
            }
        }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("权限 / 服务").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
    private fun browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            val needers = requirersOf(Capability.Browser, enabled)
            val browserNeeded = needers.isNotEmpty()

            // Row 1: profile dir writable (with AutoFix to mkdirs).
            val profileDir = File(context.filesDir, "browser-profile")
            val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
            val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
            val ok = exists && writable
            add(
                DoctorCheck(
                    id = "browser.profile_dir_writable",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_browser_02,
                    detail =
                        when {
                            ok && browserNeeded -> "${profileDir.absolutePath} exists and is writable — cookies persist."
                            ok -> "${profileDir.absolutePath} exists. Not required by any enabled tool."
                            !exists && browserNeeded -> "目录不存在。Cookie 和 localStorage 将不会持久化。需求方: 浏览器。"
                            !exists -> "目录不存在。当前未启用任何需要此目录的工具。"
                            !writable && browserNeeded -> "目录存在但不可写。需求方: 浏览器。"
                            else -> "目录存在但不可写。"
                        },
                    severity =
                        when {
                            ok -> Severity.OK
                            browserNeeded -> Severity.WARN
                            else -> Severity.INFO
                        },
                    fix =
                        if (!ok && browserNeeded) {
                            FixAction.AutoFix(
                                labelRes = R.string.doctor_browser_07,
                                run = {
                                    val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
                                    val nowOk = profileDir.exists() && profileDir.canWrite()
                                    AutoFixResult(
                                        ok = nowOk,
                                        message =
                                            if (nowOk) {
                                                "已创建 ${profileDir.absolutePath}。"
                                            } else if (created) {
                                                "目录已创建但仍不可写 — 请检查存储权限。"
                                            } else {
                                                "mkdirs() returned false; underlying storage may be read-only."
                                            },
                                    )
                                },
                            )
                        } else {
                            null
                        },
                ),
            )

            // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
            // wasn't injected — the row is purely informational and the test harness paths
            // that don't construct prefs shouldn't fail.
            val prefs = browserPreferences
            if (prefs != null) {
                val snapshot =
                    runCatching { prefs.snapshotBlocking() }.getOrDefault(
                        BrowserToolDefaults.DEFAULT_ENABLED,
                    )
                val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
                val detail =
                    if (onWriteTools.isEmpty()) {
                        "已启用的副作用浏览器工具数: 0。未开启任何写入工具。"
                    } else {
                        "已启用的副作用浏览器工具数: ${onWriteTools.size} (${onWriteTools.joinToString(
                            ", ",
                        ) { it.removePrefix("browser_") }})."
                    }
                add(
                    DoctorCheck(
                        id = "browser.write_tools_status",
                        category = DoctorCategory.Permissions,
                        labelRes = R.string.doctor_browser_11,
                        detail = detail,
                        severity = Severity.INFO,
                    ),
                )
            }
        }

    // ----- Maintenance -----------------------------------------------------------------

    private fun maintenanceChecks(): List<DoctorCheck> =
        buildList {
            // Cache size on disk
            val cacheBytes = directorySize(context.cacheDir)
            add(
                DoctorCheck(
                    id = "maint.cache_size",
                    category = DoctorCategory.Maintenance,
                    labelRes = R.string.doctor_maint_01,
                    detail =
                        "缓存占用 ${humanBytes(cacheBytes)}。" +
                            if (cacheBytes > 200L * 1024 * 1024) "建议清理 — 已超过 200 MB。" else "正常范围内。",
                    severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                    fix =
                        FixAction.AutoFix(
                            labelRes = R.string.doctor_maint_05,
                            run = {
                                val freed = clearDirectoryContents(context.cacheDir)
                                AutoFixResult(ok = true, message = "已释放 ${humanBytes(freed)}。")
                            },
                        ),
                ),
            )
        }

    // ----- Diagnostics summary ---------------------------------------------------------

    private fun diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        listOf(
            DoctorCheck(
                id = "diag.app",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_diag_01,
                detail = "RikkaHub Agents ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — debug=${BuildConfig.DEBUG}",
                severity = Severity.INFO,
            ),
            DoctorCheck(
                id = "diag.android",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_common_19,
                detail = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) 设备 ${Build.MANUFACTURER} ${Build.MODEL}",
                severity = Severity.INFO,
            ),
            DoctorCheck(
                id = "diag.runtime",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_diag_03,
                detail =
                    run {
                        val rt = Runtime.getRuntime()
                        val freeMb = rt.freeMemory() / (1024 * 1024)
                        val totalMb = rt.totalMemory() / (1024 * 1024)
                        val maxMb = rt.maxMemory() / (1024 * 1024)
                        "堆内存: $freeMb MB 可用 / $totalMb MB ($maxMb MB 最大值)"
                    },
                severity = Severity.INFO,
            ),
            DoctorCheck(
                id = "diag.enabled_tools",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_diag_05,
                detail =
                    if (enabled.isEmpty()) {
                        "未启用任何本地工具 — 智能体功能将无法工作。"
                    } else {
                        "${enabled.size} tool group(s) enabled."
                    },
                severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
            ),
        )

    private fun directorySize(dir: File): Long =
        runCatching {
            if (!dir.exists()) return@runCatching 0L
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

    private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }
}
