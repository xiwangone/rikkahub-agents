package me.rerere.rikkahub.data.ai.tools

/**
 * Single source of truth for which tools require user approval before they execute.
 *
 * The matching policy is "tool name in the set" — there's no per-arg gating yet (e.g. you
 * can't say "approve termux but only if the command doesn't start with rm"). When the
 * runtime decides whether to ask the user, it consults this set, then the per-conversation
 * "Allow for this chat" allow-list, then the persistent "Always Allow" allow-list (in that
 * order); the prompt only fires when none of those let it through.
 *
 * MCP-relayed tools (any name starting with `mcp__`) are gated separately at the
 * GenerationLoop level — this set covers only locally-defined tools. See
 * [requiresApproval] for the combined logic.
 *
 * If you add a new LLM-callable tool, decide:
 *   - Is it side-effecting (writes to disk, runs shell, controls hardware, posts to a
 *     remote service, manipulates UI)? → add it here.
 *   - Is it a pure read (battery, location, screenshot, list-installed-apps)? → leave it
 *     out; the user doesn't need to be interrupted for "what's the brightness".
 *
 * Privacy-sensitive READS (contacts, sms, call log) ARE in here: reading PII off the
 * device into an LLM context deserves the same friction as a write does — the secret
 * is leaving the device either way.
 */
object ToolApprovalDefaults {

    /** Tool names that ALWAYS require approval unless the user has granted an exception. */
    val ALWAYS_ASK: Set<String> = setOf(
        // Shell / arbitrary code execution
        "termux_run_command",
        "termux_session_start",  // opens a persistent interactive shell; the meaningful consent moment
        "transcribe_audio_file",  // shells out to whisper-cli via Termux; reads arbitrary audio files
        "eval_javascript",

        // Remote shell (SSH)
        "ssh_exec",
        "ssh_exec_saved",
        "ssh_upload",
        "ssh_download",
        "ssh_forget_host_key",
        "save_ssh_host",
        "delete_ssh_host",

        // Shizuku-privileged shell (shell UID, adb-shell level)
        "shizuku_exec",
        // Shizuku 应用管理组（批次 A，写类全部审批；appops_get 纯读不在此列）
        "app_force_stop",
        "app_disable",
        "app_enable",
        "app_uninstall",
        "appops_set",
        // Shizuku 系统参数组（批次 B，写类审批；settings_get 纯读不在此列）
        "settings_put",

        // Filesystem / network writes
        "write_text_file",
        "download_file",
        "scan_media",

        // Cron mutations (read is free; mutate asks)
        "schedule_job",
        "schedule_job_direct",
        "schedule_job_llm",
        "delete_job",
        "pause_job",
        "resume_job",
        "trigger_job_now",
        "get_job_history",

        // UI manipulation through the AccessibilityService
        "tap",
        "long_press",
        "swipe",
        "scroll",
        "set_text",
        "click_node",
        "click_and_read",  // click + post-click tree read: same side effect as click_node
        "global_action",
        "phone_use",       // operates other apps through its own accessibility service; same trust footprint as tap
        "launch_app",
        "launch_activity",   // launches a specific activity, same trust footprint as launch_app
        "open_url",          // can dial tel:, draft mailto:, hand a URI to any app
        "open_file",         // ACTION_VIEW on a user-supplied path — same surface as launch_app
        "wake_screen",       // acquires wake lock, turns screen on at night

        // Privacy / hardware actuation
        "take_photo",
        "record_audio",
        "speech_to_text",    // activates the microphone + uploads audio to recognizer
        "verify_fingerprint",
        "share",
        "set_torch",
        "vibrate",
        "set_brightness",
        "set_volume",
        "play_media",
        "stop_media",
        "pause_media",
        "resume_media",
        "seek_media",
        // get_media_status is read-only — no approval needed
        "post_notification",

        // Privacy reads — PII leaves the device into the model's context
        "list_call_log",
        "list_contacts",
        "search_contacts",
        "list_sms_inbox",
        "search_sms",
        // Clipboard / focused-field reads — the clipboard and the focused text
        // field routinely hold tokens, passwords and card numbers; reading them
        // into model context is PII exfiltration like contacts/sms, so they get
        // the same gate (previously ungated: the keyboard KDoc claimed the read
        // tools were "deliberately NOT" gated, and clipboard_tool had no entry).
        "clipboard_tool",
        "keyboard_read_field",
        "keyboard_editor_info",

        // Notification listener side effects (read-only listing is free, mutating is not)
        "dismiss_notification",
        "notification_action_click",
        "notification_reply",       // fires a notification's direct-reply RemoteInput action

        // File manager — all ops are side-effecting or read PII from arbitrary paths
        "list_files",
        "read_file",
        "write_binary_file",
        "delete_file",
        "move_file",
        "copy_file",
        "create_directory",
        "file_info",
        "find_files",
        // Batch file ops (item 5.5) — list-or-glob copy / move / delete. Side-effecting
        // over potentially many paths in one call, so each gets the same approval gate as
        // the single-path file tools.
        "batch_copy",
        "batch_move",
        "batch_delete",

        // Telegram outbound — the bot can DM other chats / change its own config
        "telegram_send_message",
        "telegram_send_photo",
        "telegram_send_document",
        "telegram_set_token",
        "telegram_enable",
        "telegram_disable",
        "telegram_add_whitelist",
        "telegram_remove_whitelist",
        "telegram_set_default_chat",
        "telegram_set_assistant",
        "telegram_set_commands",
        "telegram_delete_commands",

        // MCP control — all side-effecting MCP tools require approval.
        // mcp_add and mcp_update are also flagged with "no always-allow" below because a
        // hostile MCP server can exfiltrate everything the LLM has access to and we want
        // a per-call confirmation each time, not a one-shot blanket grant.
        "mcp_add",
        "mcp_update",
        "mcp_delete",
        "mcp_set_enabled",
        "mcp_set_tool_approval",

        // External Automation Intent API — flipping any of these changes who can fire
        // the assistant from outside the app. Privilege-escalation surface, requires
        // approval. Reads (`external_automation_status`) are free.
        "external_automation_set_enabled",
        "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",

        // Reliability bundle — generate_bug_report writes a file to disk that includes
        // a redacted logcat dump. The redactor catches the common token shapes; we still
        // gate so the user can review what's being created and decide whether to share.
        // check_app_updates is read-only and has no entry here.
        "generate_bug_report",

        // Sub-agents — subagent_dispatch spawns an autonomous LLM run with the parent's
        // tool surface. Approval-required so the user sees the task + tools before
        // delegation happens. list / get / cancel are read-only or user-controlling and
        // have no entry here.
        "subagent_dispatch",

        // Workflows (Phase 12) — every mutator goes through the existing approval flow
        // with a human-readable summary rendered by WorkflowApprovalRenderer. workflow_run
        // fires immediately on approve, with HARDLINE still applied to every action.
        // workflow_list / workflow_get are read-only and have no entry here.
        "workflow_create",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",

        // Workspace shell — executes arbitrary shell (foreground or persistent
        // background) inside the bound workspace (local Rootfs or remote SSH
        // host). Same trust footprint as ssh_exec / termux_run_command, so the
        // central set gates it: the per-workspace switch can only ADD approval
        // (see WorkspaceTools.resolveWorkspaceToolApprovalWithCentral), never
        // remove this gate. Deliberately NOT in NO_ALWAYS_ALLOW — YOLO /
        // per-chat authorisation can cover it, like ssh_exec. (Persistent
        // "Always Allow" never applies to workspace_ names by design:
        // ToolApprovalPreferences refuses them because grants are
        // per-workspace, not global — so headless workspace_shell is
        // YOLO-only. Other workspace tools stay switch-gated: their work is
        // confined to the workspace Rootfs, which is exactly what the switch
        // isolates.)
        "workspace_shell",
        "workspace_run_background",

        // Skill import (Phase 16) — pulls a markdown / JSON skill from an arbitrary URL
        // (or from raw text the LLM already fetched via ssh / curl / MCP) and installs
        // it. Whatever is in the skill rides along with the assistant's tool surface,
        // so this is privilege-escalation-adjacent. NO_ALWAYS_ALLOW below.
        "skill_install_from_url",
        "skill_install_from_text",

        // JS skills (Phase 18) — run a skill's JavaScript inside a hidden WebView.
        // The script can issue arbitrary network requests on behalf of the user, so
        // every invocation gets per-call approval. Eligible for "Always allow" once a
        // particular skill is trusted (NOT in NO_ALWAYS_ALLOW — the skill's body has
        // already been reviewed at install time).
        "run_js",

        // Native intent tools (Phase 18) — open the system Calendar / Contacts / SMS /
        // Email composer / WiFi settings / Maps app. Each fires a system intent the user
        // finalises in the destination app. Approval gate ensures the user reviews the
        // pre-filled fields before the LLM hands the action off.
        "create_calendar_event",
        "create_contact",
        "send_email_intent",
        "send_sms_intent",
        "open_wifi_settings",
        "show_location_on_map",

        // In-app browser write tools (Phase 21 / Pass 2). The browser can carry auth tokens
        // in cookies, so anything that mutates page state OR runs JS is approval-gated. Read
        // tools (open, current_url, screenshot, get_text, get_dom, get_links, back, forward,
        // wait_for) are NOT in this set — reading text out of a page is the same trust level
        // as taking a screenshot or reading any other LLM context. browser_done is the
        // loop-control sentinel and never side-effects.
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_submit",
        "browser_select",
        "browser_press_key",
        "browser_eval_js",
        // Composite click+read added in the token-cost optimisation pass — its click
        // side carries the same trust footprint as plain browser_click, so it
        // inherits the same approval gate.
        "browser_click_and_read",

        // web_fetch (item 1.2) — network egress. A bare HTTP GET/POST can exfiltrate
        // anything the LLM puts in the URL / body / headers, so it gets the same
        // approval gate as every other outbound call.
        "web_fetch",
        // web_extract performs the same outbound GET as web_fetch, so it carries the same
        // trust footprint and inherits the same approval gate.
        "web_extract",

        // Phase 25 — Phase 3 second cut. Every mutating tool is approval-gated; the
        // read-only tools (keystore_verify, keystore_list_keys, list_storage_volumes,
        // list_granted_directories, list_zip_contents) are deliberately NOT in this set.
        "send_sms",                 // sends a real SMS — costs money / leaves the device
        "set_wallpaper",            // changes a visible device setting
        "keystore_generate_key",    // creates a hardware key (NO_ALWAYS_ALLOW below)
        "keystore_sign",            // signs arbitrary data with the user's key
        "keystore_encrypt",         // encrypts with the user's key
        "keystore_decrypt",         // decrypts ciphertext (NO_ALWAYS_ALLOW below)
        "keystore_delete_key",      // destroys a key
        "nfc_read_tag",             // opens a foreground reader session
        "nfc_write_tag",            // writes NDEF to a physical tag (NO_ALWAYS_ALLOW below)
        "grant_directory_access",   // persistent read+write to a whole tree (NO_ALWAYS_ALLOW below)
        "zip_files",                // writes an archive to disk / a granted tree
        "unzip_file",               // writes extracted files to disk / a granted tree

        // agent-keyboard control — every tool that mutates the focused text field or cursor
        // is side-effecting and approval-gated. The two read tools (keyboard_read_field,
        // keyboard_editor_info) are gated in the privacy-reads section above: focused-field
        // text can carry credentials. No HARDLINE arm is needed:
        // typing a string into a focused field is not shell execution, and agent-keyboard
        // itself refuses password / sensitive fields at the IME boundary.
        "keyboard_type",
        "keyboard_press_key",
        "keyboard_delete",
        "keyboard_clear",
        "keyboard_set_cursor",
        "keyboard_select_range",

        // Credential vault destructive operations. Deleting an entry is irreversible and no
        // in-app path can restore it, yet `vault_credential_delete`'s own description already
        // promised "requires explicit user approval" — the promise was never wired up here.
        // Also excluded from "Always Allow" (see NO_ALWAYS_ALLOW): a standing grant would let a
        // single bad turn wipe several credentials in a row.
        "vault_credential_delete",
        "vault_credential_merge",
        "vault_credential_normalize_names",
        "vault_gen_key",
        "vault_pgp_sign",
        "vault_pgp_decrypt",
    )

    /**
     * Tools whose approval prompt MUST drop the "Always Allow" button so the user has to
     * confirm every single call. Reserved for tools that are inherently privilege-escalation
     * surfaces — adding an MCP server is exactly that, since a hostile server can exfiltrate
     * anything reachable through the assistant's tool set. Both in-app and Telegram surfaces
     * read this set when rendering the keyboard.
     */
    val NO_ALWAYS_ALLOW: Set<String> = setOf(
        "mcp_add",
        "mcp_update",
        // eval_javascript runs arbitrary code in the QuickJS engine. Even with a wall-clock
        // timeout + a bounded native heap/stack, the code itself is attacker-controllable, so
        // a blanket "Always Allow" would hand unattended cron / Telegram paths a standing
        // arbitrary-code primitive. Require a per-call confirmation every time.
        "eval_javascript",
        // Phase 16 — skill_install_from_url + skill_install_from_text. The skill body is
        // fetched from an arbitrary URL (or supplied as raw text via any other tool, e.g.
        // ssh_exec / termux_run_command for authenticated servers) and installed against
        // the assistant's tool surface. We require per-call approval every single time so
        // the user reviews the URL / source-label + skill name.
        "skill_install_from_url",
        "skill_install_from_text",
        // Phase 21 / Pass 2 — browser_eval_js runs arbitrary JavaScript in a real WebView
        // with the user's cookies, localStorage, and authenticated fetch surface. Even
        // after HARDLINE filters out shell-shaped strings + obvious dynamic-eval patterns,
        // the residual surface is too broad to ever blanket-allow. Every invocation gets
        // an explicit per-call approval card, no exceptions.
        "browser_eval_js",
        // Phase 25 — privilege-escalation surfaces that must confirm every single call:
        //  - keystore_generate_key: mints a hardware key that later sign/encrypt calls trust
        //  - keystore_decrypt: turns ciphertext back into plaintext the model then reads
        //  - nfc_write_tag: permanently rewrites a physical tag's contents
        //  - grant_directory_access: persistent read+write to an entire storage tree,
        //    possibly cloud / Downloads / Pictures
        "keystore_generate_key",
        "keystore_decrypt",
        "keystore_delete_key",
        "nfc_write_tag",
        "grant_directory_access",
        // Credential vault destructive operations: deleting a credential is irreversible and
        // nothing in the app can undo it, so a permanent "Always Allow" would be a standing
        // licence for the model to prune the user's vault unattended.
        "vault_credential_delete",
        "vault_credential_merge",
        // P0-2 — vault_ssh_exec / vault_http_exec: the credential value leaves the app
        // (SSH session / HTTP header) on every call, so a standing "Always Allow" would
        // let an unattended turn (cron / sub-agent / workflow) fire credentials at
        // arbitrary hosts. Per-call confirmation every time; see [isAutoApprovable].
        "vault_ssh_exec",
        "vault_http_exec",
        // save_ssh_host 决定已保存主机的 host/port/user/ssh_options，而 ssh_exec_saved 等会把
        // 主机条目里引用的 vault 凭证（`$$` 口令、vault_credential 私钥）发往该主机。若保存可被
        // blanket 放行，一个无人值守的回合就能"先改条目指向别处、再 ssh_exec_saved"，等于绕开
        // vault_ssh_exec 的逐次确认。保存是低频操作，逐次确认代价很小。
        "save_ssh_host",
        // Phase 13 — External Automation mutations: flipping any of these changes
        // who's allowed to fire the assistant from outside the app (privilege
        // escalation). A standing "Always Allow" would let an unattended turn
        // enable the API or trust a new caller package. Per-call confirmation.
        "external_automation_set_enabled",
        "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",
    )

    /**
     * 「本会话允许」(💬) 作用域：**所有工具都接受**，包括 [NO_ALWAYS_ALLOW]。
     *
     * 分界只在**授权持久度**上，不在工具种类上：
     *  · YOLO（全局）/ 持久「总是允许」/ 无界面自动审批 → 对 NO_ALWAYS_ALLOW 一律无效
     *    （这三条都是长期或无人值守的放行，一次误批会一直生效）；
     *  · 本会话允许 → 对任何工具都有效：在场点一次，(conversationId, toolName) 粒度、
     *    内存态、/new 清零、可单条撤销（[ToolApprovalAllowList]）。
     *
     * 取舍：**已权衡过风险与便利，这里有意选择可用性优先**（不是疏漏，也不是待办）。
     * 若强制逐次点击，实际结果往往是把人推向全局 YOLO —— 全覆盖、长期有效、更危险；
     * 会话级授权把暴露面限制在一次对话内，且 "撤销 / 新建对话" 成本极低。
     * ⚠ 不要出于"看起来更安全"就改回逐次确认：那样只是把风险挪到 YOLO；
     *   真要收紧，请连同 YOLO 一起改，并在提交信息里说明权衡。
     */
    fun allowsChatScope(@Suppress("UNUSED_PARAMETER") toolName: String): Boolean = true

    fun allowsAlwaysAllow(toolName: String): Boolean = toolName !in NO_ALWAYS_ALLOW

    /**
     * Whether a tool may ever be auto-approved by a blanket path (global YOLO,
     * headless auto-approve, per-chat allow-list, persistent "Always Allow").
     *
     * This is exactly [allowsAlwaysAllow]: the NO_ALWAYS_ALLOW contract is "confirm
     * every single call", and a blanket path that auto-approves the tool breaks that
     * contract just as surely as a persistent grant does. The single enforcement
     * point is the auto-approval decision in `ChatService` — every execution path
     * (chat / cron / sub-agent / workflow / Telegram) funnels through it, so a
     * NO_ALWAYS_ALLOW tool can never silently fire in a headless session: it lands
     * in Pending and the turn ends without executing it.
     */
    fun isAutoApprovable(toolName: String): Boolean = allowsAlwaysAllow(toolName)

    /**
     * True if [toolName] requires approval by name. Local tools are looked up in
     * [ALWAYS_ASK]; MCP-relayed tools (`mcp__*`) always match this name rule
     * because the MCP server's tool surface is opaque to us — we can't know which
     * calls are destructive. This is the *name* half of the gate for a relayed
     * call: the effective decision is [mcpRelayNeedsApproval], which ANDs this
     * rule with the stored per-tool flag, so a user-flipped flag can exempt a
     * single read-only tool. The name rule alone no longer means "always
     * gated" — see [mcpRelayNeedsApproval].
     */
    fun requiresApproval(toolName: String): Boolean =
        toolName in ALWAYS_ASK || toolName.startsWith("mcp__")

    /**
     * MCP 中转工具（`mcp__*`）的审批判定：名字规则与存下来的单工具开关取与。
     *
     * [requiresApproval] 对 `mcp__*` 恒为真（名字前缀规则），取与后开关完全
     * 说了算：开关 true → 审批，false → 不审批。新发现工具默认 true（见
     * McpTool.needsApproval），所以默认仍是每次审批；用户把单个开关拨到
     * false 才放行。写成取与而不是直接取开关，是为保留名字规则在判定里——
     * 名字规则与开关缺一不可：将来若前缀规则变化、某名字不再命中名字规则，
     * 开关 true 也不会凭空造出审批。
     */
    fun mcpRelayNeedsApproval(toolName: String, storedNeedsApproval: Boolean): Boolean =
        requiresApproval(toolName) && storedNeedsApproval
    /**
     * Snapshot of the persistent authorisation state a headless fire consults.
     * Read once per fire (not per action) so a grant revoked mid-run cannot
     * change the outcome halfway through the sequence.
     */
    data class HeadlessApprovalSnapshot(
        val yoloEnabled: Boolean,
        val alwaysAllowGrants: Set<String>,
    ) {
        companion object {
            /** Nothing granted — the fail-closed default for tests and unknown callers. */
            val NONE = HeadlessApprovalSnapshot(yoloEnabled = false, alwaysAllowGrants = emptySet())
        }
    }

    /**
     * Headless auto-approval — the unattended twin of ChatService's
     * isToolAutoApprovedForConversation. A headless fire (cron direct-mode,
     * workflow) has no conversation, so it recognises ONLY:
     *  · global YOLO, or
     *  · a persistent "Always Allow" grant for the tool.
     * Session-scoped ("Allow for this chat", 💬) and one-time approvals do not
     * apply — there is nobody in the room to have granted them, and a grant
     * made for one chat must never leak into an unattended run (user decision
     * 2026-11-17: "无人值守只认 YOLO 与持久授权，会话/一次不适用").
     *
     * NO_ALWAYS_ALLOW tools are never auto-approved by any blanket path
     * ([isAutoApprovable] == false): headless has no 💬, so those 16 tools can
     * never run unattended. "ask_user" is likewise never auto-approved
     * headless — it needs a live user to answer, and the chat path only
     * auto-approves it inside a marked headless conversation.
     *
     * Workspace tools ("workspace_*") are never covered by a persistent grant:
     * ToolApprovalPreferences refuses workspace_ names (grants are
     * per-workspace, not global), so headless workspace tools are YOLO-only.
     */
    fun isHeadlessAutoApproved(
        toolName: String,
        snapshot: HeadlessApprovalSnapshot,
    ): Boolean {
        if (toolName == "ask_user") return false
        if (!isAutoApprovable(toolName)) return false
        return snapshot.yoloEnabled ||
            (!me.rerere.rikkahub.data.preferences.isWorkspaceToolName(toolName) &&
                toolName in snapshot.alwaysAllowGrants)
    }

    /**
     * Human-readable reason a headless fire skips [toolName]. Shared by the
     * cron and workflow runners so both run histories word the gap the same
     * way: a NO_ALWAYS_ALLOW tool can never run unattended, anything else
     * just needs a prior grant.
     */
    fun headlessSkipReason(toolName: String): String =
        if (!isAutoApprovable(toolName)) {
            "$toolName can never run unattended (it requires a per-call confirmation every time)"
        } else {
            "$toolName needs authorization first (grant 'Always Allow' in a chat, or enable YOLO)"
        }

    /**
     * Name-based approximation of "would this tool need a prior authorisation
     * grant to run headless". Used by creation-time validation, where only
     * tool NAMES are known (no Tool instances to call needsApproval on).
     *
     * True when the central policy gates the tool ([requiresApproval]) or the
     * tool can never be blanket-approved ([isAutoApprovable] == false — the
     * NO_ALWAYS_ALLOW set, whose members' factories all set needsApproval
     * { true } but which are NOT all in ALWAYS_ASK, e.g. vault_ssh_exec).
     *
     * Known gap (by design; the fire-time check is authoritative and catches
     * it): a tool whose factory lambda returns true but which is in neither
     * set (e.g. vault_export_env) is NOT flagged here — creation succeeds,
     * and the runner skips it at fire time with a visible record. There are
     * no false positives today: every tool this returns true for has
     * needsApproval { true } in its factory or via the LocalTools opt-in.
     */
    fun requiresHeadlessAuthorization(toolName: String): Boolean =
        requiresApproval(toolName) || !isAutoApprovable(toolName)

    /**
     * Creation-time authorisation detail for [toolNames], or null when every
     * tool is already covered by [snapshot]. Shared by the cron validator
     * and the workflow create/update tools so both word the error the same
     * way. Returns the detail text only — callers wrap it in their own error
     * type (`ValidationError` / `errorResponse("authorization_required", …)`).
     *
     * [kind] is the thing being created ("job" / "workflow") and is woven
     * into the wording. Two groups, because the fix differs: tools that can
     * never run unattended must be removed from the definition; the rest just
     * need a prior grant ("Always Allow" in a chat, or YOLO) before the
     * definition is created again.
     */
    fun headlessAuthorizationDetail(
        toolNames: List<String>,
        snapshot: HeadlessApprovalSnapshot,
        kind: String,
    ): String? {
        val unauthorized =
            toolNames.distinct().filter { toolName ->
                requiresHeadlessAuthorization(toolName) &&
                    !isHeadlessAutoApproved(toolName, snapshot)
            }
        if (unauthorized.isEmpty()) return null
        val neverAllowed = unauthorized.filter { !isAutoApprovable(it) }
        val needsGrant = unauthorized - neverAllowed.toSet()
        return buildString {
            if (neverAllowed.isNotEmpty()) {
                append("tools ${neverAllowed.joinToString(", ")} can never run unattended ")
                append("(they require a per-call confirmation every time); remove them from the $kind. ")
            }
            if (needsGrant.isNotEmpty()) {
                append("tools ${needsGrant.joinToString(", ")} need authorization first: ")
                append("in a chat, grant 'Always Allow' for each tool, or enable YOLO, then create the $kind again.")
            }
        }.trim()
    }
}
