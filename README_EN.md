<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agents" style="border-radius: 24px" />

# RikkaHub Agents

**🤖 On-device Agent tool** — 140+ device tools enabled on demand. Automate your phone in one sentence.

[![Release](https://img.shields.io/github/v/release/xiwangone/rikkahub-agents?color=2ea44f&label=Latest%20Release&logo=github)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Stars](https://img.shields.io/github/stars/xiwangone/rikkahub-agents?color=cb3837&label=Stars&logo=github)](https://github.com/xiwangone/rikkahub-agents)
[![Build](https://github.com/xiwangone/rikkahub-agents/actions/workflows/01-build.yml/badge.svg)](https://github.com/xiwangone/rikkahub-agents/actions/workflows/01-build.yml)
[![Downloads](https://img.shields.io/github/downloads/xiwangone/rikkahub-agents/total?color=blue&label=Downloads&logo=download)](https://github.com/xiwangone/rikkahub-agents/releases)
[![License](https://img.shields.io/github/license/xiwangone/rikkahub-agents?color=ff69b4&label=License)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/xiwangone/rikkahub-agents?color=yellow&label=Last%20Commit&logo=github)](https://github.com/xiwangone/rikkahub-agents/commits/master)

[![Download Latest](https://img.shields.io/badge/⬇️-Download%20Latest-2ea44f?style=for-the-badge&logo=android)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Reasonix Agents (in development)](https://img.shields.io/badge/🧪-Reasonix%20Agents%20in%20development-8b5cf6?style=for-the-badge)](https://github.com/xiwangone/reasonix-agents)

> 💡 **Also in this account**: [Reasonix Agents](https://github.com/xiwangone/reasonix-agents) — a native Android client for the Reasonix protocol (in development). Different positioning: this project treats the phone itself as the execution environment, while that one connects to your own Reasonix server.

> 🔧 **Prerequisite for Reasonix Agents**: it is a pure client — you **self-deploy the Reasonix server** (DeepSeek-Reasonix protocol) locally or on a server, then configure address / port / auth to connect. No cloud hosting — bring your own server resources.

> ✅ **Runs locally**: pick your own LLM provider (OpenAI-compatible or on-device). Conversations and data only travel between the provider you choose and your device.

[**简体中文**](README.md) | **English**

> <span style="color:red">**❗️❗️❗️ Note: RikkaHub Agents includes 140+ tools — enable on demand, avoid excessive resident resource usage!**</span>

</div>

---

## ✨ Highlights

- 📱 **Real on-device execution** — tap, swipe, type, screenshot, launch apps, read notifications, change settings: not "here is how", but "already done"
- 🗂️ **Files & workspaces** — find/read/edit/organize files; the **workspace sandbox** isolates reads and writes, bindable per conversation or sub-agent
- ⏰ **Workflows & scheduled jobs** — describe triggers and actions in natural language (19 triggers / 14 conditions); survives reboot and battery saver
- 🌐 **Built-in browser (AI-driven)** — handles cookie banners, fills forms, scrolls and reads pages; screenshots stream back at every step
- 🤖 **Sub-agents** — split long tasks into independent parallel runs; choose model, workspace and a **tool allow-list** (read-only + workspace write by default), with usage reported back
- 🧩 **Skills & MCP** — drop in a Markdown skill to gain new abilities; connect MCP servers to extend the tool surface
- 🔌 **SSH & Telegram remote control** — run commands, transfer files, tail logs from chat; approve actions while away from the phone
- 🔐 **Secure by default** — per-assistant toggles, per-call approval for mutating actions, HARDLINE blocking

---

## 🚨 Disclaimer

| Project | Link | Description |
|------|------|------|
| 🟡 **This Repo (AI-Maintained)** | https://github.com/xiwangone/rikkahub-agents | **AI auto-merges upstream + builds** |
| 🟣 **Reasonix Agents (sibling project)** | https://github.com/xiwangone/reasonix-agents | **Native Android client for Reasonix (in development)** |
| 🔵 **RikkaHub (Official)** | https://github.com/rikkahub/rikkahub | **Official upstream, source of this code** |
| 🟢 **ExTV/rikkahub-agent (Original Fork)** | https://github.com/ExTV/rikkahub-agent | **Original fork this repo is based on** |

> ### ⚠️ Usage Notice
>
> - **❌ NOT an official release** — Not published by the RikkaHub team
> - **❌ NOT the original release** — Not published by the ExTV developer
> - ✅ Code sources are trustworthy (official + original), auto-merged by AI and built with a fixed signing key
> - 💡 For issues, use the [official RikkaHub](https://github.com/rikkahub/rikkahub) or [original fork](https://github.com/ExTV/rikkahub-agent) first

---

## 🏗️ Architecture

### Modules

| Module | Responsibility |
|---|---|
| `app` | App shell: UI, settings, conversations, **tool assembly** and the generation loop |
| `ai` | LLM abstraction: providers, message/stream protocol, tool-call protocol |
| `workspace` | **Workspace sandbox**: file I/O, mounts, background tasks, command execution |
| `agent-tools` | Tool infrastructure: registry, schema, on-demand injection, error envelope |
| `common` / `material3` / `highlight` | Shared utilities, theme & components, syntax highlighting |
| `document` / `search` / `speech` / `web` | Document parsing, web search, speech, built-in browser |
| `local-llm` / `llama-cpp` / `videogen` | On-device inference, llama.cpp bindings, video generation |

### Key mechanisms

- **On-demand tool injection**: the tool surface is sorted by name for a stable request prefix, with a "cold tier" — locked tools send an empty schema, so 140+ tools never flood the context at once.
- **Approval & hardline**: per-assistant toggles; mutating calls are approved one by one; dangerous commands are blocked by HARDLINE. Unattended runtimes (scheduled jobs, sub-agents) use a separate channel.
- **Workspace isolation**: file operations stay inside the conversation's bound workspace; a sub-agent can bind its own workspace to avoid clobbering the main session.
- **Sub-agent runtime**: each dispatch is an **independent conversation** (config and model reusable, context not inherited), with timeout and step caps; read-only workspace by default, widen it via the allow-list.
- **Observability**: every generation reports token usage (including cache hits); sub-agent results and usage are posted back to the parent conversation.

---

### Data flow

```text
        ┌───────────────────────────────────────────────┐
        │            Your chosen LLM provider           │
        │      (OpenAI-compatible API / local LiteRT)   │
        └───────────────────────┬───────────────────────┘
                                │ chat / tool calls
        ┌───────────────────────▼───────────────────────┐
        │           RikkaHub Agents (this app)          │
        │  Assistant / chat ──▶ tool assembly (lazy +   │
        │        ▲              cold tier)              │
        │        │ result              │ approval · HARDLINE
        │  Scheduled / workflows   Workspace sandbox    │
        │  Sub-agents (parallel)   MCP / Skills         │
        └───────────────────────┬───────────────────────┘
                                │
        ┌───────────────────────▼───────────────────────┐
        │              Android device capabilities      │
        │  apps · notifications · files · media · sensors · SSH
        └───────────────────────────────────────────────┘
```

Unattended runtimes (scheduled jobs, sub-agents) use a separate channel: a narrowed tool surface and no per-call prompts, still bounded by HARDLINE.

---

## 🧱 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose (Material 3, BOM 2026.08.00) |
| Architecture | MVVM (ViewModel + Repository + Room / DataStore) |
| Networking | OkHttp 5.4.0 · Ktor 3.5.2 |
| Serialization | kotlinx.serialization |
| Images | Coil 3.5.0 |
| DI | Koin 4.2.2 |
| Coroutines | kotlinx.coroutines 1.11.0 |
| Build | Gradle 9.5.0 + AGP 9.3.1 + Version Catalog |
| Compile / target SDK | 37 (Android 15+); minimum 26 (Android 8.0) |

---

## 📁 Project Structure

```text
rikkahub-agents/
├── app/              # App shell: UI, settings, conversations, tool assembly, generation loop
├── ai/               # LLM abstraction: providers, message/stream protocol, tool-call protocol
├── workspace/        # Workspace sandbox: file I/O, mounts, background tasks, command execution
├── agent-tools/      # Tool infrastructure: registry, schema, on-demand injection, error envelope
├── common/           # Shared utilities and extensions
├── material3/ highlight/   # Theme & components, syntax highlighting
├── document/ search/ speech/ web/   # Document parsing, web search, speech, in-app browser
├── local-llm/ llama-cpp/ videogen/  # On-device inference, llama.cpp bindings, video generation
├── build-logic/      # Build convention plugins
└── locale-tui/ trace-cli/ web-ui/   # Localization, tracing and Web helper tools
```

---

## Overview

A fork that turns a native Android LLM chat client into a true on-device Agent: **140+ device tools**, AI-driven workflows, scheduled jobs, an in-app browser (AI-controlled), SSH, screen automation, file manager, music player, speech-to-text, downloadable local LLMs, and a remote Telegram Bot. All features default to OFF.

> *"Export my phone's to-do list as a Markdown file into the workspace."*
> *"Take a screenshot every 2 hours for 4 hours — let's see what I did this afternoon."*
> *"When I get a delivery notification, auto-screenshot and save to gallery."*
> *"When I connect to my work WiFi, disable the personal Telegram Bot."*
> *"Write a Python script in Termux to check the weather forecast on a schedule."*

Each is a one-sentence setup.

---

## Features

### Device Control
Tap, swipe, scroll, type, screenshot, open apps, adjust brightness/volume, send notifications, check battery/WiFi/signal/location/sensors, read contacts & SMS, send SMS, set wallpaper, read/write NFC, manage ZIP archives. **140+ tools**, all disabled by default.

### Workflows & Scheduled Jobs
**Workflows** — Describe triggers and actions in natural language: *"When I get home, silence the ringer."* 19 trigger types (WiFi, Bluetooth, headset, geofence, app launch, notification, time, charging, screen state, etc.) and 14 conditions.

**Scheduled Jobs** — *"Every Monday at 8 AM"*, *"Every 2 hours"*, *"Next Friday at 3 PM"*. Survives reboots and battery saver.

### Telegram Bot
Talk to your assistant from anywhere. Send questions, photos, PDFs, voice messages. AI pops up Yes/No buttons when confirmation is needed. Long messages auto-pack as downloadable files.

### In-App Browser
A real browser embedded in the app. AI auto-clicks cookie banners, fills search boxes, scrolls, reads page content. Each step streams a screenshot into the chat.

### File Manager
Find, read, save, copy, move, rename, delete files. *"Find all PDFs on my phone that mention 'invoice'"* — one sentence.

### SSH
Save server info. Run commands, upload files, pull backups, check disk, tail logs — all in chat. Works over WiFi and mobile data.

### Music & Media
Play music through Android's normal media controls: lock screen album art, headset buttons, all supported. Pause, resume, adjust volume — from chat or Telegram.

### Skills
Drop in Markdown Skill files and the AI gains new capabilities. Built-in: QR code generator, Wikipedia lookup, piano, interactive map, and more.

### Sub-Agents
Long tasks auto-split into parallel sub-agents, optionally using smaller, cheaper models. `/stop` cancels all sub-tasks at once.

### MCP Servers
Connect Model Context Protocol servers and the AI gets their tools.

### Notifications & External Triggers
AI can read, summarize, and forward notifications from specified apps. The whitelist is empty by default.

### Security & Privacy
Three layers of protection:
1. **Per-assistant toggles** — all tools start disabled
2. **Per-call approval** — modifying actions require approval before execution
3. **HARDLINE floor** — dangerous commands are unconditionally blocked

---

## Quick Start

### 1. Download APK
Get the latest APK from the **Releases** page on the right.

### 2. Install
Open the APK file, allow installation from unknown sources, and install.

### 3. Configure LLM
Open the app → **Settings → Providers → Add** → Choose OpenAI-compatible or the built-in LiteRT local model.

### 4. Enable Features (Optional)
**Settings → Assistants → Local Tools** → enable as needed.

### 5. Telegram Bot (Optional)
Get a token from [@BotFather](https://t.me/BotFather) and tell the assistant to configure it.

### 6. Build from Source (Optional)

Prerequisites: **JDK 17+** and Android SDK (**API 37**).

```bash
git clone https://github.com/xiwangone/rikkahub-agents.git
cd rikkahub-agents
./gradlew :app:assembleDebug          # output: app/build/outputs/apk/debug/
adb install app/build/outputs/apk/debug/app-debug.apk   # install to a connected device
```

---

## System Requirements

| | |
|---|---|
| **Minimum** | Android 8.0 (API 26) |
| **Compile / target SDK** | API 37 |
| **Architecture** | arm64-v8a or x86_64 |
| **Storage** | ~80 MB |

---

## Supported Languages

English, 简体中文, 繁體中文（香港）, 日本語, 한국어, Русский.

---

## Credits

This project exists because of others' work:

- **[RikkaHub](https://github.com/rikkahub/rikkahub)** — the official project. Most of the foundation — UI, model integrations, the tool framework — comes from here. Thanks to its authors and maintainers for the long-term effort.
- **[ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)** — the original fork this repo is built on, and we continue to merge improvements from both. Thanks to its author.
- And to the authors of every open-source library and tool those projects depend on.

This repo's tidying, merging and builds are done with AI assistance. **If you hit a problem, please try the official project first**; if it is specific to this repo's changes, open an issue here — we do read them.

---

## License

**GNU Affero General Public License v3.0 (AGPL-3.0)**

- ✅ Free to use, modify, and distribute
- ✅ Commercial use allowed
- ⚠️ If providing as a network service, source code must be made public
- ⚠️ Modified versions must use the same license

Full text in [LICENSE](LICENSE).
