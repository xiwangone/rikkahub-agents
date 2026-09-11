package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderSetting
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
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.subagent.SubAgentModelResolver
import me.rerere.rikkahub.subagent.SubAgentProfile
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
private fun LocalToolOption.shortName(context: Context): String =
    when (this) {
        LocalToolOption.Location -> context.getString(R.string.doctor_tool_location)
        LocalToolOption.WifiInfo -> context.getString(R.string.doctor_tool_wifi)
        LocalToolOption.NotificationListener -> context.getString(R.string.doctor_tool_nl)
        LocalToolOption.ScreenAutomation -> context.getString(R.string.doctor_tool_screen)
        LocalToolOption.Termux -> "Termux"
        LocalToolOption.SpeechToText -> context.getString(R.string.doctor_tool_stt)
        LocalToolOption.Ssh -> "SSH"
        LocalToolOption.TelegramBot -> context.getString(R.string.doctor_tool_telegram)
        LocalToolOption.CronJobs -> context.getString(R.string.doctor_tool_cron)
        LocalToolOption.Workflows -> context.getString(R.string.doctor_tool_workflow)
        LocalToolOption.Notification -> context.getString(R.string.doctor_tool_notification)
        LocalToolOption.Files -> context.getString(R.string.doctor_tool_files)
        LocalToolOption.Browser -> context.getString(R.string.doctor_tool_browser)
        LocalToolOption.SmsSend -> context.getString(R.string.doctor_tool_sms)
        LocalToolOption.Wallpaper -> context.getString(R.string.doctor_tool_wallpaper)
        LocalToolOption.Keystore -> context.getString(R.string.doctor_tool_keystore)
        LocalToolOption.Nfc -> "NFC"
        LocalToolOption.ExternalStorage -> context.getString(R.string.doctor_tool_ext_storage)
        LocalToolOption.Archive -> context.getString(R.string.doctor_tool_archive)
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
                    grantedDetail = context.getString(R.string.doctor_msg_granted),
                    missingDetail = context.getString(R.string.doctor_msg_notif_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_granted),
                    missingDetail = context.getString(R.string.doctor_msg_location_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_battery_ok),
                    missingDetail = context.getString(R.string.doctor_msg_battery_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_nl_ok),
                    missingDetail = context.getString(R.string.doctor_msg_nl_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_acc_ok),
                    missingDetail = context.getString(R.string.doctor_msg_acc_missing),
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
                        grantedDetail = context.getString(R.string.doctor_msg_files_ok),
                        missingDetail = context.getString(R.string.doctor_msg_files_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_granted),
                    missingDetail = context.getString(R.string.doctor_msg_sms_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_granted),
                    missingDetail = context.getString(R.string.doctor_msg_overlay_missing),
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
                    grantedDetail = context.getString(R.string.doctor_msg_granted),
                    missingDetail = context.getString(R.string.doctor_msg_write_settings_missing),
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
                        grantedDetail = context.getString(R.string.doctor_msg_granted),
                        missingDetail = context.getString(R.string.doctor_msg_bluetooth_missing),
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
                        grantedDetail = context.getString(R.string.doctor_msg_granted),
                        missingDetail = context.getString(R.string.doctor_msg_nearby_wifi_missing),
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
                        grantedDetail = context.getString(R.string.doctor_msg_granted),
                        missingDetail = context.getString(R.string.doctor_msg_bg_location_missing),
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
                                detail = context.getString(R.string.doctor_msg_nfc_no_hardware),
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
                                        context.getString(R.string.doctor_msg_nfc_off)
                                    } else {
                                        context.getString(R.string.doctor_msg_nfc_off_needed) +
                                            nfcNeeders.joinToString(", ") { it.shortName(context) } + "."
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
                                detail = context.getString(R.string.doctor_msg_nfc_on),
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
                needers.isEmpty() -> context.getString(R.string.doctor_msg_not_needed)
                else -> "$missingDetail Needed by: ${needers.joinToString(", ") { it.shortName(context) }}."
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
                                context.getString(R.string.doctor_msg_tg_token_set, tg.token.length)
                            } else {
                                context.getString(R.string.doctor_msg_tg_token_missing)
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
                                context.getString(R.string.doctor_msg_tg_running)
                            } else {
                                context.getString(R.string.doctor_msg_tg_stopped)
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
                        detail = context.getString(R.string.doctor_msg_tg_disabled),
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
                                context.getString(R.string.doctor_msg_acc_live, accNeeders.joinToString(", ") { it.shortName(context) })
                            } else if (PermissionHelper.hasAccessibilityService(context)) {
                                context.getString(R.string.doctor_msg_acc_enabled_unbound)
                            } else {
                                context.getString(R.string.doctor_msg_acc_needed, accNeeders.joinToString(", ") { it.shortName(context) })
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
                                context.getString(R.string.doctor_msg_nl_bound, nlNeeders.joinToString(", ") { it.shortName(context) })
                            } else if (PermissionHelper.hasNotificationListener(context)) {
                                context.getString(R.string.doctor_msg_nl_granted_unbound)
                            } else {
                                context.getString(R.string.doctor_msg_nl_needed, nlNeeders.joinToString(", ") { it.shortName(context) })
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
                                context.getString(R.string.doctor_msg_assistant_none)
                            } else {
                                context.getString(
                                        R.string.doctor_msg_assistant_named,
                                        defaultAssistant.name.ifBlank { context.getString(R.string.doctor_msg_unnamed) },
                                        defaultAssistant.id.toString().take(8),
                                    ) +
                                    context.getString(R.string.doctor_msg_assistant_default)
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
                        detail = context.getString(R.string.doctor_msg_assistant_count, assistants.size),
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
                                        context.getString(
                                            R.string.doctor_msg_tg_route,
                                            tgAssistant.name.ifBlank { context.getString(R.string.doctor_msg_unnamed) },
                                            tgAssistant.id.toString().take(8),
                                        )
                                    }

                                    else -> {
                                        context.getString(R.string.doctor_msg_tg_override_missing, tg.assistantId.take(8))
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
                            context.getString(R.string.doctor_msg_db_version_ok, version)
                        } else {
                            context.getString(R.string.doctor_msg_db_version_failed)
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
                            null -> context.getString(R.string.doctor_msg_db_timeout)
                            "ok" -> context.getString(R.string.doctor_msg_db_ok)
                            else -> context.getString(R.string.doctor_msg_db_result, integrity)
                        },
                    severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                    fix =
                        if (mentionsFts) {
                            FixAction.AutoFix(
                                labelRes = R.string.doctor_db_07,
                                run = {
                                    runCatching {
                                        val n = conversationRepository.repairAndRebuildIndexes()
                                        AutoFixResult(ok = true, message = context.getString(R.string.doctor_msg_fts_rebuilt, n))
                                    }.getOrElse {
                                        AutoFixResult(
                                            ok = false,
                                            message = context.getString(R.string.doctor_msg_fix_failed, it::class.simpleName, it.message ?: "?"),
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
                        detail = context.getString(R.string.doctor_msg_count_total_enabled, all.size, enabled),
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
                        detail = context.getString(R.string.doctor_msg_count_total_enabled, all.size, enabled),
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
                                context.getString(R.string.doctor_msg_stranded_none)
                            } else {
                                context.getString(R.string.doctor_msg_stranded_some, stranded.size)
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
                                        context.getString(R.string.doctor_msg_ext_storage_off)
                                    }

                                    grants.isEmpty() -> {
                                        context.getString(R.string.doctor_msg_ext_storage_no_grants)
                                    }

                                    else -> {
                                        context.getString(
                                            R.string.doctor_msg_dirs_granted,
                                            grants.size,
                                            grants.joinToString(", ") { it.displayName.trim() },
                                        )
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
                            is me.rerere.ai.provider.ProviderSetting.Backend -> p.enabled

                            is me.rerere.ai.provider.ProviderSetting.GeminiOAuth -> p.enabled

                            // on-device, no API key
                            is me.rerere.ai.provider.ProviderSetting.LlamaCppLocal -> p.enabled && p.models.isNotEmpty()
                        }
                    }
                add(
                    DoctorCheck(
                        id = "net.providers",
                        category = DoctorCategory.Network,
                        labelRes = R.string.doctor_net_01,
                        detail = context.getString(R.string.doctor_msg_providers, configured, provs.size),
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
                                context.getString(R.string.doctor_msg_litert_unprobed)
                            }

                            forceCpu && accel == "CPU" -> {
                                context.getString(R.string.doctor_msg_litert_cpu_disabled) +
                                    context.getString(R.string.doctor_msg_litert_enable_hint)
                            }

                            accel == "CPU" -> {
                                context.getString(R.string.doctor_msg_litert_cpu_mldrift)
                            }

                            accel == "GPU" -> {
                                context.getString(R.string.doctor_msg_litert_gpu)
                            }

                            accel == "QNN" || accel == "NPU" -> {
                                "NPU（Qualcomm QNN delegate）。"
                            }

                            accel == "NNAPI" -> {
                                "NNAPI。"
                            }

                            else -> {
                                context.getString(R.string.doctor_msg_litert_backend, accel)
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
                                    context.getString(R.string.doctor_msg_rate_detail, detail),
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
                                    context.getString(
                                        R.string.doctor_msg_vision_detail,
                                        visionUnavailable.joinToString(", "),
                                    ),
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
                            context.getString(R.string.doctor_msg_dns_ok)
                        } else {
                            context.getString(R.string.doctor_msg_dns_failed)
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
                            context.getString(R.string.doctor_msg_termux_installed)
                        } else {
                            context.getString(R.string.doctor_msg_termux_missing, needers.joinToString(", ") { it.shortName(context) })
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
                                context.getString(R.string.doctor_msg_termux_ok)
                            } else {
                                context.getString(R.string.doctor_msg_termux_denied)
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
                            ok && browserNeeded -> context.getString(R.string.doctor_msg_browser_ok, profileDir.absolutePath)
                            ok -> context.getString(R.string.doctor_msg_browser_unneeded, profileDir.absolutePath)
                            !exists && browserNeeded -> context.getString(R.string.doctor_msg_browser_no_dir)
                            !exists -> context.getString(R.string.doctor_msg_browser_no_dir_unneeded)
                            !writable && browserNeeded -> context.getString(R.string.doctor_msg_browser_readonly_needed)
                            else -> context.getString(R.string.doctor_msg_browser_readonly)
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
                                                context.getString(R.string.doctor_msg_browser_created, profileDir.absolutePath)
                                            } else if (created) {
                                                context.getString(R.string.doctor_msg_browser_created_readonly)
                                            } else {
                                                context.getString(R.string.doctor_msg_browser_mkdir_failed)
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
                        context.getString(R.string.doctor_msg_browser_write_none)
                    } else {
                        context.getString(
                            R.string.doctor_msg_browser_write_some,
                            onWriteTools.size,
                            onWriteTools.joinToString(", ") { it.removePrefix("browser_") },
                        )
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
                        context.getString(R.string.doctor_msg_cache_usage, humanBytes(cacheBytes)) +
                            if (cacheBytes > 200L * 1024 * 1024) context.getString(R.string.doctor_msg_cache_advice) else context.getString(R.string.doctor_msg_cache_normal),
                    severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                    fix =
                        FixAction.AutoFix(
                            labelRes = R.string.doctor_maint_05,
                            run = {
                                val freed = clearDirectoryContents(context.cacheDir)
                                AutoFixResult(ok = true, message = context.getString(R.string.doctor_msg_cache_freed, humanBytes(freed)))
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
                detail = context.getString(R.string.doctor_msg_android_device, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL),
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
                        context.getString(R.string.doctor_msg_heap, freeMb, totalMb, maxMb)
                    },
                severity = Severity.INFO,
            ),
            DoctorCheck(
                            id = "diag.enabled_tools",
                            category = DoctorCategory.Diagnostics,
                            labelRes = R.string.doctor_diag_05,
                            detail =
                                if (enabled.isEmpty()) {
                                    context.getString(R.string.doctor_msg_no_local_tools)
                                } else {
                                    context.getString(R.string.doctor_msg_tool_groups, enabled.size)
                                },
                            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
                        ),
                        // P0：AppLog 开关真相——默认关闭时 read_app_logs 永远返回空
                        DoctorCheck(
                            id = "diag.app_log_enabled",
                            category = DoctorCategory.Diagnostics,
                            labelRes = R.string.doctor_diag_02,
                            detail =
                                if (AppLog.isEnabled(context)) {
                                    context.getString(R.string.doctor_msg_app_log_on)
                                } else {
                                    context.getString(R.string.doctor_msg_app_log_off)
                                },
                            severity = if (AppLog.isEnabled(context)) Severity.OK else Severity.WARN,
                        ),
                        // P1：崩溃记录——ApplicationExitInfo 最近 N 天崩溃汇总
                        run {
                            val exitInfo = crashExitSummary()
                            val days = 7
                            DoctorCheck(
                                id = "diag.crash_history",
                                category = DoctorCategory.Diagnostics,
                                labelRes = R.string.doctor_diag_04,
                                detail =
                                    if (exitInfo == null) {
                                        context.getString(R.string.doctor_msg_crash_none, days)
                                    } else {
                                        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US)
                                        context.getString(
                                            R.string.doctor_msg_crash_some,
                                            exitInfo.count,
                                            days,
                                            fmt.format(java.util.Date(exitInfo.latestTs)),
                                        )
                                    },
                                severity = if (exitInfo == null) Severity.OK else Severity.WARN,
                            )
                        },
                        // P1：版本/进程新鲜度——装了新包但进程跑旧代码
                                    run {
                                        val installed = runCatching {
                                            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                                        }.getOrNull()
                                        val running = BuildConfig.VERSION_CODE.toLong()
                                        val fresh = installed != null && installed == running
                                        DoctorCheck(
                                            id = "diag.version_freshness",
                                            category = DoctorCategory.Diagnostics,
                                            labelRes = R.string.doctor_diag_06,
                                            detail =
                                                if (fresh) {
                                                    context.getString(R.string.doctor_msg_version_ok, running)
                                                } else {
                                                    context.getString(R.string.doctor_msg_version_stale, installed ?: -1, running)
                                                },
                                            severity = if (fresh) Severity.OK else Severity.WARN,
                                        )
                                    },
                                    // P2：技能注入真相——「开关看似开实则空」：扫描 skills 目录下 SKILL.md 存在且非空；
                                                //     auto_load 技能还校验 auto_load_path 文件存在（注入是否真正有效）。
                                                //     纯文件系统自检（不依赖 settingsStore，非 suspend 环境），只统计本地技能目录。
                                                run {
                                                    val skillsDir = context.filesDir.resolve(me.rerere.rikkahub.data.files.FileFolders.SKILLS)
                                                    val dirs =
                                                        skillsDir.listFiles()
                                                            ?.filter { it.isDirectory }
                                                            ?: emptyList()
                                                    val problems =
                                                        dirs.filter { dir ->
                                                            val skillFile = dir.resolve("SKILL.md")
                                                            if (!skillFile.exists() || skillFile.length() == 0L) {
                                                                true
                                                            } else {
                                                                val fm = runCatching {
                                                                    me.rerere.rikkahub.data.files.SkillFrontmatterParser.parse(skillFile.readText())
                                                                }.getOrDefault(emptyMap())
                                                                val autoLoad = fm["auto_load"]?.equals("true", ignoreCase = true) == true
                                                                val path = fm["auto_load_path"]
                                                                autoLoad && !path.isNullOrBlank() &&
                                                                    (!dir.resolve(path).exists() || dir.resolve(path).length() == 0L)
                                                            }
                                                        }.map { it.name }
                                                    DoctorCheck(
                                                        id = "diag.skill_injection",
                                                        category = DoctorCategory.Diagnostics,
                                                        labelRes = R.string.doctor_diag_07,
                                                        detail =
                                                            if (dirs.isEmpty()) {
                                                                context.getString(R.string.doctor_msg_skill_none_on_disk)
                                                            } else if (problems.isEmpty()) {
                                                                context.getString(R.string.doctor_msg_skill_all_present, dirs.size)
                                                            } else {
                                                                context.getString(R.string.doctor_msg_skill_missing, problems.take(5).joinToString(", "))
                                                            },
                                                        severity = if (problems.isEmpty()) Severity.OK else Severity.WARN,
                                                    )
                                                },
                                                // P2：脱敏回归自检——已知样例喂 maskText，断言被掩；只防回归，不证明覆盖完整
                                                run {
                                                    val samples =
                                                        listOf(
                                                            "sk-1234567890abcdef1234567890abcdef",
                                                            "xai-1234567890abcdef1234567890abcdef",
                                                            "Bearer abcdefghijklmnopqrstuvwxyz012345",
                                                            "AIzaSyAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                                            "api_key=9876543210abcdef9876543210abcdef",
                                                        )
                                                    val leaked =
                                                        samples.filter { sample ->
                                                            val masked = me.rerere.rikkahub.utils.LogRedactor.maskText(sample)
                                                            masked == sample
                                                        }
                                                    DoctorCheck(
                                                        id = "diag.redactor_selftest",
                                                        category = DoctorCategory.Diagnostics,
                                                        labelRes = R.string.doctor_diag_08,
                                                        detail =
                                                            if (leaked.isEmpty()) {
                                                                context.getString(R.string.doctor_msg_redactor_ok, samples.size)
                                                            } else {
                                                                context.getString(R.string.doctor_msg_redactor_leak, leaked.take(3).joinToString(", "))
                                                            },
                                                        severity = if (leaked.isEmpty()) Severity.OK else Severity.WARN,
                                                    )
                                                },
                                                // P3：日志健康——AppLog 近期 WARN/ERROR 占比与小样（过脱敏）
                                                run {
                                                    val logs = me.rerere.rikkahub.data.log.AppLog.getLogs()
                                                    val recent = logs.takeLast(100)
                                                    val problems = recent.filter { it.level == 'W' || it.level == 'E' }
                                                    DoctorCheck(
                                                        id = "diag.log_health",
                                                        category = DoctorCategory.Diagnostics,
                                                        labelRes = R.string.doctor_diag_09,
                                                        detail =
                                                            if (recent.isEmpty()) {
                                                                context.getString(R.string.doctor_msg_log_health_empty)
                                                            } else {
                                                                val sample =
                                                                    problems.takeLast(3).joinToString(" | ") { e ->
                                                                        "${e.level} ${e.tag}: ${me.rerere.rikkahub.utils.LogRedactor.maskText(e.message.take(80))}"
                                                                    }
                                                                context.getString(
                                                                    R.string.doctor_msg_log_health_summary,
                                                                    problems.size,
                                                                    recent.size,
                                                                    sample.ifEmpty { "-" },
                                                                )
                                                            },
                                                        severity =
                                                            when {
                                                                problems.isEmpty() -> Severity.OK
                                                                problems.size >= 20 -> Severity.WARN
                                                                else -> Severity.INFO
                                                            },
                                                    )
                                                },
                                            )

    // P1 检查项 `diag.crash_history` 的辅助：汇总最近 N 天非正常退出（崩溃）次数与最新时间。
    // ApplicationExitInfo 仅 API 30+；低版本返回 null（检查项显示"无崩溃"）。
    private fun crashExitSummary(): CrashExitSummary? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val exits =
            runCatching {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            }.getOrDefault(emptyList())
        val crashes =
            exits.filter { e ->
                (e.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                    e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    e.reason == android.app.ApplicationExitInfo.REASON_ANR) &&
                    e.timestamp >= cutoff
            }
        if (crashes.isEmpty()) return null
        return CrashExitSummary(count = crashes.size, latestTs = crashes.maxOf { it.timestamp })
    }

    private data class CrashExitSummary(val count: Int, val latestTs: Long)

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

/**
 * Pure decision logic backing the "net.llamacpp_models" row: given the filename ->
 * absolute-path map from [me.rerere.locallm.LocalRuntimePreferences.installedModels],
 * report the total installed count and which filenames' backing file is no longer on
 * disk. Extracted to a top-level function (rather than left inline) so it's unit-testable
 * on the JVM without an Android Context — [DoctorChecks] itself needs one for every other
 * check, which rules out constructing it directly in a plain JUnit test.
 */
internal data class LlamaCppModelStatus(val total: Int, val missing: List<String>)

internal fun llamaCppModelStatus(installed: Map<String, String>): LlamaCppModelStatus =
    LlamaCppModelStatus(
        total = installed.size,
        missing = installed.filterValues { path -> !File(path).exists() }.keys.sorted(),
    )

/**
 * Pure decision logic backing the "storage.gallery_orphans" row: given the resolved
 * absolute paths of every generated-image DB record, report the total and how many no
 * longer have a backing file on disk (the #39 bug class). Mirrors [llamaCppModelStatus]'s
 * shape so both are unit-testable on the JVM without a Context.
 */
internal data class GalleryOrphanStatus(val total: Int, val orphanCount: Int)

internal fun galleryOrphanStatus(absolutePaths: List<String>): GalleryOrphanStatus =
    GalleryOrphanStatus(
        total = absolutePaths.size,
        orphanCount = absolutePaths.count { path -> !File(path).exists() },
    )

/**
 * Pure decision logic backing the "assistant.subagent_profiles" row: which configured
 * [SubAgentProfile]s have a `modelId` that no longer resolves to a chat model of an
 * enabled provider (the #28 failure class; it used to fail silently at dispatch time).
 * Reuses [SubAgentModelResolver.resolve] itself rather than re-deriving model lookup; a
 * profile's `modelId` is already a resolved [kotlin.uuid.Uuid], so it's passed through as
 * the resolver's string input, exactly like a `subagent_dispatch` caller would.
 */
internal data class SubAgentProfileStatus(val total: Int, val broken: List<String>)

internal fun subAgentProfileStatus(
    profiles: List<SubAgentProfile>,
    providers: List<ProviderSetting>,
): SubAgentProfileStatus = SubAgentProfileStatus(
    total = profiles.size,
    broken = profiles.filter { profile ->
        val modelId = profile.modelId ?: return@filter false
        SubAgentModelResolver.resolve(modelId.toString(), providers) is SubAgentModelResolver.Result.Failed
    }.map { it.name },
)

/**
 * Pure decision logic backing the "service.mcp_servers" row: given each configured
 * server's (name, enabled, connected) triple, report the configured/enabled/connected
 * counts and which enabled servers are not currently connected.
 */
internal data class McpServerSummary(
    val configured: Int,
    val enabled: Int,
    val connected: Int,
    val enabledNotConnected: List<String>,
)

internal fun mcpServerSummary(servers: List<Triple<String, Boolean, Boolean>>): McpServerSummary =
    McpServerSummary(
        configured = servers.size,
        enabled = servers.count { (_, enabled, _) -> enabled },
        connected = servers.count { (_, _, connected) -> connected },
        enabledNotConnected = servers.filter { (_, enabled, connected) -> enabled && !connected }
            .map { (name, _, _) -> name },
    )

/**
 * Pure decision logic backing the "skills.seed" row: a bundled skill's on-disk
 * `.core-bundled-hash` sentinel is stale when it's missing, unreadable, or doesn't match
 * the hash of what the app would currently seed. Non-bundled (user-added) entries are
 * never flagged: [isBundled] gates them out entirely, mirroring
 * [me.rerere.rikkahub.data.files.decideSeedAction]'s "never touch a directory we didn't
 * create" rule.
 */
internal data class SkillSeedEntry(
    val name: String,
    val isBundled: Boolean,
    val storedHash: String?,
    val currentHash: String?,
)

internal fun staleSeedSkillNames(entries: List<SkillSeedEntry>): List<String> =
    entries.filter { it.isBundled && it.storedHash != it.currentHash }.map { it.name }
