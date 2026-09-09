---
name: morning-briefing
description: 生成用户的晨间摘要——当前天气、今日日程、未读邮件数、接下来的定时任务，以及电池/存储告警。输出一个短段落，让用户 10 秒读完。
allowed-tools: get_time_info get_battery_status get_storage_info list_active_notifications list_recent_notifications get_jobs_history list_call_log get_location launch_app read_window_tree
---

# 晨间简报

产出一个简短段落，告诉用户开始新一天所需知道的一切。

## 何时使用

用户问 "我今天安排怎么样"、"晨间简报"、"早上好今天有什么日程"、"来份摘要"。或者你正从每周工作日早上 7 点运行的 workflow 中触发它。

## 步骤

在工具允许的情况下并行执行所有读取，最后统一汇总。

1. **时间锚点。** `get_time_info` — 确认本地日期/星期。问候语取决于它（"周五早上" vs "周六早上" vs 节假日称呼）。
2. **设备健康。**
   - `get_battery_status` — 仅在电量 < 30%，或用户平时夜间充电但现在没充时提示。
   - `get_storage_info` — 仅在剩余空间 < 5% 时提示。
3. **通讯。**
   - `list_active_notifications` 过滤到用户在 `notification_listener` 设置里白名单的包——按包分组，统计未读。
   - `list_call_log(type = "missed", limit = 5)` — 提示用户上次交互以来错过的来电。
4. **日历 / 天气。** 两者都由 App 驱动。挑选用户使用的日历 App（`com.google.android.calendar`、`com.microsoft.office.outlook` 等）——用 `launch_app` + 在日视图上 `read_window_tree`，把今天的事件提取成文本。天气同理，用 OEM 天气 App 或用户偏好的（Pixel Weather、Google、AccuWeather）。
5. **定时任务。** `get_jobs_history(limit = 5, since_ms = <过去24h>)` — 提示夜里失败的任务。
6. **组织段落。** 以问候 + 日期开头。然后是告警（如有）。然后是会议（如有）。然后是通讯摘要。最后一行"还有别的吗？"方便用户继续。

## 输出形态

- 总计 ≤4 个句子。不要注水。
- 纯文本——不要 markdown 标题。用户在手机上阅读，或通过 TTS 收听。
- 如果一切正常（电量没问题、存储没问题、无未接来电、无紧急通知、日历为空），用一句话说明并停止。

## 失败模式

- **日历 App 未安装 / 无障碍视图没有结构化文本。** 跳过会议部分；说明"我没能读取你的日历——如果今天有安排请自己打开看看"。
- **天气需要定位但用户拒绝了。** 静默跳过；不要在简报场景里纠缠用户要权限。
- **通知监听器被禁用。** 提一次："顺便说一下，你的通知监听器是关的，我看不到 App 动态——想要我把它纳入明天的简报，就到设置里打开它。"

## 不要

- 不要用本技能逐条念通知。如果有 47 封未读邮件，说"47 封未读邮件"而不是列 47 行。
- 不要引用任何消息正文——只显示标题预览 + 发件人。邮件预览经常包含重置链接、验证码等用户不想被朗读的内容。
- 不要凭日历元数据猜测用户的一天（"看起来很忙！"）。只讲事实。
