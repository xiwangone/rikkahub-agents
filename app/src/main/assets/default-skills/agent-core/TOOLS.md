# Tools — RikkaHub Agent Reference

Every tool the agent can call, grouped by capability surface. Each entry lists: what it does, when to reach for it, and the not-obvious gotchas. Tools surfaced as toggles in the in-app *Local tools* page — what the user has enabled determines which ones you actually have at runtime. Not every tool listed here is always available; pick the ones present in your tool list this turn.

## Built-in (Phase 0)

- **`eval_javascript`** — run JS inside QuickJS for arithmetic, string transforms, JSON shaping. No Node / DOM.
- **`get_time_info`** — date, weekday, ISO time, timezone, epoch ms. Cheap; call before any scheduling.
- **`clipboard_tool`** — read/write the device clipboard. Don't write unless the user asked.
- **`text_to_speech`** — speak text aloud. Returns immediately; audio plays in background.
- **`ask_user`** — surface a question with optional pre-canned options. Use when proceeding without a clarification would waste work.

## Device info (Phase 1)

- **`device_info(kind="battery")`** — percent, charging, plug type, temperature.
- **`device_info(kind="audio")`** — current audio mode, headphones connected, ringer mode.
- **`device_info(kind="telephony")`** — SIM operator, network type, signal strength. Requires READ_PHONE_STATE.
- **`device_info(kind="wifi")`** — current SSID, BSSID, IP, signal. Requires fine location.
- **`device_info(kind="sensors")`** — enumerate sensors, or pass `sensor="accelerometer"` to sample one.
- **`device_info(kind="storage")`** — free / used / total bytes for internal + external storage.

## Self-inspection & maintenance

- **`diagnostics`** — inspect the app itself: health, build, enabled tools, usage counters, logs, requests, crash, lifecycle, conversation, generation, perf. For logs prefer `summary:true` or a level / keyword filter — raw lines are noisy.
- **`test_model`** — probe a model endpoint (provider + model id): non-streaming, streaming, tool-call support. Run it before blaming a failed turn on the model.
- **`app_backup`** — build an app-data backup (database incl. the encrypted credential store, settings, avatars, skills, workspace docs) and either save it locally (`target=local`) or upload it to the destination configured in Settings → Backup. Items, encryption and destination all come from the user's settings — never ask for the backup password in chat.
- **`check_app_updates`** — check the release channel for a build newer than the installed one. Read-only.
- **`generate_bug_report`** — package recent redacted logs plus app / device / OS metadata into one archive for sharing.
- **`check_token_usage`** — read the running token totals and compare them against the configured soft / hard budget. Use it instead of guessing how close this conversation is to the cap.
- **`tool_surface_report`** — size breakdown, ordering and hash of the tool definitions attached to this turn. Reach for it when context pressure looks tool-related.

## Output / notify (Phase 1)

- **`show_toast`** — short transient overlay; not stored.
- **`post_notification`** — system notification with optional click intent.
- **`share`** — send a string / file via the system share sheet.
- **`show_image`** — display an image file inline in the chat. Use it when the user wants to see an image at a known path, or right after `take_photo` / `take_screenshot`.
- **`open_file`** — hand a file to the OS viewer (gallery, PDF reader, audio player, text editor). Backgrounds the app; the user reads or edits it in the destination app.

## Hardware control (Phase 1)

- **`set_torch`** — flashlight on/off.
- **`vibrate`** — pattern or duration. One of `pattern` or `duration_ms`, not both.
- **`get_brightness`** / **`set_brightness`** — 1..255 (the tool clamps below 1 because brightness=0 produces no visible change on most Android builds). For "lowest brightness" requests, pass `1`. Requires WRITE_SETTINGS.
- **`get_volume`** / **`set_volume`** — per stream. Requires DND access.
- **`set_wallpaper`** — replace the home-screen / lock-screen wallpaper.
- **`nfc_read_tag`** / **`nfc_write_tag`** — read an NDEF tag, or write one. A write only completes when the user taps a tag against the phone while the call is still pending — ask them to have the tag ready first.

## Media (Phase 1)

- **`play_media`** — START a new playback session from position 0. Replaces any
  existing session (DESTRUCTIVE). Optional `title`/`artist`/`album`/`artwork_uri`
  populate the system media notification.
- **`pause_media`** / **`resume_media`** — pause/resume the active session WITHOUT
  losing position. Use `resume_media` (not `play_media`) to continue playback.
- **`seek_media(position_ms)`** — jump within the active session. Works whether
  playing or paused. Preserves play/pause state.
- **`get_media_status`** — current track / position / duration / play-state.
  Free / no approval needed.
- **`stop_media`** — stop and dismiss the notification.
- **`scan_media`** — tell Android's media scanner about new files so they show up in Gallery / Music.
- **`download_file`** — fetch URL into Downloads via DownloadManager.
- **`write_text_file`** — save text to a path. Defaults refuse if file exists.
- **`whisper_status()`** — check whether whisper.cpp transcription is ready: Termux toggle
  enabled, Termux app installed, whisper-cli on disk, model (.bin) present. Returns
  `{termux_enabled_in_assistant, termux_app_installed, whisper_cli_installed, whisper_cli_path,
  model_present, model_path, ready_to_transcribe, missing_steps[], install_commands}`.
  Free/no approval. Call this BEFORE `transcribe_audio_file`.
- **`transcribe_audio_file(path, language?)`** — transcribe speech in an audio file to text
  using whisper.cpp (via Termux). Accepts OGG/Opus (Telegram voice notes), WAV, MP3, M4A,
  FLAC. Returns `{success, text, language, audio_duration_sec, transcription_time_sec}`.
  Requires Termux + whisper-cli + a model file.
  **NO HALLUCINATION RULE: `play_media` plays audio to the device speaker — it does NOT
  let the agent hear the content. When the user sends a voice note and asks what was said,
  ALWAYS call `transcribe_audio_file` to get the actual words. Never call `play_media` on a
  voice note and then fabricate a transcript — that is a hallucination.**

**Audio transcription flow**

When the user sends an audio file or voice note (or otherwise asks for transcription),
the FIRST tool you should call is `whisper_status()`. It tells you whether Termux is
enabled, whisper.cpp is installed, and a model is present. Three outcomes:

1. `ready_to_transcribe: true` → call `transcribe_audio_file(path, language?)` directly.
2. `termux_enabled_in_assistant: false` → tell the user the Termux toggle needs to be on
   for this assistant in Settings → Local Tools. You cannot enable it for them.
3. Anything else missing (whisper not installed, model missing) → tell the user what's
   missing AND the install commands from `install_commands`. Ask for explicit confirmation
   BEFORE running them. The whisper.cpp build takes ~5 minutes; the model download is
   ~75 MB. Don't silently install.

NEVER call `play_media` on an audio file as a substitute for transcription — that plays
it through the user's speaker but does NOT give YOU the content. Hallucinating what was
said is a serious failure.

**Troubleshooting media:** if the user says "I can't hear anything" while a session
is active, DO NOT call `play_media` — that restarts from 0 and loses the user's
position. Instead: `get_media_status` (is it actually playing?), `get_volume` and
`device_info(kind="audio")` (volume / mute state), `set_volume` if needed. Only fall back to
`play_media` if the session is genuinely gone.

## File manager (new)

- **`list_files(path, pattern?, recursive?, limit?)`** — directory listing with optional glob.
- **`find_files(root, query, recursive?, limit?)`** — recursive name-substring search.
- **`read_file(path, max_bytes?, encoding?)`** — text or binary read; auto-detects.
- **`write_text_file(path, content, append?, overwrite?)`** — writes text. Default refuses if file exists. Pass `overwrite=true` to truncate or `append=true` to append.
- **`write_binary_file(path, base64_content, overwrite?)`** — base64 → file.
- **`copy_file(src, dst, overwrite?)`** / **`move_file(src, dst, overwrite?)`** — duplicate / rename.
- **`create_directory(path)`** — mkdir -p semantics.
- **`delete_file(path, recursive?)`** — refuses non-empty dirs without `recursive=true`.
- **`file_info(path, include_hash?)`** — stat with optional sha256.
- **`batch_copy`** / **`batch_move`** / **`batch_delete`** — the many-file form of the same operations: pass an explicit `paths` list, or `root` + `pattern` glob, plus the destination directory. `batch_delete` refuses non-empty directories unless `recursive=true`.

System paths (`/system`, `/proc`, `/dev`, `/data/data/<other-apps>`) are blocked
unconditionally with a `path_blocked` envelope. Path-traversal via `..` is
canonicalized and blocked too. Prefer these tools over `termux_run_command` for
file operations — faster, no shell needed, no Termux dependency.

## Archive

- **`zip_files`** — create a .zip from a list of files and/or directories. Sources and destination accept `file://` and `content://` (USB / SD / Downloads / cloud).
- **`unzip_file`** — extract an archive into a directory.
- **`list_zip_contents`** — list the entries of a .zip without extracting it. Check the listing first for a large or unfamiliar archive.

## Storage access (SD / USB / cloud trees)

Scoped storage hides these trees from the file tools until the user grants them.

- **`list_storage_volumes`** — physical volumes (internal, SD card, USB) with mount state and free space.
- **`list_granted_directories`** — the trees the user has already granted persistent access to.
- **`grant_directory_access`** — open the system directory picker so the user can grant a tree. The user must tap it; you cannot grant it for them.

## Workspace (sandboxed Linux)

A persistent Linux workspace with its own filesystem, separate from the phone's storage. Use it for scratch files, builds, and anything that wants a real shell. Paths are absolute inside the workspace, e.g. `/workspace/notes.md`.

- **`workspace_list`** — list workspaces with their id, name and shell status.
- **`workspace_read_file`** / **`workspace_write_file`** — read a file, or create / overwrite one. Reads also handle images.
- **`workspace_edit_file`** — exact `old_text` → `new_text` replacement, or a batch of edits applied atomically (a failing item aborts the whole batch, so the file is never half-written).
- **`workspace_create_folder`** / **`workspace_read_folder`** — mkdir -p, and a recursive tree listing.
- **`workspace_shell`** — run a shell command inside the workspace, with an optional `cwd`, timeout and a saved preset. Preferred over the device file tools for anything that needs real tooling.
- **`workspace_run_background`** / **`workspace_background_status`** / **`workspace_background_kill`** — long-running processes (servers, installs, watchers) that survive across turns: start one, note the task id, then check it later instead of blocking the conversation.
- **`workspace_search_code`** — regex search over workspace files, returning file / line / text matches.
- **`diff_files`** — unified diff of two workspace files; says so explicitly when they are identical.

## Personal data (Phase 2)

- **`get_location`** — current lat/long. 30s default timeout, falls back to last-known fix with `cached:true` annotation.
- **`search_contacts`** / **`list_contacts`** — read contacts. Requires READ_CONTACTS.
- **`list_call_log`** — recent incoming/outgoing/missed calls.
- **`list_sms_inbox`** / **`search_sms`** — read the inbox. Read-only; use `send_sms` when the user wants to reply.
- **`send_sms`** — send a message from the default SIM directly, without leaving the assistant.
- **`send_sms_intent`** / **`send_email_intent`** / **`create_contact`** / **`create_calendar_event`** / **`open_wifi_settings`** / **`show_location_on_map`** — hand a pre-filled action to the matching system app: the user reviews and confirms inside the SMS / mail / contacts / calendar / settings / map app. Prefer these over screen automation whenever the goal maps cleanly to a system intent.
- **`take_photo`** — opens camera UI; user must take the shot. Returned as image attachment so you can see it.
- **`record_audio`** — fixed-duration mic capture.
- **`speech_to_text`** — short utterance recognition.
- **`verify_fingerprint`** — biometric prompt; succeeds on user thumbprint.

## Screen automation (Phase 4)

Always read the screen *before* gesturing. The right pattern is `read_window_tree` → choose target → `click_node` / `set_text` (or `tap` if you know coordinates).

- **`tap`** — single tap at absolute pixels.
- **`long_press`** — same as tap but with a hold duration (default 600ms, range 100-5000).
- **`swipe`** — start → end with duration (default 300ms, range 50-5000).
- **`scroll`** — direction up/down/left/right; falls back to swipe gesture if no scrollable container is found.
- **`read_window_tree`** — current foreground window. Default mode filters to interactive nodes; pass `verbose:true` for the full tree (large; use sparingly). 500-node default cap.
- **`find_node`** / **`click_node`** — selector by `text` / `content_description` / `view_id_resource_name`. `nth` disambiguates when multiple match. `click_node` walks up the parent chain to find a clickable ancestor automatically.
- **`set_text`** — type into an editable input (URL bar, search field, form input). Locate the field with the same selector axes as `find_node`. **Does not work for terminals** like Termux that render to a Surface — for those, use `termux_run_command`.
- **`global_action`** — system gestures: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen`, `power_dialog`.
- **`take_screenshot`** — captures current display, returned as a vision-input image part on your next turn. Secure surfaces (DRM, banking, password fields) error out gracefully. ~1/sec OS rate limit.
- **`wake_screen`** — turns the display on if it was off. Call this before `launch_app` or any gesture when the device may be asleep. Reports `keyguard_secure:true` if a real PIN is set; in that case the user must unlock manually before automation can continue.

## Keyboard control (agent IME)

For fields that are not node-tree inputs (WebView fields, custom editors), typing goes through the agent keyboard instead of `set_text`. Focus the field first — by tapping it, or via `read_window_tree` → `click_node`.

- **`keyboard_type`** — type text into the focused field, at the cursor. The default entry point for entering text anywhere on the device.
- **`keyboard_read_field`** / **`keyboard_editor_info`** — read the focused field's current text, or its metadata (owning package, hint, input type, selection).
- **`keyboard_press_key`** — send a single named key (enter, tab, backspace, arrows, …).
- **`keyboard_delete`** / **`keyboard_clear`** — delete N characters before the cursor, or clear the whole field.
- **`keyboard_set_cursor`** / **`keyboard_select_range`** — move the cursor to a character position, or select a character range.

## App launcher

- **`launch_app`** — open any installed app by package name. Auto-wakes the screen if it was off and reports `woke_screen:true`. Use this to bring Termux / Settings / Chrome / any installed app to the foreground before screen automation.
- **`list_installed_apps`** — discover available package names. Filter by substring; defaults to user-installed apps only.
- **`open_url`** — hand a URL to the system's default handler. **Strongly preferred over `launch_app` + screen automation when the user's request maps cleanly to a URL.** Examples:
  - "search hello in chrome" → `open_url("https://www.google.com/search?q=hello")` — done in one tool call. Do NOT try to drive Chrome's URL bar via `set_text`; it is unreliable and you will loop.
  - "open google.com" → `open_url("https://google.com")`
  - "call 555-1234" → `open_url("tel:555-1234")`
  - "show 1600 Amphitheatre Pkwy on a map" → `open_url("geo:0,0?q=1600+Amphitheatre+Pkwy")`
  - "email foo@bar.com" → `open_url("mailto:foo@bar.com")`

  Pass `package_name` to force a specific browser; otherwise the system default opens.
- **`list_app_activities`** — the activities (screens) one app declares, with exported flags. Use it to find a deep-link target instead of tapping through the UI.
- **`launch_activity`** — open one specific screen directly, by package and activity name.

## Device administration (Shizuku)

These run with shell privileges through the user's Shizuku service — no root. They are unavailable unless Shizuku is running and the toggle is on; a missing service comes back as a structured error, not a silent failure.

- **`shizuku_exec`** — run a single shell command (or a batch, max 20) with the shell UID. Read `stdout` / `stderr` / exit code from the envelope. It never uses root; `probe_root=true` only reports whether a `su` binary exists.
- **`appops_get`** / **`appops_set`** — read an app's AppOps op modes, or set one (`allow` / `ignore` / `deny`). Only a whitelist of ops is settable; everything else is refused.
- **`settings_get`** / **`settings_put`** — read or write a system setting (system / secure / global). Reads are read-only and free; writes are side-effecting and approval-gated.
- **`app_force_stop`** / **`app_enable`** / **`app_disable`** / **`app_uninstall`** — force-stop, re-enable, hide for the current user, or uninstall an app. The last two make the app disappear for the user — confirm intent first. A `--user 0` uninstall keeps the app's data.

## Termux integration

- **`termux_run_command`** — run a shell command in Termux. **Default mode captures output**: the command runs in the background and `stdout` / `stderr` / `exit_code` come back in the JSON envelope so you can reason on them. Examples: *"is python installed?"* → run `which python3 || echo missing`, read stdout, decide. *"how big is my home dir?"* → `du -sh ~`. Pass `interactive=true` for a visible session that the user can watch (no output capture in that mode — only useful when the user wants to see live output or run an interactive program like `nano`).
  - Setup the user must do once: in Termux run `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`, then force-stop Termux and reopen it. The toggle row in the assistant Local-tools page has a state indicator (red/orange/yellow/green) and a "tap to verify" affordance that runs an end-to-end smoke test — once it goes green capture mode works.
  - Errors return structured envelopes: `termux_not_installed`, `termux_permission_not_granted`, `termux_permission_denied` (allow-external-apps missing), `timeout`. The recovery field tells the user exactly what to fix; surface it verbatim.
  - **Install source:** ONLY recommend the official GitHub releases page at `https://github.com/termux/termux-app/releases`. Do NOT recommend the Play Store or F-Droid — those builds are unmaintained and have known incompatibilities with newer Android versions. Same applies to addons (Termux:API, Termux:Boot, Termux:Styling, Termux:X11): GitHub releases only.
  - **Local HTTP servers:** When you spin up a server in Termux that the user will hit from a browser on the *same phone*, bind it to `0.0.0.0` and visit `http://127.0.0.1:PORT` — never `localhost`. Some Android browsers and ROMs resolve `localhost` only over IPv6 loopback or fail outright; `127.0.0.1` is reliable. Also `pkill -f <process>` before relaunching, since a recently-killed server can leave the port in TIME_WAIT for ~30s and the new bind silently fails.
  - **Noninteractive by default:** `command`-mode invocations are auto-wrapped with `DEBIAN_FRONTEND=noninteractive` and dpkg `--force-confdef --force-confold`, so `pkg upgrade` / `apt install` won't hang on debconf prompts. You don't need to set these yourself.
- **`termux_session_start`** / **`termux_session_send`** / **`termux_session_read`** / **`termux_session_list`** / **`termux_session_kill`** — persistent interactive sessions (real pty) for programs that need a live terminal: REPLs, `ssh` password prompts, anything that scrolls. Start one, send input, re-read the screen without sending anything, and kill it when done. Sessions outlive the turn — clean up after yourself.

## Notification awareness

When `notification_listener` is enabled, the bound listener service maintains a 100-entry ring buffer of recent notifications and (optionally) auto-forwards whitelisted packages to the user's default Telegram chat.

- **`list_recent_notifications`** — historical lookup. Filter by `package_name`, `since_unix_ms`, or `limit` (default 50). Returns the ring buffer; entries persist until evicted by the 100-cap or until the process dies. Use this when the user asks "what was that ping a minute ago".
- **`list_active_notifications`** — only the notifications still being shown by their owning apps right now. Use this when you intend to act on something the user can see in the shade (dismiss it, click an action).
- **`dismiss_notification`** — `cancelNotification(key)`. Only works on currently active notifications; ring-buffer keys for already-dismissed notifications return `not_found`.
- **`notification_action_click`** — fire one of a notification's action buttons. Pass `action_index` (0-based) OR `action_title` (case-insensitive). If the action requires text input (e.g. a messaging app's Reply with RemoteInput), it returns `requires_input` — use `notification_reply`, or drive the app UI with the screen-automation tools.
- **`notification_reply`** — answer through a notification's RemoteInput (messaging apps) without opening the app. The direct path for "reply to that message"; prefer it over screen automation.
- **`notification_status`** — service bound, ring buffer size, whitelist size, default Telegram chat configured.

The auto-route forwarder is fire-and-forget — it formats the notification as `🔔 [App] Title: Text` and calls Telegram directly without an LLM round-trip. Empty whitelist by default; the user opts apps in via Settings → Notifications.

## Detecting Termux addons

Termux:API, Termux:Boot, etc. are real installed packages but have **no launcher icon** — they show up only when `list_installed_apps` is called with a `filter` (or `include_no_launcher=true`). Each row carries `has_launcher: bool`; addons return `has_launcher: false` but `package` and `label` are still set, which is enough to confirm presence. The user reporting that `termux-vibrate` or any other `termux-api`-prefixed command works in Termux is conclusive proof that Termux:API is installed even if your earlier `list_installed_apps` call missed it — trust the user.

## SSH

- **`ssh_exec`** — one-shot remote command. Provide host/port/user/auth or call by saved-host name with `ssh_exec_saved`.
- **`save_ssh_host`** / **`list_ssh_hosts`** / **`delete_ssh_host`** — manage saved hosts (Room-persisted).
- **`ssh_upload`** / **`ssh_download`** — SFTP file transfer.
- **`ssh_forget_host_key`** — recovery for "HostKey has been changed" after the user reinstalled a remote. Only call after the user explicitly confirms the remote is theirs.
- **`ssh_presets`** — list the verified command presets a saved host exposes (build / pull / log / …). Prefer a preset over hand-writing a fragile command string.
- **`ssh_job_poll`** — tail a background job's log and check whether its process is still alive. Built for non-blocking polling: one short call, not a loop.
- **`vault_deploy_ssh_key`** — install a stored key's public half into a saved host's `authorized_keys` (idempotent). The private half never leaves the encrypted credential store.

## Cron / scheduled jobs (Phase 5)

**Two modes, two timing types:**

- `mode='llm'` — at fire time, your `prompt` is sent to a fresh headless conversation; the model decides what tools to call. Use this when reasoning is required ("if battery < 20%, message me", "summarize last hour of notifications").
- `mode='direct'` — at fire time, the listed `actions[]` execute deterministically without the LLM. Free, fast, predictable. Use this for fixed side effects ("post 'good morning' every 8am").

**Timing:**

- `schedule_type='once'` — fires once at `at_unix_ms`, then auto-disables.
- `schedule_type='cron'` — 5-field cron expression with aliases. Examples:
  - `0 9 * * MON-FRI` — weekdays 9am
  - `*/15 * * * *` — every 15 minutes
  - `@every 30m` — every 30 minutes
  - `@daily` — midnight every day
  - `0 0 1 * *` — first of every month

  Timezone defaults to the device's; pass IANA id via `timezone` to override.

**Bounds (cron only):** `start_at_unix_ms`, `end_at_unix_ms`, `max_runs`.

**Catchup** (default `fire_once`): `skip` / `fire_once` / `fire_all`. Controls what happens for windows missed during reboot.

**Tools:**

- `schedule_job`, `list_jobs`, `delete_job`, `pause_job`, `resume_job`
- `trigger_job_now(id)` — fire immediately, doesn't disturb the schedule
- `get_job_history(id, limit?)` — last N runs newest-first, with outcomes

## Telegram bot (LLM-side)

- **`telegram_set_token`** / **`telegram_status`** / **`telegram_enable`** / **`telegram_disable`** — bot lifecycle.
- **`telegram_add_whitelist`** / **`telegram_remove_whitelist`** — restrict who the bot replies to.
- **`telegram_set_default_chat`** / **`telegram_set_assistant`** — defaults for proactive sends.
- **`telegram_send_message`** / **`telegram_send_photo`** / **`telegram_send_document`** — outbound to a specific chat_id.
- **`telegram_set_commands`** / **`telegram_get_commands`** / **`telegram_delete_commands`** — control the `/`-prefix menu Telegram users see when typing.

## Web & search

- **`web_fetch`** — fetch a URL over HTTP(S), optionally extracting readable article text; `links` / `metadata` modes for structure. Refuses private, loopback and link-local addresses. A truncated result carries `next_start_index` — page through with `start_index` instead of re-fetching.
- **`web_extract`** — read a known URL as prose (main content only). Use it when a search snippet was too short to answer the question.
- **`search_web`** — general web search; returns titles, snippets and URLs.
- **`scrape_web`** — pull the text of a page you already picked out of search results.

## Memory

Long-term facts that survive across conversations. Core entries are injected every turn; conditional entries are retrieved on demand.

- **`memory_tool`** — create / edit / delete / list memory records. Writes need the user's confirmation; prefer editing a related record over creating a near-duplicate, and store facts (config, paths, decisions) rather than progress notes.
- **`memory_search`** — keyword search over conditional memories. Run it before assuming a detail is unknown; if the keyword misses, list the records and read through them.

## Skills & tool discovery

- **`use_skill`** — load a skill's instructions (and its supporting files) before acting on it. Read the skill first, then execute.
- **`skill_get_content`** — read a skill's markdown without running it; works on disabled skills too.
- **`list_tools`** — list the tools available in this conversation, optionally filtered by a keyword. Use it when a tool you expect is missing.
- **`get_tool_schema`** — full description and parameter schema for one tool. Call it before retrying a tool whose injected description was abbreviated.
- **`skill_install_from_url`** / **`skill_install_from_text`** — install a skill from a URL, or from markdown / JSON you already hold. Read the content before installing from an untrusted source.
- **`run_js`** — run a JavaScript skill and return its structured result. Use it for skills that compute something, render UI, or call a third-party API with a stored secret.

## Conversations & delegation

- **`recent_chats`** — the user's recent conversations, for context on what they were working on.
- **`conversation_search`** — full-text search across past conversations. Use focused keywords and several narrow searches rather than one broad one.
- **`subagent_dispatch`** — hand an independent, multi-step task to a focused sub-agent with a clean context and get back only its summary. The sub-agent does not see this conversation, so restate the context it needs; pass a short label so the user can recognise it. Concurrent dispatch is capped — back off and retry when it reports the cap.
- **`subagent_list`** / **`subagent_get`** / **`subagent_cancel`** — inspect running sub-agents, fetch one run's full record, or cancel one.

## Vault & Keystore

Two different secret stores. The vault holds credentials (tokens, passwords, keys) as ciphertext and you only ever reference them by name — never ask for, or echo, a value. The Android Keystore holds non-exportable keys; you can borrow their operations but not read them.

- **`vault_credential_names`** / **`vault_credential_meta`** — list credential names (never values), or one entry's metadata.
- **`vault_credential_prepare`** / **`vault_credential_update`** / **`vault_credential_delete`** — create a placeholder for the user to fill in, rename or re-describe an entry, or delete one (deletion needs explicit confirmation).
- **`vault_credential_refs`** / **`vault_dangling_refs`** / **`vault_credential_merge`** — find where a credential is referenced, list references pointing at a name that no longer exists, or merge a duplicate into the entry you keep.
- **`vault_credential_audit`** / **`vault_credential_normalize_names`** — the access log for a credential, and a rename pass that brings legacy names to the required form.
- **`vault_ssh_exec`** / **`vault_http_exec`** — SSH to a host, or call an HTTP API, with a stored credential injected inside the app process. The value is never written to disk and never shown to you.
- **`vault_gen_key`** — generate an SSH key pair, keep the private half in the vault, and return the public key to hand to the remote.
- **`vault_export_env`** / **`vault_export_loadcreds`** / **`vault_import_loadcreds`** / **`vault_compare_loadcreds`** — materialise credentials as an env script so a sandbox CLI can source them (delete the file right after use), or import / compare such a script. Files that contain values stay on the device — never upload them anywhere.
- **`keystore_generate_key`** / **`keystore_list_keys`** / **`keystore_delete_key`** — create, enumerate or remove hardware-backed keys by alias.
- **`keystore_encrypt`** / **`keystore_decrypt`** / **`keystore_sign`** / **`keystore_verify`** — use a Keystore key for AES-256-GCM encrypt/decrypt or RSA SHA256withRSA sign/verify. The private key cannot be exported — only these operations are available.

## MCP control

Manage the MCP servers the user has configured. Changing a server is user-visible — confirm before adding, updating or removing one.

- **`mcp_list`** — configured servers with connection status, tool counts and whether each is enabled for this assistant. A server that is connected but not enabled will fail as not-found until it is turned on.
- **`mcp_get`** — one server's full configuration, with headers redacted, plus the tools it currently exposes.
- **`mcp_add`** / **`mcp_update`** / **`mcp_delete`** — add a server, replace its configuration in one shot, or remove it.
- **`mcp_set_enabled`** — enable or disable a server. Disabling tears down its client; enabling reconnects.
- **`mcp_list_tools`** — the tools one server exposes, or the aggregate across all enabled servers.
- **`mcp_test`** — force a re-connect and tool re-sync right now. Use it as a poll companion after `mcp_add`, or as an "is it really alive" check.
- **`mcp_set_tool_approval`** — toggle the approval requirement on a single tool of a server.

## External automation

Controls the intent API that lets a trusted app on the same device drive this assistant.

- **`external_automation_status`** — whether the master toggle is on, which caller packages are trusted, and the current limits.
- **`external_automation_set_enabled`** — turn the API on or off.
- **`external_automation_add_trusted_package`** / **`external_automation_remove_trusted_package`** — add or remove a caller from the trusted list. Adding a package lets it issue requests as the user — confirm before doing it.

## Universal envelope shapes

Tools return structured JSON. Common shapes:

- `{success: true, ...}` — happy path.
- `{success: false, reason: "..."}` — operation completed but the result is "no".
- `{error: "...", recovery: "..."}` — broken state, with a hint to surface to the user.

When you see `recovery`, paste it into your reply verbatim — it's written for the user, not for you.
