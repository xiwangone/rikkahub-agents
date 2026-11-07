
package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.BrowserPreferences
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
internal object Capability {
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
            LocalToolOption.DeviceInfo, // SSID/BSSID on Android 10+
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
            LocalToolOption.DeviceInfo, // WiFi scan/info on Android 13+
        )
    val BackgroundLocation: Set<LocalToolOption> =
        setOf(
            LocalToolOption.Workflows, // geofence triggers fire while the app is closed
        )
}

private val DOCTOR_TOOL_STRING_RES =
    mapOf(
        LocalToolOption.Location to R.string.doctor_tool_location,
        LocalToolOption.DeviceInfo to R.string.doctor_tool_device_info,
        LocalToolOption.NotificationListener to R.string.doctor_tool_nl,
        LocalToolOption.ScreenAutomation to R.string.doctor_tool_screen,
        LocalToolOption.SpeechToText to R.string.doctor_tool_stt,
        LocalToolOption.TelegramBot to R.string.doctor_tool_telegram,
        LocalToolOption.CronJobs to R.string.doctor_tool_cron,
        LocalToolOption.Workflows to R.string.doctor_tool_workflow,
        LocalToolOption.Notification to R.string.doctor_tool_notification,
        LocalToolOption.Files to R.string.doctor_tool_files,
        LocalToolOption.Browser to R.string.doctor_tool_browser,
        LocalToolOption.SmsSend to R.string.doctor_tool_sms,
        LocalToolOption.Wallpaper to R.string.doctor_tool_wallpaper,
        LocalToolOption.Keystore to R.string.doctor_tool_keystore,
        LocalToolOption.ExternalStorage to R.string.doctor_tool_ext_storage,
        LocalToolOption.Archive to R.string.doctor_tool_archive,
    )

private val DOCTOR_TOOL_LITERAL_NAMES =
    mapOf(
        LocalToolOption.Termux to "Termux",
        LocalToolOption.Ssh to "SSH",
        LocalToolOption.Nfc to "NFC",
    )

/** Friendly name for the row's "needed by:" subtitle. */
internal fun LocalToolOption.shortName(context: Context): String =
    DOCTOR_TOOL_STRING_RES[this]?.let(context::getString)
        ?: DOCTOR_TOOL_LITERAL_NAMES[this]
        ?: this::class.simpleName
        ?: "?"

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
    internal val context: Context,
    internal val settingsStore: SettingsStore,
    internal val telegramPrefs: TelegramBotPreferences,
    internal val workflowRepository: WorkflowRepository,
    internal val scheduledJobRepository: ScheduledJobRepository,
    internal val scheduledJobRunRepository: ScheduledJobRunRepository,
    internal val conversationRepository: ConversationRepository,
    internal val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    internal val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    internal val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    internal val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
) {
    internal fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

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
                addAll(this@DoctorChecks.networkChecks())
                addAll(this@DoctorChecks.termuxChecks(enabled))
                addAll(this@DoctorChecks.browserChecks(enabled))
                addAll(this@DoctorChecks.maintenanceChecks())
                addAll(this@DoctorChecks.diagnosticsChecks(enabled))
            }
        }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    internal fun requirersOf(
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
                addAll(buildAssistantModelChecks(settings))

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

}
