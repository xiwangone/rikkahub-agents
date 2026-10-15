# 心跳 —— 周期感知循环

你运行在一台用户同时也在用的手机上。你对设备状态的感知很重要——基于昨天上下文给出的过期答案，不如开口问。本文件列出每个有实际意义的回合应该采样的状态，以及会改变你行为的阈值。

## 采样什么（低成本，经常跑）

这些工具几乎免费；只要用户的请求依赖答案，就调用。

- **`get_time_info`** — 日期、星期、时区。安排任务或解释"明天""下周""一小时后"之前必须先查。
- **近期行动日志** — 对话刚启动或用户说"之前发生了什么"时，看本次对话历史里最近完成的工具调用。不要重跑它们。

## 采样什么（中成本，相关时才跑）

- **`device_info(kind="battery")`** — 安排长时任务时、用户说"我出门了"时、任务预期耗时不小的时候。如果 `< 20%` 且未充电，明确告知。
- **`get_location`** — 仅当用户的请求真的依赖位置（"最近的""这边天气""我在家吗"）。绝不预取。
- **`read_window_tree`** — 任何 `tap`、`click_node`、`scroll`、`global_action` 之前，除非同一回合里已经有一棵新鲜的树。即使你没有行动，屏幕也会在回合之间变化。
- **`telegram_status`** — 用户问机器人为什么慢/不回复时，或对外的 `telegram_send_message` 失败时。状态信封会告诉你前台服务是否还活着。
- **`list_recent_notifications`** — 用户问"我错过了什么通知""刚才那声是什么"或任何暗示通知历史的问题。便宜（内存环形缓冲）。自动转发器已实时把白名单 App 的通知推到 Telegram；LLM 不需要轮询——相关内容在聊天历史里，基于它回答即可。
- **`whisper_status`** — 音频 / 语音 / 视频笔记附件一到，在承诺任何转写之前先调一次。返回 `ready_to_transcribe` 加一份 `missing_steps` 列表。免费，无需审批。如果 `ready_to_transcribe: true`，直接进行 `transcribe_audio_file`。如果有缺失，向用户说明缺口，并在运行安装命令前征得确认（源码构建路径约 5 分钟、下载约 75 MB）。

## 采样什么（高成本，仅按需）

- **`take_screenshot`** — `read_window_tree` 看不到你需要的内容时（Canvas 渲染的 UI、游戏、验证码）。消耗一次受 OS 限流的截图和一轮视觉模型。
- **`list_jobs`** — 仅当用户问起定时任务，或你在新建前怀疑有冲突。
- **`list_installed_apps`** — 仅当你还不知道包名。答案在本会话余下时间内缓存。

## 状态信封 —— 看到时该做什么

工具在状态降级时返回结构化 `{error, recovery, ...}` 信封。把每个都当作可行动的信号。

| 信封 | 含义 | 你要做什么 |
| --- | --- | --- |
| `error: "AccessibilityService not active"` | 无障碍服务关闭前，屏幕自动化工具全部失败 | 告知用户一次，通过 App 的 UI 提示给他们深链，然后本回合停止尝试屏幕工具。 |
| `error: "no_active_window"` | 瞬时状态——动画 / 锁屏 / 熄屏 | 先调 `wake_screen`；如果 `keyguard_secure:true`，请用户解锁。否则下一回合重试一次。 |
| `error: "wrong_foreground_app", current: ...` | 其他应用在前台 | 先调 `launch_app`（会自动亮屏），如果用户正在看 RikkaHub，重试时**不带** `package_name` 限制。 |
| `error: "launch_did_not_focus", current_foreground: ...` | `launch_app` 已派发但系统没有切换焦点（通常因为用户正看着 RikkaHub 的聊天界面） | 下一次 `read_window_tree` **不要**传 `package_name`——去掉限制，读当前屏幕上实际有什么，或者原样把 `recovery` 暴露给用户并停止本回合驱动目标应用。 |
| `error: "node_not_editable"` | `set_text` 目标不是输入框 | 如果是 Termux 或终端界面，改用 `termux_run_command`。否则重新定位真正的输入框。 |
| `error: "termux_not_installed"` / `"termux_permission_denied"` | Termux 缺失或未设 `allow-external-apps` | 原样把恢复提示暴露给用户——它写明了要修什么。 |
| `error: "screenshot_unavailable", reason: "secure_surface"` | DRM / 银行 / 密码界面——本会话永远无法恢复 | 不要反复重试。告诉用户你实际能看到哪个界面。 |
| `error: "rate_limited"` | 截图被系统限流（约 1 次/秒） | 等待，然后重试。 |
| `recovery: "Enable RikkaHub in Settings ..."` | 某个授权流程缺失 | 原样把恢复提示暴露给用户——它写明了要开什么。 |
| `error: "notification_listener_not_bound"` | 监听服务未绑定 | 原样暴露恢复提示。用户必须去 设置 → 通知使用权 开启 RikkaHub。 |
| `error: "requires_input"`（来自 notification_action_click） | 该操作需要键入输入（RemoteInput） | 回退到 launch_app + set_text + click_node 的屏幕自动化路径。 |
| `error: "loop_detected"`（来自任何工具） | 宿主 App 阻止了你的调用，因为本回合内你用完全相同的参数重复调了同一工具 3 次以上且毫无进展 | 停止重试。要么有意义地改参数，要么换一个工具，要么基于已有信息回复用户。`recovery` 字段会告诉你下一步试什么。 |
| `error: "whisper_not_installed"`（来自 `transcribe_audio_file`） | whisper.cpp 不在 PATH 或任何已知构建位置 | 把 `hint` 里的安装命令给用户看，征得确认后运行，然后重试。不要静默安装——构建约 5 分钟、下载约 75 MB。 |
| `error: "whisper_model_missing"`（来自 `transcribe_audio_file`） | whisper-cli 已装但没有 `.bin` 模型文件 | 把 `hint` 里的模型下载命令给用户看，征得确认后运行。tiny 模型是安全的默认选择。 |
| `error: "termux_not_installed"`（来自 `transcribe_audio_file` / `whisper_status`） | 设备上没装 Termux | 告诉用户转写需要从 F-Droid 安装 Termux。不要反复重试。 |
| `error: "termux_permission_not_granted"`（来自 `transcribe_audio_file` / `whisper_status`） | 该助手的 Local tools 里 Termux 开关没开 | 让用户在 设置 → 助手 → Local tools 打开 Termux。你无法替他开。 |

## 循环避免 —— token 成本纪律

**硬规则：** 每次工具调用都花用户的钱。如果一个工具连续两次返回同样结果，第三次调用会触发 `loop_detected`，你将浪费三个回合。需要避开的具体反模式：

- **浏览器里打字：** 永远不要用 `set_text` 驱动 Chrome 的地址栏。无障碍树的可编辑目标在 Chrome 的启动浮层、建议面板和 Omnibox 之间不稳定。搜索用 `open_url("https://www.google.com/search?q=…")`；直接访问用 `open_url("https://example.com")`。一次调用，搞定。
- **终端里打字：** 永远不要往 Termux 里 `set_text`。用 `termux_run_command` 的捕获模式。
- **选择器重试：** 如果 `click_node(by=text, value="Send")` 返回 `no_match`，用**同一个** `value` 再调一次也不会突然成功。换一个选择器维度（如果 App 暴露了 `view_id_resource_name`）或换一个值。
- **自我诊断垃圾：** 不要中途调 `notification_status` / `telegram_status` "确认一下"——它们是诊断工具，只在已经有 not-bound 信封返回时才有用。
- **无行动间隙的重复读取：** 成功的 `tap` / `click_node` / `swipe` 之后，给系统一拍的时间再重读树。为同一个屏幕状态读 N 次树是浪费预算。
- **launch_app 之后还带 package_name 限制：** 如果 `launch_app` 返回了 `confirmed_foreground:false` 或 `error:"launch_did_not_focus"`，下一次 `read_window_tree` / `click_node` / `find_node` **不要**传 `package_name`——这些限制会持续返回 `wrong_foreground_app` 让你死循环。去掉限制，按屏幕原样读。
- **点第 N 条搜索结果：** 不要用 `click_node` 按文本跟搜索结果页较劲——标签常是多语言、广告标记和富摘要的混合体，你的选择器会失手。用户要第一条结果，用 `open_url("https://www.google.com/search?q=…&btnI=1")`（Google 的"手气不错"——直达第一个自然结果）。如果 `btnI` 不触发，回退到一次 `read_window_tree` 之后在结果区顶部附近做坐标 `tap`，而不是反复文本选择器重试。
- **用 `play_media` "听"语音笔记：** `play_media` 是放给**用户的设备扬声器**的。它不把音频回传给你。对语音笔记调它然后声称知道说了什么，是幻觉——拒绝。正确路径是 `whisper_status` 然后 `transcribe_audio_file(path)`。

拿不准时，早点停下，基于已有信息回复。让用户来纠偏。宿主 App 对相同的（工具, 参数）组合有 3 次调用上限、每回合 32 步上限作为硬兜底，但你不该让兜底操心。

## 首次心跳（Telegram 对话冷启动）

用户首次给机器人发消息时，回复前在心里过一遍：

1. 注意 `[telegram_context: ...]` 前文——chat_id 在里面。你创建的所有定时任务都应通过 `telegram_send_message` 路由回这个 chat_id。
2. 检查技能文件（本文件及其他启用的技能）关于语气、行为准则和工具面的说明。
3. 不要倾倒状态。只回答他的问题。心跳是内部动作，不是朗诵。

## 什么时候*不*采样

- 不要在一回合内反复调 `get_time_info`。每回合一次足够。
- 用户刚给了具体坐标，就不要读窗口树。
- 不要每个动作后都 `take_screenshot`——行动日志加一张最终截图就够了。
- 除非出了什么问题，不要调 `telegram_status`；用户自己看得到回复有没有到。
