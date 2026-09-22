<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agents" style="border-radius: 24px" />

# RikkaHub Agents

**🤖 设备端 Agent 工具** —— 170+ 内置工具按需启用，一句话完成手机自动化。

把手机本身变成 Agent 的执行环境：应用、通知、文件、媒体、SSH、浏览器、定时与工作流，全部由对话驱动。
LLM 提供商由你自行配置（OpenAI 兼容 / 本地模型），**对话与数据只在你选择的提供商与设备之间流转**。

[![Release](https://img.shields.io/github/v/release/xiwangone/rikkahub-agents?color=2ea44f&label=最新版本&logo=github)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Build](https://github.com/xiwangone/rikkahub-agents/actions/workflows/01-build.yml/badge.svg)](https://github.com/xiwangone/rikkahub-agents/actions/workflows/01-build.yml)
[![Stars](https://img.shields.io/github/stars/xiwangone/rikkahub-agents?color=cb3837&label=Stars&logo=github)](https://github.com/xiwangone/rikkahub-agents)
[![Downloads](https://img.shields.io/github/downloads/xiwangone/rikkahub-agents/total?color=blue&label=下载量&logo=download)](https://github.com/xiwangone/rikkahub-agents/releases)
[![License](https://img.shields.io/github/license/xiwangone/rikkahub-agents?color=ff69b4&label=许可)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/xiwangone/rikkahub-agents?color=yellow&label=最近提交&logo=github)](https://github.com/xiwangone/rikkahub-agents/commits/master)

[**English**](README_EN.md) | **简体中文**

[![📥 下载 APK](https://img.shields.io/badge/📥-下载%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![🧪 Reasonix Agents 开发中](https://img.shields.io/badge/🧪-Reasonix%20Agents%20开发中-8b5cf6?style=for-the-badge)](https://github.com/xiwangone/reasonix-agents)

> <span style="color:red">**❗️❗️❗️ 注：包含 170+ 内置工具，请按需启用 —— 常驻过多会持续占用上下文与额度！**</span>

> 💡 **同账号另有** [Reasonix Agents](https://github.com/xiwangone/reasonix-agents)：Reasonix 协议的原生 Android 客户端（开发中），与本项目定位不同 —— 本项目把手机当执行环境，那个项目连接你自己的 Reasonix 服务端。

> 🔧 **关于 Reasonix Agents 的使用前提**：它是纯客户端，**需在本地或服务器自部署 Reasonix 服务端**（DeepSeek-Reasonix 协议），通过配置服务器地址 / 端口 / 认证连接使用，不支持云端托管，请自备服务资源。

</div>

---

## ✨ 核心特色

- 📱 **真正的设备端执行** — 点击、滑动、打字、截图、开应用、读通知、改设置，不是「建议你去做」，而是「已经做完」
- 🗂️ **文件与工作区** — 查找/读取/编辑/整理文件；**工作区沙箱**隔离读写，可按会话或子代理绑定
- ⏰ **工作流与定时任务** — 自然语言描述触发与动作（19 种触发器 / 14 种条件）；重启与省电模式后依然生效
- 🌐 **内置浏览器（AI 操控）** — 自动处理 Cookie 弹窗、填写表单、滚动取页，每一步截图流式回传
- 🤖 **子 Agent** — 长任务拆分为独立子任务并行处理；可指定模型、工作区与**工具白名单**（默认只读 + 工作区内读写），并回传用量
- 🧩 **Skills 与 MCP** — 拖入 Markdown 即获得新能力；连接 MCP 服务器扩展工具面
- 🔌 **SSH 与 Telegram 远控** — 聊天里跑命令、传文件、看日志；不在手机旁也能对话与审批
- 🔐 **安全默认** — 每个助手独立开关、修改性操作逐次审批、HARDLINE 无条件拦截

---

## 🚨 重要声明

| 项目 | 链接 | 说明 |
|------|------|------|
| 🟡 **本仓库（AI 协助维护版）** | https://github.com/xiwangone/rikkahub-agents | **AI 协助合并源代码并持续编译** |
| 🟣 **Reasonix Agents（同账号项目）** | https://github.com/xiwangone/reasonix-agents | **Reasonix 协议的原生 Android 客户端（开发中）** |
| 🔵 **RikkaHub（官方）** | https://github.com/rikkahub/rikkahub | **官方项目，本仓库的代码来源** |
| 🟢 **ExTV/rikkahub-agent（原版 Fork）** | https://github.com/ExTV/rikkahub-agent | **原版 Fork，本仓库基于此** |

> ### ⚠️ 使用须知
>
> - **❌ 非官方发布** — 不是 RikkaHub 官方团队发布
> - **❌ 非原版发布** — 不是 ExTV 原版开发者发布
> - ✅ 代码来源可信（官方 + 原版），由 AI 协助合并源代码并持续编译
> - 💡 如遇到问题，建议优先使用 [官方版](https://github.com/rikkahub/rikkahub) 或 [原版 Fork](https://github.com/ExTV/rikkahub-agent)

---

## 🏗️ 架构概览

### 模块划分

| 模块 | 职责 |
|---|---|
| `app` | 应用外壳：界面、设置、会话、**工具装配**与生成循环 |
| `ai` | LLM 抽象层：提供商实现、消息与流式协议、工具调用协议 |
| `workspace` | **工作区沙箱**：文件读写、目录挂载、后台任务、命令执行 |
| `agent-tools` | 工具基建：注册、schema、按需注入与错误信封 |
| `common` / `material3` / `highlight` | 通用工具、主题与组件、代码高亮 |
| `document` / `search` / `speech` / `web` | 文档解析、联网检索、语音、内置浏览器 |
| `local-llm` / `llama-cpp` / `videogen` | 本地模型推理、llama.cpp 绑定、视频生成 |

### 关键机制

- **工具按需注入**：工具面按名称稳定排序后注入请求前缀，并支持「冷档」—— 未解锁的工具只发空 schema，避免 170+ 工具一次性吃满上下文。
- **审批与底线**：每个助手独立开关工具；修改性调用逐次审批；危险命令由 HARDLINE 无条件拦截。定时任务与子代理这类无人值守的会话走独立通道。
- **工作区隔离**：文件操作限定在会话绑定的工作区；子代理可绑定独立工作区，避免与主会话互相覆盖。
- **子代理执行体**：每次派发是**独立会话**（配置与模型可复用，上下文不继承），有超时与步数上限；默认只读工作区，可在配置页按需放开白名单。
- **可观测性**：每次生成回传 token 用量（含缓存命中）；子代理完成时把结论与用量回投主会话。

---

### 数据流

```text
        ┌───────────────────────────────────────────────┐
        │            你选择的 LLM 提供商                 │
        │     （OpenAI 兼容 API / 本地 LiteRT 模型）     │
        └───────────────────────┬───────────────────────┘
                                │ 对话 / 工具调用
        ┌───────────────────────▼───────────────────────┐
        │            RikkaHub Agents（本应用）           │
        │  助手 / 会话 ──▶ 工具装配（按需注入 + 冷档）    │
        │        ▲                    │ 批准 · HARDLINE │
        │        │ 结果               ▼                 │
        │  定时 / 工作流      工作区沙箱（读写隔离）      │
        │  子 Agent（并行）   MCP / Skills 扩展          │
        └───────────────────────┬───────────────────────┘
                                │
        ┌───────────────────────▼───────────────────────┐
        │                Android 设备能力                │
        │  应用控制 · 通知 · 文件 · 媒体 · 传感器 · SSH   │
        └───────────────────────────────────────────────┘
```

无人值守的会话（定时任务、子代理）走独立通道：工具面收敛、修改性操作不逐次询问，但仍受 HARDLINE 约束。

---

## 🧱 技术栈

| 层面 | 技术 |
|---|---|
| 语言 | Kotlin 2.4.20 |
| UI | Jetpack Compose（Material 3，BOM 2026.08.00） |
| 架构 | MVVM（ViewModel + Repository + Room / DataStore） |
| 网络 | OkHttp 5.4.0 · Ktor 3.5.2 |
| 序列化 | kotlinx.serialization |
| 图片 | Coil 3.5.0 |
| 依赖注入 | Koin 4.2.2 |
| 协程 | kotlinx.coroutines 1.11.0 |
| 构建 | Gradle 9.5.0 + AGP 9.3.1 + Version Catalog |
| 编译 / 目标 SDK | 37（Android 15+）；最低 26（Android 8.0） |

---

## 📁 项目结构

```text
rikkahub-agents/
├── app/              # 应用外壳：界面、设置、会话、工具装配与生成循环
├── ai/               # LLM 抽象层：提供商、消息与流式协议、工具调用协议
├── workspace/        # 工作区沙箱：文件读写、目录挂载、后台任务、命令执行
├── agent-tools/      # 工具基建：注册、schema、按需注入、错误信封
├── common/           # 通用工具与扩展
├── material3/ highlight/   # 主题与组件、代码高亮
├── document/ search/ speech/ web/   # 文档解析、联网检索、语音、内置浏览器
├── local-llm/ llama-cpp/ videogen/  # 本地推理、llama.cpp 绑定、视频生成
├── build-logic/      # 构建约定插件
└── locale-tui/ trace-cli/ web-ui/   # 多语言、调用追踪与 Web 辅助工具
```

---

## 功能简介

一个把原生 Android LLM 聊天客户端变成设备端 Agent 的项目：**170+ 内置工具**（设备控制、凭证、工作区、SSH、子代理等）、AI 驱动的工作流、定时任务、内置浏览器（AI 操控）、SSH、屏幕自动化、文件管理、音乐播放、语音转文字、可下载的本地 LLM，以及远程 Telegram Bot。**所有功能默认关闭，按需开启。**

> *"把手机上的待办事项导出为 Markdown 文件，放到工作区。"*
> *"每两小时截一次屏，持续 4 小时，看看我今天下午都干了什么。"*
> *"收到快递通知时，自动截图并保存到相册。"*
> *"在我连上公司 WiFi 后，自动关闭个人 Telegram Bot。"*
> *"用 Termux 写一个 Python 脚本，定时检查天气预报。"*
> *"把公司的 SSH 私钥存进凭证库，之后用我的密钥推送代码。"*
> *"克隆我的仓库、改一处文案、跑完单测再提交。"*
> *"开车时开语音模式连续对话，回复直接朗读出来。"*

每一条都是一句话设置。

---

## 功能列表

### 设备控制
点击、滑动、滚动、打字、截图、打开应用、调节亮度/音量、发送通知、检查电池/WiFi/信号/位置/传感器、读取联系人 & 短信、发送短信、设置壁纸、读写 NFC、管理 ZIP 压缩包。以上为设备与本地能力类工具，全部默认关闭。

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
长任务自动拆分为子 Agent 并行处理，可选择用更小更便宜的模型；可指定工作区与工具白名单。`/stop` 一键取消所有子任务。

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

### 6. 从源码构建（可选）

前置：**JDK 17+**、Android SDK（**API 37**）。

```bash
git clone https://github.com/xiwangone/rikkahub-agents.git
cd rikkahub-agents
./gradlew :app:assembleDebug          # 产物：app/build/outputs/apk/debug/
adb install app/build/outputs/apk/debug/app-debug.apk   # 安装到已连接设备
```

---

## 系统要求

| | |
|---|---|
| **最低版本** | Android 8.0（API 26） |
| **编译 / 目标版本** | API 37 |
| **架构** | arm64-v8a 或 x86_64 |
| **存储** | ~80 MB |

---

## 语言支持

English、简体中文、繁體中文（香港）、日本語、한국어、Русский。

---

## 致谢

这个项目能存在，首先要感谢：

- **[RikkaHub](https://github.com/rikkahub/rikkahub)** —— 官方项目。应用的界面、模型接入、工具框架等绝大部分基础都来自这里，感谢作者与维护者的长期投入。
- **[ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)** —— 原版 Fork。本仓库直接基于它构建，并持续从这两处合并改进，感谢它的作者。
- 以及上述项目所依赖的全部开源库与工具的作者们。

本仓库的整理、合并与构建由 AI 协助完成。**如果遇到问题，建议先到官方项目反馈**；如果是本仓库特有的改动，欢迎直接开 Issue，我们会认真看。

---

## 许可证

**GNU Affero General Public License v3.0 (AGPL-3.0)**

- ✅ 可以自由使用、修改、分发
- ✅ 可以用于商业用途
- ⚠️ 如果通过网络提供服务，必须公开源代码
- ⚠️ 修改后的版本必须使用相同许可证

完整文本见 [LICENSE](LICENSE)。
