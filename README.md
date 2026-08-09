<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agents" style="border-radius: 24px" />

# RikkaHub Agents

**🤖 由 AI 维护迭代的手机端 Agent 工具** —— 通过提示词约束、记忆指针索引、工作区文件详情形成经济及较好的跨会话体验。还有更多 Agent 体验模式待探索。（自动压缩上下文应该能用了，连接 reasonix 协作已完成，本地 OCR 还在修……）

[![Release](https://img.shields.io/github/v/release/xiwangone/rikkahub-agents?color=2ea44f&label=最新版本&logo=github)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Stars](https://img.shields.io/github/stars/xiwangone/rikkahub-agents?color=cb3837&label=Stars&logo=github)](https://github.com/xiwangone/rikkahub-agents)
[![Downloads](https://img.shields.io/github/downloads/xiwangone/rikkahub-agents/total?color=blue&label=下载量&logo=download)](https://github.com/xiwangone/rikkahub-agents/releases)
[![License](https://img.shields.io/github/license/xiwangone/rikkahub-agents?color=ff69b4&label=许可)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/xiwangone/rikkahub-agents?color=yellow&label=最近提交&logo=github)](https://github.com/xiwangone/rikkahub-agents/commits/master)

[![下载最新版](https://img.shields.io/badge/⬇️-下载最新版-2ea44f?style=for-the-badge&logo=android)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Reasonix Agents 开发中](https://img.shields.io/badge/🧪-Reasonix%20Agents%20开发中-8b5cf6?style=for-the-badge)](https://github.com/xiwangone/reasonix-agents)

**🤝 相关项目：[Reasonix Agents](https://github.com/xiwangone/reasonix-agents)** — Reasonix 的 Android 原生客户端 · AI 编码助手（开发中）

> 🔧 **使用前提**：Reasonix Agents 为纯客户端，**需在本地或服务器自部署 Reasonix 服务端**（DeepSeek-Reasonix 协议）——通过配置服务器地址/端口/认证连接使用，不支持云端托管，请自备服务资源。


[**English**](README_EN.md) | **简体中文**

> <span style="color:red">**❗️❗️❗️ 注：RikkaHub Agents 包含 80+ 工具，请按需启用，避免常驻过多增加消耗！！！**</span>

> ⚠️ **⚠️⚠️ 重要警告（2026-08-10）** ⚠️⚠️
>
> **2.45.5 用户请直接升级 2.45.7，不要安装 2.45.6！**
>
> `2.45.6` 存在数据库迁移缺陷（升级后闪退），已修复并发布 **2.45.7**。若你当前是 2.45.5（或更早），**请直接安装 2.45.7**（[下载最新版](https://github.com/xiwangone/rikkahub-agents/releases/latest)）；若已误装 2.45.6，请**立即升级 2.45.7** 即可恢复（数据不丢失）。

</div>

---

## 🚨 重要声明

| 项目 | 链接 | 说明 |
|------|------|------|
| 🟡 **本仓库（AI 协助维护版）** | https://github.com/xiwangone/rikkahub-agents | **AI 协助合并上游 + 编译** |
| 🟣 **Reasonix Agents（并列项目）** | https://github.com/xiwangone/reasonix-agents | **Reasonix 的 Android 原生客户端（开发中）** |
| 🔵 **RikkaHub（官方）** | https://github.com/rikkahub/rikkahub | **官方上游项目，本仓库的代码来源** |
| 🟢 **ExTV/rikkahub-agent（原版）** | https://github.com/ExTV/rikkahub-agent | **原版 Fork，本仓库基于此** |

> ### ⚠️ 使用须知
>
> - **❌ 非官方发布** — 不是 RikkaHub 官方团队发布
> - **❌ 非原版发布** — 不是 ExTV 原版开发者发布
> - ✅ 代码来源可信（官方 + 原版），由 AI 协助合并上游代码并持续编译
> - 💡 如遇到问题，建议优先使用 [官方版](https://github.com/rikkahub/rikkahub) 或 [原版 Fork](https://github.com/ExTV/rikkahub-agent)

---

## 功能简介

一个将原生 Android LLM 聊天客户端变为真正设备端 Agent 的 Fork：**80+ 设备工具**、AI 驱动的工作流、定时任务、内置浏览器（AI 操控）、SSH、屏幕自动化、文件管理、音乐播放、语音转文字、可下载的本地 LLM，以及远程 Telegram Bot。所有功能默认关闭，按需开启。

> *"把手机上的待办事项导出为 Markdown 文件，放到工作区。"*
> *"每两小时截一次屏，持续 4 小时，看看我今天下午都干了什么。"*
> *"收到快递通知时，自动截图并保存到相册。"*
> *"在我连上公司 WiFi 后，自动关闭个人 Telegram Bot。"*
> *"用 Termux 写一个 Python 脚本，定时检查天气预报。"*

每一条都是一句话设置。

---

## 功能列表

### 设备控制
点击、滑动、滚动、打字、截图、打开应用、调节亮度/音量、发送通知、检查电池/WiFi/信号/位置/传感器、读取联系人 & 短信、发送短信、设置壁纸、读写 NFC、管理 ZIP 压缩包。**80+ 工具**，全部默认关闭。

### 工作流与定时任务
**工作流** — 用自然语言描述触发器和动作：*"当我到家时，关闭响铃模式。"* 19 种触发器（WiFi、蓝牙、耳机、地理围栏、应用启动、通知、时间、充电、屏幕状态等）和 14 种条件。

**定时任务** — *"每周一早上 8 点"*、*"每两小时"*、*"下周五下午 3 点"*。重启和节电模式后仍有效。

### Telegram Bot
从任何地方与你的助手对话。发问题、发照片、发 PDF、发语音消息。AI 需要确认时弹出 Yes/No 按钮。长消息自动打包为可下载文件。

### 内置浏览器
真正的浏览器内置于应用中。AI 自动点击 Cookie 弹窗、填写搜索框、滚动、读取页面内容。每一步截图流式发送到聊天。

### 文件管理
查找文件、读取、保存、复制、移动、重命名、删除。*"找到手机上所有提到'发票'的 PDF"* — 一句话搞定。

### SSH
保存服务器信息。运行命令、上传文件、拉取备份、检查磁盘、跟踪日志——全部在聊天中完成。支持 WiFi 和移动网络。

### 音乐与媒体
通过 Android 正常媒体控制播放音乐：锁屏封面、耳机键、全部支持。暂停、继续、调音量——聊天或 Telegram 均可。

### Skills
拖入 Markdown Skill 文件，AI 即获得新能力。内置 QR 码生成器、Wikipedia 查询、钢琴、交互式地图等。

### 子 Agent
长任务自动拆分为子 Agent 并行处理，可选择用更小更便宜的模型。`/stop` 一键取消所有子任务。

### MCP 服务器
连接 Model Context Protocol 服务器，AI 获取对应工具。

### 通知与外部触发
AI 可读取、汇总和转发指定应用的通知。白名单默认全空。

### 安全与隐私
三层保护：
1. **每个助手独立开关** — 所有工具默认关闭
2. **每次调用需批准** — 修改性操作执行前询问
3. **HARDLINE 底线** — 危险命令无条件阻止

---

## 快速开始

### 1. 下载 APK
从本仓库右侧 **Releases** 页面下载最新 APK。

### 2. 安装
打开 APK 文件，允许未知来源安装，完成安装。

### 3. 配置 LLM
打开应用 → **设置 → 提供商 → 添加** → 选择 OpenAI 兼容或内置 LiteRT 本地模型。

### 4. 开启功能（可选）
**设置 → 助手 → 本地工具** → 按需开启。

### 5. Telegram Bot（可选）
向 [@BotFather](https://t.me/BotFather) 申请 Token，告诉助手配置即可。

---

## 系统要求

| | |
|---|---|
| **架构** | arm64 或 x86_64 |
| **Android** | 8.0+ (API 26) |
| **存储** | ~80 MB |

---

## 语言支持

English、简体中文、繁體中文（香港）、日本語、한국어、Русский。

---

## 致谢

- **[RikkaHub（官方）](https://github.com/rikkahub/rikkahub)** — 上游项目
- **[ExTV/rikkahub-agent（原版 Fork）](https://github.com/ExTV/rikkahub-agent)** — 原版 Fork

---

## 许可证

**GNU Affero General Public License v3.0 (AGPL-3.0)**

- ✅ 可以自由使用、修改、分发
- ✅ 可以用于商业用途
- ⚠️ 如果通过网络提供服务，必须公开源代码
- ⚠️ 修改后的版本必须使用相同许可证

完整文本见 [LICENSE](LICENSE)。
