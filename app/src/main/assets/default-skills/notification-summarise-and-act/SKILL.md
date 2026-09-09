---
name: notification-summarise-and-act
description: 读取最近的/活跃的通知流，按 App 分组，总结实际发生了什么，并给出具体可执行的下一步建议。适用于用户问「有什么动静」，或离开手机几小时后回来时。
allowed-tools: list_recent_notifications list_active_notifications dismiss_notification notification_action_click launch_app read_window_tree get_time_info
---

# 通知摘要 + 行动

把用户的通知噪音压缩成一份带具体建议的短简报。目标是"5 秒扫完，10 秒决定"。

## 何时使用

- "我错过了什么" / "有什么动静" / "有什么重要的"
- 用户刚醒 / 开完会拿起手机
- workflow 定时触发（如 "息屏时每 2 小时总结一次通知"）

## 步骤

1. **时间锚点。** `get_time_info` — 记录用户上次交互"从何时起"（尽力而为：用最新通知的 `post_time` 作下界）。
2. **读取通知流。**
   - `list_active_notifications` — 通知栏当前可见的。
   - `list_recent_notifications(limit = 50)` — 环形缓冲；覆盖最近几小时。
3. **按包分组。** 对每个包统计条目数，挑最新标题 + 预览。跳过用户没在 `notification_listener` 设置里白名单的包——那些是噪音。
4. **分类。**
   - **可行动** — 需要回复、回应或决策的（聊天消息、日历邀请、快递、账户告警）。
   - **告知性** — 新闻通讯、App 更新、社交媒体、营销。
   - **紧急** — 银行欺诈告警、未接来电、系统警告、验证码类内容。
5. **组织简报。**
   - 先讲紧急（≤2 行）。
   - 然后可行动（每个来源 1 行，含数量与显而易见的发件人）。
   - 告知性整体省略，除非用户通常想要（查记忆）。
   - 总长度 ≤6 个短句。
6. **提出行动。** 结尾给最多 3 条具体建议：
   - "回复 Telegram 里的<某博>？"
   - "打开银行告警？"
   - "划掉那 14 条营销推送？"
   用户可以说好/不要/跳过，然后你做下一步。
7. **如果用户同意"划掉营销"这类动作**，对每条不重要的条目调用 `dismiss_notification(key = ...)`。未经确认绝不自动划掉。

## 用到的工具

- `list_recent_notifications`、`list_active_notifications`
- `dismiss_notification`、`notification_action_click`
- `launch_app`、`read_window_tree`（用户选了建议后窥视某个聊天）
- `get_time_info`

## 失败模式

- **通知监听器被禁用。** 告诉用户一次并提供设置深链路径："通知监听器是关的——我只能看到 `list_active_notifications` 里的内容。想让我好好跟踪就在 设置 → 通知 里打开它。"
- **没有近期通知。** 没问题——用一句话说明（"没什么新的——最近 3 小时很安静"）然后停止。不要编内容。
- **标题/预览里有验证码形态的内容。** 标为紧急，但**不要**引用代码。说"你的银行发了一条验证码——在手机通知里"。

## 不要

- 未经用户明确同意，不要划掉任何东西。
- 不要朗读或转发消息正文——那是 `smart-forward` 的活，如果用户想要的话。
- 如果总结结果比原始通知还长，就别总结。直接列出。
- 不要因为大写或感叹号就判定"紧急"——App 们用得很随意。
