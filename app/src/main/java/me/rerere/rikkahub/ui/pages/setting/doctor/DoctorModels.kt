package me.rerere.rikkahub.ui.pages.setting.doctor

import android.content.Intent
import androidx.annotation.StringRes

/**
 * One diagnostic line. The Doctor screen renders these in a flat scrollable list grouped
 * by [category]. Severity decides the colour chip; [fix], when present, surfaces a tap
 * target on the row.
 *
 * Pattern is a fusion of two upstream "doctors" we audited:
 *   - hermes-agent's `hermes doctor` (NousResearch): linear category-by-category print
 *     with "ok / warn / fail / info" markers, plus a `--fix` flag for auto-repairable items.
 *   - openclaw/openclaw's `doctor`: per-mutation repair pipeline where each check returns
 *     `{ changes, warnings, mutated_config }` and the orchestrator applies them in sequence.
 *
 * For RikkaHub-as-Android-app the right shape is hermes' surface (categorized list) with
 * openclaw's per-row "apply this fix" action, since most repairs require user awareness
 * (granting permissions, restarting services) rather than silent config rewrites.
 */
data class DoctorCheck(
    /** Stable id used as the Compose `key` and for state lookup. */
    val id: String,
    val category: DoctorCategory,
    /** 行标题资源 ID（多语言）。 */
    @StringRes val labelRes: Int,
    /**
     * 详情文本（保留 String：多为动态拼接/插值，如 "已授予。需求方: xxx"，
     * 资源化需 format 占位符重构，暂保持原样）。
     */
    val detail: String,
    val severity: Severity,
    val fix: FixAction? = null,
)

enum class Severity { OK, INFO, WARN, FAIL }

enum class DoctorCategory(
    @StringRes val displayNameRes: Int,
) {
    Permissions(me.rerere.rikkahub.R.string.doctor_category_permissions),
    Services(me.rerere.rikkahub.R.string.doctor_category_services),
    AssistantInfo(me.rerere.rikkahub.R.string.doctor_category_assistant_info),
    Database(me.rerere.rikkahub.R.string.doctor_category_database),
    Network(me.rerere.rikkahub.R.string.doctor_category_network),
    Termux(me.rerere.rikkahub.R.string.doctor_category_termux),
    Maintenance(me.rerere.rikkahub.R.string.doctor_category_maintenance),
    Diagnostics(me.rerere.rikkahub.R.string.doctor_category_diagnostics),
}

/**
 * What the row's "Fix" button does. Each variant carries everything needed to apply the
 * remedy without having to re-run the check function. AutoFix runs in-process; the Open*
 * variants hand off to system / in-app settings.
 */
sealed interface FixAction {
    /** Tap this to fire an auto-repair (clear cache, run integrity check, register watcher). */
    data class AutoFix(
        @StringRes val labelRes: Int,
        val run: suspend () -> AutoFixResult,
    ) : FixAction

    /** Open a system Settings activity (battery, notification listener, accessibility, etc.). */
    data class OpenIntent(
        @StringRes val labelRes: Int,
        val intent: Intent,
    ) : FixAction

    /**
     * Navigate to a screen inside the app — e.g. "Open assistants" if a config setting needs
     * tweaking. Stored as a string route key the screen passes to the parent NavController.
     */
    data class OpenAppRoute(
        @StringRes val labelRes: Int,
        val routeKey: AppRouteKey,
    ) : FixAction
}

enum class AppRouteKey {
    SettingTelegram,
    SettingScheduledJobs,
    SettingWorkflows,
    SettingPermissions,
    SettingProvider,
    Assistant,
}

data class AutoFixResult(
    val ok: Boolean,
    val message: String,
)
