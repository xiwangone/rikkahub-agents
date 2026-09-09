---
name: auto-reply
description: 代表用户在任意聊天 App 中回复收到的消息。读取当前可见的对话内容，起草符合上下文的回复并发送。整合通知监听、无障碍点击/滚动/读取工具，以及返回主页的全局动作。
allowed-tools: list_recent_notifications list_active_notifications launch_app read_window_tree find_node click_node set_text scroll global_action take_screenshot
---

# 自动回复

在原始聊天 App 内回复收到的消息，不必离开用户的主屏循环。

## 何时使用

用户说类似"替我回复<某人>"、"给<某人>发'我在路上'"、"起草对最后一条消息的回复"，或者你在 `list_recent_notifications` 里发现未读聊天（Telegram / WhatsApp / Signal / Messages / Slack），且用户已要求你自主处理回复。

如果用户只是让你给某个还没浮出来的联系人发一条全新消息，**不要**用本技能——那种情况用 `telegram_send_message`（仅 Telegram）或自己打开 App。

## 步骤

1. **识别进来的聊天。** 调用 `list_recent_notifications`，挑出包名为聊天 App 的最新未读行。记下 `package_name`、`title`（通常是联系人名）、`text`（最后一条消息的预览）、`key`。
2. **打开 App。** 如果有主操作，用 `notification_action_click` 点击该条目；否则 `launch_app(package_name = "<x>")`，App 会落在聊天列表。
3. **打开联系人的会话。** 读取 `read_window_tree`，用 `find_node` 找联系人名的文本节点，`click_node`。如果聊天已经打开，跳过。
4. **读取可见上下文。** 在聊天界面调用 `read_window_tree`。如果最近几条消息不可见，用 `scroll(direction = "up")` 上滑一次。把最近 3-5 条消息从树中提取为纯文本。
5. **起草回复。** 匹配用户的语气（你有记忆；检查 `enableMemory`）。保持简短。如果来的是问题，回答它；是状态更新，确认它；是请求，判断用户现在能否处理还是需要延后。
6. **发送。** `find_node` 找到消息输入框，`set_text` 填入草稿，`find_node` 找发送按钮（像纸飞机/箭头），`click_node`。
7. **确认。** 拍一张 `take_screenshot` 让用户能在聊天记录里核实。
8. **返回主页。** `global_action(action = "home")`。

## 用到的工具

- `list_recent_notifications`、`list_active_notifications`
- `launch_app`、`notification_action_click`
- `read_window_tree`、`find_node`、`click_node`、`set_text`、`scroll`
- `take_screenshot`
- `global_action`

## 失败模式

- **找不到输入框。** 有些 App 用弹层对话框渲染发送消息。稍等片刻再 `read_window_tree` 一次；还是没有就放弃，告诉用户"我打开了聊天但找不到输入框——请手动回复"。
- **发送按钮是灰的。** 草稿多半没设置成功。再试一次 `set_text`；仍然灰色就放弃。
- **打开的会话不对。** 如果 `find_node` 匹配到了别的人（相似名字常见），用 `global_action(action = "back")` 退出，改走搜索框。
- **通知已经被划掉。** `list_recent_notifications` 是 100 条的环形缓冲——划掉后旧条目仍然可见。始终以打开 App 实际确认，而不是相信那条聊天在最顶上。

## 不要

- 未经明确确认，不要在群聊里代表任何人回复——太容易尴尬。
- 不要发送任何包含用户真实个人信息（全名、电话、地址）的内容，除非是用户自己输入的。
- 发送前不要反过来向用户复述整段对话，除非用户要求先确认草稿。
