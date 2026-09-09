---
name: smart-forward
description: 捕获某个 App 的通知，把内容转发给另一个（通常是 Telegram 里的）联系人，并附上一句摘要让接收方能直接行动。适合分享验证码、快递进度、新闻提醒，或「你看这个」的时刻。
allowed-tools: list_recent_notifications notification_action_click launch_app read_window_tree find_node click_node set_text take_screenshot telegram_send_message global_action
---

# 智能转发

从 App A 抓取一条通知，把它的实质内容转发给 App B 里的联系人。开头加一句摘要，让接收方不用自己解读原始文本。

## 何时使用

- "把这封邮件转给<某人>"
- "把刚到的快递单号发给<某人>"
- "告诉我刚收到的验证码给<某人>"（小心——见"不要"部分）
- 收到一条符合用户自定义转发规则的通知（workflow 触发）

## 步骤

1. **挑源通知。** `list_recent_notifications` — 找到用户指的那条（如果是"这个/那个"这类指示词，就挑最新一条）。记下发件人、可见正文和源包。
2. **判断正文是否需要更多。** 通知经常截断到 80-120 字符。如果用户要转完整内容，打开 App：用条目的主操作 `notification_action_click`，然后在结果界面 `read_window_tree`，提取完整消息正文。
3. **写摘要。** 一个句子。示例：
   - 快递通知："你的包裹正在派送——预计今天下午 6 点前到。"
   - 新闻提醒："路透社：<标题>。"
   - 邮件："<发件人>给你发了 <主题>——<一句话要点>。"
   - 验证码：绝不转发——见"不要"。
4. **选目的地。**
   - 如果用户说"Telegram <名字>"：`telegram_send_message(chat_id = <白名单里的名字的 chat id>, text = <摘要 + 正文>)`。
   - 如果用户说的是短信联系人 / 非 Telegram 聊天工具：打开对应 App，`find_node` 找联系人，打开会话，用 `set_text` + 点发送按钮粘贴发送。
5. **确认。** 回复用户"已转发给<某人>" + 一行预览。
6. **返回主页。** `global_action(action = "home")`。

## 用到的工具

- `list_recent_notifications`、`notification_action_click`
- `launch_app`、`read_window_tree`、`find_node`、`click_node`、`set_text`
- `take_screenshot`（仅调试）
- `telegram_send_message`
- `global_action`

## 失败模式

- **源 App 在无障碍树里不暴露正文。** 一些银行 / 双因素认证 App 故意隐藏内容。只转发通知文本并说明"该 App 隐藏了其余内容——想看完整内容请在你的手机上打开"。
- **目的地联系人不明确。** "把文章发给 Anna"——如果白名单里有两个 Anna，问用户是哪一个。不要猜。
- **源通知已被划掉。** 它还在最近环形缓冲里保留几分钟——从那取，但缓存预览可能被截断。

## 不要

- **绝不转发验证码 / 验证码类代码 / 密码重置链接**，除非用户自己输入了验证码并要求你转发。这些机制存在的意义就是不该传播太远。如果用户说"把我的验证码发给 Bob"，拒绝并说明"我不会自动转发验证码——它们只该给你用"。
- **绝不把通知完整正文转发到不在用户白名单里的聊天。** 白名单聊天是明确的；其他一律禁止。
- 不要对同一内容总结+转发两次——如果用户已经说过一次"转发它"，不要在下一个"好的"上重复触发。
