# 工具 —— RikkaHub 智能体参考

智能体可调用的每个工具，按能力面分组。每个条目列出：做什么、什么时候用、不明显的坑。工具在 App 内的 *Local tools* 页以开关形式呈现——用户启用了哪些，决定了你运行时实际拥有哪些。这里列出的工具并非始终可用；从本回合的工具列表里挑实际存在的那些。

## 内置（Phase 0）

- **`eval_javascript`** — 在 QuickJS 里跑 JS：算术、字符串变换、JSON 整形。无 Node / DOM。
- **`get_time_info`** — 日期、星期、ISO 时间、时区、epoch 毫秒。便宜；任何调度前先调。
- **`clipboard_tool`** — 读/写设备剪贴板。用户没要求就不要写。
- **`text_to_speech`** — 朗读文本。立即返回；音频在后台播放。
- **`ask_user`** — 提出一个带可选预设选项的问题。当缺少澄清就会白费功夫时使用。

## 设备信息（Phase 1）

- **`device_info(kind="battery")`** — 百分比、是否充电、供电类型、温度。
- **`device_info(kind="audio")`** — 当前音频模式、耳机连接状态、响铃模式。
- **`device_info(kind="telephony")`** — SIM 运营商、网络类型、信号强度。需要 READ_PHONE_STATE。
- **`device_info(kind="wifi")`** — 当前 SSID、BSSID、IP、信号。需要精确定位权限。
- **`device_info(kind="sensors")`** — 枚举传感器，或传 `sensor="accelerometer"` 采样单个传感器。
- **`device_info(kind="storage")`** — 内部 + 外部存储的剩余 / 已用 / 总字节数。

## 自检与维护

- **`diagnostics`** — 检查 App 自身：健康、构建、已启用工具、用量计数、日志、请求、崩溃、生命周期、对话、生成、性能。看日志优先用 `summary:true` 或按级别 / 关键词过滤——原始日志行很吵。
- **`test_model`** — 探测模型端点（provider + model id）：非流式、流式、工具调用支持。在把失败回合归咎于模型之前先跑它。
- **`app_backup`** — 构建 App 数据备份（含加密凭据存储的数据库、设置、头像、技能、工作区文档），本地保存（`target=local`）或上传到 设置 → 备份 里配置的目标。备份项、加密和目标全部来自用户设置——绝不要在聊天里索要备份密码。
- **`check_app_updates`** — 检查发布渠道上比当前安装版本更新的构建。只读。
- **`generate_bug_report`** — 把近期脱敏日志加上 App / 设备 / OS 元数据打包成一个可分享的压缩包。
- **`check_token_usage`** — 读取运行中的 token 总量，与配置的软 / 硬预算对比。用它，而不是猜这个对话离上限还有多远。
- **`tool_surface_report`** — 本回合附加的工具定义的大小构成、排序和哈希。当上下文压力看起来与工具相关时用它。

## 输出 / 通知（Phase 1）

- **`show_toast`** — 短时悬浮提示；不落存储。
- **`post_notification`** — 系统通知，可带点击意图。
- **`share`** — 通过系统分享面板发送字符串 / 文件。
- **`show_image`** — 在聊天里内联显示图片文件。用户想看到已知路径的图片时，或紧跟 `take_photo` / `take_screenshot` 之后使用。
- **`open_file`** — 把文件交给 OS 查看器（图库、PDF 阅读器、音频播放器、文本编辑器）。会把 App 切到后台；用户在目标应用里阅读或编辑。

## 硬件控制（Phase 1）

- **`set_torch`** — 手电筒开关。
- **`vibrate`** — 模式或时长。`pattern` 和 `duration_ms` 二选一，不要都传。
- **`get_brightness`** / **`set_brightness`** — 1..255（低于 1 会被工具钳制，因为多数 Android 构建上 brightness=0 不产生可见变化）。"最低亮度"请求传 `1`。需要 WRITE_SETTINGS。
- **`get_volume`** / **`set_volume`** — 按音频流。需要 DND 访问权限。
- **`set_wallpaper`** — 替换主屏 / 锁屏壁纸。
- **`nfc_read_tag`** / **`nfc_write_tag`** — 读 NDEF 标签，或写一个。写操作只有在用户于调用待决期间把标签贴到手机上才完成——先让他把标签准备好。

## 媒体（Phase 1）

- **`play_media`** — 从 0 位置**启动**一个新的播放会话。会替换任何既有会话（破坏性）。可选的 `title`/`artist`/`album`/`artwork_uri` 会填充系统媒体通知。
- **`pause_media`** / **`resume_media`** — 暂停 / 恢复当前会话且**不**丢失进度。继续播放用 `resume_media`（不要用 `play_media`）。
- **`seek_media(position_ms)`** — 在当前会话内跳转。播放中或暂停中都有效。保持播放 / 暂停状态。
- **`get_media_status`** — 当前曲目 / 位置 / 时长 / 播放状态。免费，无需审批。
- **`stop_media`** — 停止并关闭通知。
- **`scan_media`** — 让 Android 媒体扫描器知道新文件，使它们出现在图库 / 音乐里。
- **`download_file`** — 通过 DownloadManager 把 URL 抓取到 Downloads。
- **`write_text_file`** — 保存文本到路径。默认在文件已存在时拒绝。
- **`whisper_status()`** — 检查 whisper.cpp 转写是否就绪：助手内 Termux 开关已开、Termux App 已装、whisper-cli 在磁盘上、模型（.bin）已就位。返回 `{termux_enabled_in_assistant, termux_app_installed, whisper_cli_installed, whisper_cli_path, model_present, model_path, ready_to_transcribe, missing_steps[], install_commands}`。免费 / 无需审批。在 `transcribe_audio_file` **之前**调用。
- **`transcribe_audio_file(path, language?)`** — 用 whisper.cpp（经 Termux）把音频文件中的语音转写成文本。接受 OGG/Opus（Telegram 语音）、WAV、MP3、M4A、FLAC。返回 `{success, text, language, audio_duration_sec, transcription_time_sec}`。需要 Termux + whisper-cli + 模型文件。
  **禁止幻觉规则：`play_media` 是放给设备扬声器的——它不让你听到内容。用户发来语音问说了什么时，必须调 `transcribe_audio_file` 拿到真实文字。绝不对语音笔记调 `play_media` 然后编造转写稿——那就是幻觉。**

**音频转写流程**

用户发来音频文件或语音笔记（或以其他方式要求转写）时，你**第一个**该调的工具是 `whisper_status()`。它会告诉你 Termux 是否启用、whisper.cpp 是否安装、模型是否就位。三种结果：

1. `ready_to_transcribe: true` → 直接调 `transcribe_audio_file(path, language?)`。
2. `termux_enabled_in_assistant: false` → 告诉用户该助手的 Termux 开关需要打开，位置在 设置 → Local Tools。你无法替他开。
3. 其他任何缺失（whisper 未装、模型缺失）→ 告诉用户缺什么**并**给出 `install_commands` 里的安装命令。运行前征得明确确认。whisper.cpp 构建约 5 分钟；模型下载约 75 MB。不要静默安装。

绝不把 `play_media` 当作转写的替代手段调在音频文件上——那是放给用户扬声器，不给你内容。幻觉"说了什么"是严重失败。

**媒体排错：** 会话进行中用户说"我听不到声音"时，不要调 `play_media`——那会从头重启并丢失用户的位置。应该：`get_media_status`（真的在放吗？）、`get_volume` 和 `device_info(kind="audio")`（音量 / 静音状态）、需要时 `set_volume`。只有会话确实已经没了，才回退到 `play_media`。

## 文件管理（新）

- **`list_files(path, pattern?, recursive?, limit?)`** — 目录列举，可选 glob。
- **`find_files(root, query, recursive?, limit?)`** — 递归按名称子串搜索。
- **`read_file(path, max_bytes?, encoding?)`** — 文本或二进制读取；自动探测。
- **`write_text_file(path, content, append?, overwrite?)`** — 写文本。默认在文件已存在时拒绝。传 `overwrite=true` 截断，或 `append=true` 追加。
- **`write_binary_file(path, base64_content, overwrite?)`** — base64 → 文件。
- **`copy_file(src, dst, overwrite?)`** / **`move_file(src, dst, overwrite?)`** — 复制 / 重命名。
- **`create_directory(path)`** — mkdir -p 语义。
- **`delete_file(path, recursive?)`** — 非空目录没有 `recursive=true` 会拒绝。
- **`file_info(path, include_hash?)`** — stat，可选 sha256。
- **`batch_copy`** / **`batch_move`** / **`batch_delete`** — 同操作的多文件形式：传显式 `paths` 列表，或 `root` + `pattern` glob，加目标目录。`batch_delete` 对非空目录没有 `recursive=true` 会拒绝。

系统路径（`/system`、`/proc`、`/dev`、`/data/data/<其他应用>`）被无条件拦截，返回 `path_blocked` 信封。经 `..` 的路径穿越会被规范化后同样拦截。文件操作优先用这些工具而不是 `termux_run_command`——更快，不需要 shell，不依赖 Termux。

## 压缩包

- **`zip_files`** — 从文件 / 目录列表创建 .zip。源和目标接受 `file://` 和 `content://`（USB / SD / Downloads / 云盘）。
- **`unzip_file`** — 把压缩包解压到目录。
- **`list_zip_contents`** — 不解压列出 .zip 的条目。大型或不熟悉的压缩包先看清单。

## 存储访问（SD / USB / 云盘目录树）

分区存储会在用户授权前把这些目录树对文件工具隐藏。

- **`list_storage_volumes`** — 物理卷（内部、SD 卡、USB）及挂载状态和剩余空间。
- **`list_granted_directories`** — 用户已授予持久访问的目录树。
- **`grant_directory_access`** — 打开系统目录选择器让用户授权一个目录树。必须由用户点击；你无法替他授权。

## 工作区（沙箱化 Linux）

一个拥有独立文件系统的持久 Linux 工作区，与手机存储隔离。用于临时文件、构建和任何需要真实 shell 的事。路径在工作区内是绝对的，例如 `/workspace/notes.md`。

- **`workspace_list`** — 列举工作区及其 id、名称和 shell 状态。
- **`workspace_read_file`** / **`workspace_write_file`** — 读文件，或创建 / 覆盖。读取也支持图片。
- **`workspace_edit_file`** — 精确的 `old_text` → `new_text` 替换，或原子应用的一批编辑（任一项失败则整批中止，文件绝不会被写一半）。
- **`workspace_create_folder`** / **`workspace_read_folder`** — mkdir -p，以及递归树列举。
- **`workspace_shell`** — 在工作区内跑 shell 命令，可带 `cwd`、超时和保存的预设。任何需要真实工具链的事都优先于设备文件工具。
- **`workspace_run_background`** / **`workspace_background_status`** / **`workspace_background_kill`** — 跨回合存活的长时进程（服务器、安装、监视器）：启动一个，记下任务 id，之后再来查，而不是阻塞对话。
- **`workspace_search_code`** — 对工作区文件做正则搜索，返回文件 / 行 / 文本匹配。
- **`diff_files`** — 两个工作区文件的 unified diff；完全相同时会明确说明。

## 个人数据（Phase 2）

- **`get_location`** — 当前经纬度。默认 30 秒超时，回退到上次定位并标注 `cached:true`。
- **`search_contacts`** / **`list_contacts`** — 读通讯录。需要 READ_CONTACTS。
- **`list_call_log`** — 近期来电 / 去电 / 未接。
- **`list_sms_inbox`** / **`search_sms`** — 读收件箱。只读；用户要回复时用 `send_sms`。
- **`send_sms`** — 从默认 SIM 直接发消息，不离开助手。
- **`send_sms_intent`** / **`send_email_intent`** / **`create_contact`** / **`create_calendar_event`** / **`open_wifi_settings`** / **`show_location_on_map`** — 把预填充的动作交给对应的系统应用：用户在短信 / 邮件 / 通讯录 / 日历 / 设置 / 地图应用里审阅并确认。目标能干净映射到系统 intent 时，优先用它们而不是屏幕自动化。
- **`take_photo`** — 打开相机界面；必须由用户按快门。以图片附件返回，让你能看到。
- **`record_audio`** — 固定时长的麦克风采集。
- **`speech_to_text`** — 短语音识别。
- **`verify_fingerprint`** — 生物识别提示；用户指纹匹配即成功。

## 屏幕自动化（Phase 4）

手势前**永远**先读屏幕。正确模式是 `read_window_tree` → 选目标 → `click_node` / `set_text`（知道坐标时才 `tap`）。

- **`tap`** — 在绝对像素坐标单击。
- **`long_press`** — 同 tap，但带按住时长（默认 600ms，范围 100-5000）。
- **`swipe`** — 起点 → 终点，带时长（默认 300ms，范围 50-5000）。
- **`scroll`** — 方向 up/down/left/right；找不到可滚动容器时回退为滑动手势。
- **`read_window_tree`** — 当前前台窗口。默认模式过滤为可交互节点；传 `verbose:true` 得完整树（很大；慎用）。默认 500 节点上限。
- **`find_node`** / **`click_node`** — 按 `text` / `content_description` / `view_id_resource_name` 选择。多个匹配时用 `nth` 消歧。`click_node` 会自动沿父链向上找可点击的祖先。
- **`set_text`** — 往可编辑输入框键入（地址栏、搜索框、表单输入）。用与 `find_node` 相同的选择器维度定位字段。**对 Termux 这类渲染到 Surface 的终端无效**——那些用 `termux_run_command`。
- **`global_action`** — 系统手势：`back`、`home`、`recents`、`notifications`、`quick_settings`、`lock_screen`、`power_dialog`。
- **`take_screenshot`** — 截取当前显示，下一回合以视觉输入图片部件返回。安全界面（DRM、银行、密码框）会优雅报错。OS 限流约 1 次/秒。
- **`wake_screen`** — 屏幕关闭时点亮。设备可能睡着时，在 `launch_app` 或任何手势前调用。设了真实 PIN 时报告 `keyguard_secure:true`；这种情况下用户必须先手动解锁，自动化才能继续。

## 键盘控制（智能体 IME）

对不是节点树输入的字段（WebView 输入框、自定义编辑器），键入走智能体键盘而不是 `set_text`。先聚焦字段——点它，或经 `read_window_tree` → `click_node`。

- **`keyboard_type`** — 在聚焦字段的光标处键入文本。设备上任何位置输入文本的默认入口。
- **`keyboard_read_field`** / **`keyboard_editor_info`** — 读聚焦字段的当前文本，或其元数据（所属包、提示语、输入类型、选区）。
- **`keyboard_press_key`** — 发送单个命名按键（enter、tab、backspace、方向键……）。
- **`keyboard_delete`** / **`keyboard_clear`** — 删除光标前 N 个字符，或清空整个字段。
- **`keyboard_set_cursor`** / **`keyboard_select_range`** — 把光标移到字符位置，或选择一个字符范围。

## 应用启动器

- **`launch_app`** — 按包名打开任意已安装应用。屏幕关闭时自动亮屏并报告 `woke_screen:true`。屏幕自动化前用它把 Termux / 设置 / Chrome / 任意已装应用带到前台。
- **`list_installed_apps`** — 发现可用包名。按子串过滤；默认只列用户安装的应用。
- **`open_url`** — 把 URL 交给系统默认处理器。**当用户请求能干净映射到一个 URL 时，强烈优先于 `launch_app` + 屏幕自动化。** 示例：
  - "在 chrome 里搜 hello" → `open_url("https://www.google.com/search?q=hello")` —— 一次调用搞定。**不要**试图用 `set_text` 驱动 Chrome 地址栏；不可靠，你会死循环。
  - "打开 google.com" → `open_url("https://google.com")`
  - "打 555-1234" → `open_url("tel:555-1234")`
  - "在地图上看 1600 Amphitheatre Pkwy" → `open_url("geo:0,0?q=1600+Amphitheatre+Pkwy")`
  - "发邮件给 foo@bar.com" → `open_url("mailto:foo@bar.com")`

  传 `package_name` 强制指定浏览器；否则用系统默认。
- **`list_app_activities`** — 一个应用声明的 activity（界面）及导出标志。用它找深链目标，而不是在 UI 里点来点去。
- **`launch_activity`** — 按包名和 activity 名直接打开一个特定界面。

## 设备管理（Shizuku）

这些工具通过用户的 Shizuku 服务以 shell 权限运行——不需要 root。除非 Shizuku 在运行且开关打开，否则不可用；服务缺失会返回结构化错误，不是静默失败。

- **`shizuku_exec`** — 以 shell UID 跑单条（或一批，最多 20 条）shell 命令。从信封读 `stdout` / `stderr` / 退出码。绝不用 root；`probe_root=true` 只报告是否存在 `su` 二进制。
- **`appops_get`** / **`appops_set`** — 读一个应用的 AppOps op 模式，或设置一个（`allow` / `ignore` / `deny`）。只有白名单内的 op 可设；其余一律拒绝。
- **`settings_get`** / **`settings_put`** — 读或写系统设置（system / secure / global）。读是只读且免费；写有副作用且需审批。
- **`app_force_stop`** / **`app_enable`** / **`app_disable`** / **`app_uninstall`** — 强停、重新启用、对当前用户隐藏、或卸载应用。后两个会让应用从用户眼前消失——先确认意图。`--user 0` 卸载会保留应用数据。

## Termux 集成

- **`termux_run_command`** — 在 Termux 里跑 shell 命令。**默认模式捕获输出**：命令在后台运行，`stdout` / `stderr` / `exit_code` 在 JSON 信封里返回，供你推理。示例：*"python 装了吗？"* → 跑 `which python3 || echo missing`，读 stdout，决定下一步。*"我的主目录多大？"* → `du -sh ~`。传 `interactive=true` 得到用户可见的会话（该模式不捕获输出——只在用户想看实时输出或跑 `nano` 这类交互程序时有用）。
  - 用户需一次性完成的设置：在 Termux 里跑 `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`，然后强停 Termux 并重开。助手 Local-tools 页的开关行有状态指示灯（红/橙/黄/绿）和"点按验证"入口，会跑一次端到端冒烟测试——变绿后捕获模式即可用。
  - 错误返回结构化信封：`termux_not_installed`、`termux_permission_not_granted`、`termux_permission_denied`（缺 allow-external-apps）、`timeout`。recovery 字段会告诉用户确切要修什么；原样转述。
  - **安装来源：** 只推荐官方 GitHub releases 页 `https://github.com/termux/termux-app/releases`。**不要**推荐 Play Store 或 F-Droid——那些构建无人维护，且与新版 Android 有已知的不兼容。插件（Termux:API、Termux:Boot、Termux:Styling、Termux:X11）同理：只认 GitHub releases。
  - **本地 HTTP 服务器：** 在 Termux 里起一个供*同一台手机*上的浏览器访问的服务器时，绑 `0.0.0.0`，访问 `http://127.0.0.1:PORT`——永远不要用 `localhost`。部分 Android 浏览器和 ROM 只走 IPv6 回环解析 `localhost` 或直接失败；`127.0.0.1` 可靠。另外重启前先 `pkill -f <进程名>`，因为刚杀掉的服务器可能让端口在 TIME_WAIT 里待约 30 秒，新绑定会静默失败。
  - **默认非交互：** `command` 模式的调用会自动包上 `DEBIAN_FRONTEND=noninteractive` 和 dpkg `--force-confdef --force-confold`，所以 `pkg upgrade` / `apt install` 不会卡在 debconf 提示上。这些不需要你自己设。
- **`termux_session_start`** / **`termux_session_send`** / **`termux_session_read`** / **`termux_session_list`** / **`termux_session_kill`** — 持久交互会话（真 pty），用于需要活终端的程序：REPL、`ssh` 密码提示、任何会滚屏的东西。启一个，发输入，不发任何东西再读屏幕，用完杀掉。会话比回合活得长——用完自己清理。

## 通知感知

`notification_listener` 启用时，绑定的监听服务维护一个 100 条容量的近期通知环形缓冲，并（可选）把白名单包的通知自动转发到用户默认的 Telegram 聊天。

- **`list_recent_notifications`** — 历史查询。按 `package_name`、`since_unix_ms` 或 `limit`（默认 50）过滤。返回环形缓冲；条目在被 100 容量挤出或进程死亡前一直存在。用户问"一分钟前那声是什么"时用它。
- **`list_active_notifications`** — 只列所属应用当前仍在展示的通知。当你打算对用户在下拉栏里能看到的东西动手（划掉、点某个动作）时用它。
- **`dismiss_notification`** — `cancelNotification(key)`。只对当前活跃通知有效；已划掉通知的环形缓冲键返回 `not_found`。
- **`notification_action_click`** — 触发通知的一个动作按钮。传 `action_index`（从 0 起）**或** `action_title`（大小写不敏感）。如果动作需要文本输入（如消息类应用的带 RemoteInput 的"回复"），返回 `requires_input`——用 `notification_reply`，或用屏幕自动化工具驱动应用界面。
- **`notification_reply`** — 通过通知的 RemoteInput 回复（消息类应用），不打开应用。"回复那条消息"的直接路径；优先于屏幕自动化。
- **`notification_status`** — 服务绑定状态、环形缓冲大小、白名单大小、默认 Telegram 聊天配置。

自动转发器是发射后不管——它把通知格式化为 `🔔 [应用名] 标题: 内容` 直接调 Telegram，不过 LLM。白名单默认为空；用户在 设置 → 通知 里逐个把应用加进来。

## 检测 Termux 插件

Termux:API、Termux:Boot 等是真实的已安装包，但**没有启动器图标**——只有 `list_installed_apps` 带 `filter`（或 `include_no_launcher=true`）调用时才会出现。每一行带 `has_launcher: bool`；插件返回 `has_launcher: false`，但 `package` 和 `label` 仍然有值，足够确认存在。用户报告 `termux-vibrate` 或任何其他 `termux-api` 前缀的命令在 Termux 里能用，就是 Termux:API 已装的确凿证据，即使你之前的 `list_installed_apps` 漏了它——相信用户。

## SSH

- **`ssh_exec`** — 一次性远程命令。提供 host/port/user/auth，或用保存的主机名配 `ssh_exec_saved` 调用。
- **`save_ssh_host`** / **`list_ssh_hosts`** / **`delete_ssh_host`** — 管理保存的主机（Room 持久化）。
- **`ssh_upload`** / **`ssh_download`** — SFTP 文件传输。
- **`ssh_forget_host_key`** — 用户重装远程机器后 "HostKey has been changed" 的恢复手段。只在用户明确确认远程属于他之后调用。
- **`ssh_presets`** — 列出一个保存主机暴露的、经过验证的命令预设（build / pull / log /……）。优先用预设，而不是手写脆弱的命令串。
- **`ssh_job_poll`** — 跟踪一个后台任务的日志并检查进程是否还活着。为非阻塞轮询而生：一次短调用，不是循环。
- **`vault_deploy_ssh_key`** — 把存储密钥的公钥部分安装到保存主机的 `authorized_keys`（幂等）。私钥部分永远不离开加密凭据存储。

## Cron / 定时任务（Phase 5）

**两种模式，两种计时类型：**

- `mode='llm'` — 触发时，你的 `prompt` 被发到一个全新的无头对话；模型决定调哪些工具。需要推理时用它（"电量低于 20% 就提醒我""总结过去一小时的通知"）。
- `mode='direct'` — 触发时，列出的 `actions[]` 不经 LLM 确定性地执行。免费、快、可预测。固定副作用用它（"每天早上 8 点发早安"）。

**计时：**

- `schedule_type='once'` — 在 `at_unix_ms` 触发一次，然后自动禁用。
- `schedule_type='cron'` — 5 字段 cron 表达式，带别名。示例：
  - `0 9 * * MON-FRI` — 工作日早 9 点
  - `*/15 * * * *` — 每 15 分钟
  - `@every 30m` — 每 30 分钟
  - `@daily` — 每天午夜
  - `0 0 1 * *` — 每月 1 号

  时区默认用设备的；传 IANA id 到 `timezone` 覆盖。

**边界（仅 cron）：** `start_at_unix_ms`、`end_at_unix_ms`、`max_runs`。

**补跑**（默认 `fire_once`）：`skip` / `fire_once` / `fire_all`。控制重启期间错过的时间窗口怎么处理。

**工具：**

- `schedule_job`、`list_jobs`、`delete_job`、`pause_job`、`resume_job`
- `trigger_job_now(id)` — 立即触发，不打乱既有日程
- `get_job_history(id, limit?)` — 最近 N 次运行，新的在前，带结果

## Telegram 机器人（LLM 侧）

- **`telegram_set_token`** / **`telegram_status`** / **`telegram_enable`** / **`telegram_disable`** — 机器人生命周期。
- **`telegram_add_whitelist`** / **`telegram_remove_whitelist`** — 限制机器人回复谁。
- **`telegram_set_default_chat`** / **`telegram_set_assistant`** — 主动发送的默认值。
- **`telegram_send_message`** / **`telegram_send_photo`** / **`telegram_send_document`** — 向指定 chat_id 外发。
- **`telegram_set_commands`** / **`telegram_get_commands`** / **`telegram_delete_commands`** — 控制 Telegram 用户输入时看到的 `/` 前缀菜单。

## 网页与搜索

- **`web_fetch`** — 通过 HTTP(S) 抓取 URL，可选提取可读文章正文；`links` / `metadata` 模式用于结构。拒绝私有、回环和链路本地地址。被截断的结果带 `next_start_index`——用 `start_index` 翻页，而不是重新抓取。
- **`web_extract`** — 把已知 URL 当正文读（只取主要内容）。搜索摘要太短答不上问题时用它。
- **`search_web`** — 通用网页搜索；返回标题、摘要和 URL。
- **`scrape_web`** — 抓取你已从搜索结果中挑出的页面的文本。

## 记忆

跨对话存活的长期事实。核心条目每回合注入；条件条目按需检索。

- **`memory_tool`** — 创建 / 编辑 / 删除 / 列举记忆记录。写操作需要用户确认；优先编辑相关记录而不是建近似重复，且存事实（配置、路径、决策）而不是进度笔记。
- **`memory_search`** — 对条件记忆做关键词搜索。在假定某个细节未知之前先跑它；关键词没命中就列举记录并通读。

## 技能与工具发现

- **`use_skill`** — 照着技能行动前，先加载它的指令（及配套文件）。先读技能，再执行。
- **`skill_get_content`** — 不运行技能，只读它的 markdown；对未启用的技能也有效。
- **`list_tools`** — 列举本对话可用的工具，可按关键词过滤。期待的工具缺失时用它。
- **`get_tool_schema`** — 单个工具的完整描述和参数模式。重试一个注入描述被缩水的工具前先调它。
- **`skill_install_from_url`** / **`skill_install_from_text`** — 从 URL 或你已持有的 markdown / JSON 安装技能。从不可信来源安装前先读内容。
- **`run_js`** — 运行一个 JavaScript 技能并返回其结构化结果。用于会计算、渲染 UI、或用存储的密钥调第三方 API 的技能。

## 对话与委派

- **`recent_chats`** — 用户近期的对话，用于了解他在忙什么。
- **`conversation_search`** — 跨历史对话全文搜索。用聚焦的关键词和多次窄搜索，而不是一次宽搜索。
- **`subagent_dispatch`** — 把一个独立的多步任务交给上下文干净的子智能体，只拿回它的摘要。子智能体看不到本对话，所以要重述它需要的上下文；传一个短标签方便用户识别。并发派发有上限——它报告触顶时退避重试。
- **`subagent_list`** / **`subagent_get`** / **`subagent_cancel`** — 查看运行中的子智能体、取某次运行的完整记录、或取消一个。

## 保险库与密钥库

两个不同的密钥存储。保险库存放凭据（token、密码、密钥）的密文，你只能按名称引用它们——绝不要索要或回显值。Android 密钥库存放不可导出的密钥；你可以借用其运算，但读不出密钥。

- **`vault_credential_names`** / **`vault_credential_meta`** — 列凭据名称（绝不是值），或单条条目的元数据。
- **`vault_credential_prepare`** / **`vault_credential_update`** / **`vault_credential_delete`** — 建一个占位让用户填写、重命名或改描述、或删除一条（删除需明确确认）。
- **`vault_credential_refs`** / **`vault_dangling_refs`** / **`vault_credential_merge`** — 查一个凭据在哪里被引用、列指向不存在名称的引用、或把重复项合并到你保留的那条。
- **`vault_credential_audit`** / **`vault_credential_normalize_names`** — 一个凭据的访问日志，和一次把旧名称规整到要求形式的重命名。
- **`vault_ssh_exec`** / **`vault_http_exec`** — SSH 到主机，或调 HTTP API，凭据在 App 进程内注入。值绝不落盘，也绝不展示给你。
- **`vault_gen_key`** — 生成 SSH 密钥对，私钥留在保险库，公钥返回给你交给远程。
- **`vault_export_env`** / **`vault_export_loadcreds`** / **`vault_import_loadcreds`** / **`vault_compare_loadcreds`** — 把凭据物化为 env 脚本供沙箱 CLI source（用完立刻删文件），或导入 / 比较这样的脚本。含值的文件只留在设备上——绝不上传到任何地方。
- **`keystore_generate_key`** / **`keystore_list_keys`** / **`keystore_delete_key`** — 按别名创建、列举或移除硬件支持的密钥。
- **`keystore_encrypt`** / **`keystore_decrypt`** / **`keystore_sign`** / **`keystore_verify`** — 用密钥库密钥做 AES-256-GCM 加解密或 RSA SHA256withRSA 签名 / 验签。私钥不可导出——只有这些运算可用。

## MCP 控制

管理用户配置的 MCP 服务器。改服务器是用户可见的——添加、更新或移除前先确认。

- **`mcp_list`** — 已配置服务器及连接状态、工具数、是否对本助手启用。已连接但未启用的服务器会以 not-found 失败，直到被打开。
- **`mcp_get`** — 单个服务器的完整配置（头部已脱敏）加它当前暴露的工具。
- **`mcp_add`** / **`mcp_update`** / **`mcp_delete`** — 添加服务器、整体替换其配置、或移除。
- **`mcp_set_enabled`** — 启用或禁用一个服务器。禁用会拆除其客户端；启用会重连。
- **`mcp_list_tools`** — 单个服务器暴露的工具，或所有已启用服务器的聚合。
- **`mcp_test`** — 立即强制重连并重新同步工具。作为 `mcp_add` 后的轮询伴侣，或"它到底活着没"的检查。
- **`mcp_set_tool_approval`** — 切换服务器单个工具的审批要求。

## 外部自动化

控制让同设备上受信任的应用驱动本助手的 intent API。

- **`external_automation_status`** — 总开关状态、哪些调用包受信任、当前限额。
- **`external_automation_set_enabled`** — 打开或关闭该 API。
- **`external_automation_add_trusted_package`** / **`external_automation_remove_trusted_package`** — 把调用方加入 / 移出信任列表。加包等于让它以用户身份发请求——做之前先确认。

## 通用信封结构

工具返回结构化 JSON。常见形态：

- `{success: true, ...}` — 顺利路径。
- `{success: false, reason: "..."}` — 操作完成但结果是"不"。
- `{error: "...", recovery: "..."}` — 状态损坏，附带一条要转述给用户的提示。

看到 `recovery` 时，原样贴进你的回复——那是写给用户的，不是写给你的。
