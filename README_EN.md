<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agents" style="border-radius: 24px" />

# RikkaHub Agents

**🤖 On-device Agent tool, iteratively maintained by AI** — economical & better cross-session experience via prompt constraints, memory-pointer indexing and workspace file details. More agent modes to explore. (Auto-compact context working, Reasonix collaboration complete, local OCR still in progress...)

[![Release](https://img.shields.io/github/v/release/xiwangone/rikkahub-agents?color=2ea44f&label=Latest%20Release&logo=github)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Stars](https://img.shields.io/github/stars/xiwangone/rikkahub-agents?color=cb3837&label=Stars&logo=github)](https://github.com/xiwangone/rikkahub-agents)
[![Downloads](https://img.shields.io/github/downloads/xiwangone/rikkahub-agents/total?color=blue&label=Downloads&logo=download)](https://github.com/xiwangone/rikkahub-agents/releases)
[![License](https://img.shields.io/github/license/xiwangone/rikkahub-agents?color=ff69b4&label=License)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/xiwangone/rikkahub-agents?color=yellow&label=Last%20Commit&logo=github)](https://github.com/xiwangone/rikkahub-agents/commits/master)

[![Download Latest](https://img.shields.io/badge/⬇️-Download%20Latest-2ea44f?style=for-the-badge&logo=android)](https://github.com/xiwangone/rikkahub-agents/releases/latest)
[![Reasonix Agents (in development)](https://img.shields.io/badge/🧪-Reasonix%20Agents%20in%20development-8b5cf6?style=for-the-badge)](https://github.com/xiwangone/reasonix-agents)

**🤝 Related project: [Reasonix Agents](https://github.com/xiwangone/reasonix-agents)** — Native Android client for Reasonix · AI coding assistant (in development)

> 🔧 **Prerequisite**: Reasonix Agents is a pure client — **self-deploy the Reasonix server** (DeepSeek-Reasonix protocol) locally or on a server; configure address/port/auth to connect. No cloud hosting — bring your own server resources.

[**简体中文**](README.md) | **English**

> <span style="color:red">**❗️❗️❗️ Note: RikkaHub Agents includes 80+ tools — enable on demand, avoid excessive resident resource usage!**</span>

> ⚠️ **⚠️⚠️ IMPORTANT WARNING (2026-08-10)** ⚠️⚠️
>
> **Users on 2.45.5: upgrade directly to 2.45.7 — do NOT install 2.45.6!**
>
> `2.45.6` has a database-migration defect (crashes on upgrade); fixed and released as **2.45.7**. If you are on 2.45.5 (or earlier), **install 2.45.7 directly** ([Download latest](https://github.com/xiwangone/rikkahub-agents/releases/latest)). If you already installed 2.45.6, **upgrade to 2.45.7 immediately** to recover (data is preserved).

</div>

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

## Overview

A fork that turns a native Android LLM chat client into a true on-device Agent: **80+ device tools**, AI-driven workflows, scheduled jobs, an in-app browser (AI-controlled), SSH, screen automation, file manager, music player, speech-to-text, downloadable local LLMs, and a remote Telegram Bot. All features default to OFF.

> *"Export my phone's to-do list as a Markdown file into the workspace."*
> *"Take a screenshot every 2 hours for 4 hours — let's see what I did this afternoon."*
> *"When I get a delivery notification, auto-screenshot and save to gallery."*
> *"When I connect to my work WiFi, disable the personal Telegram Bot."*
> *"Write a Python script in Termux to check the weather forecast on a schedule."*

Each is a one-sentence setup.

---

## Features

### Device Control
Tap, swipe, scroll, type, screenshot, open apps, adjust brightness/volume, send notifications, check battery/WiFi/signal/location/sensors, read contacts & SMS, send SMS, set wallpaper, read/write NFC, manage ZIP archives. **80+ tools**, all disabled by default.

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

---

## System Requirements

| | |
|---|---|
| **Architecture** | arm64 or x86_64 |
| **Android** | 8.0+ (API 26) |
| **Storage** | ~80 MB |

---

## Supported Languages

English, 简体中文, 繁體中文（香港）, 日本語, 한국어, Русский.

---

## Credits

- **[RikkaHub (Official)](https://github.com/rikkahub/rikkahub)** — Upstream project
- **[ExTV/rikkahub-agent (Original Fork)](https://github.com/ExTV/rikkahub-agent)** — Original fork

---

## License

**GNU Affero General Public License v3.0 (AGPL-3.0)**

- ✅ Free to use, modify, and distribute
- ✅ Commercial use allowed
- ⚠️ If providing as a network service, source code must be made public
- ⚠️ Modified versions must use the same license

Full text in [LICENSE](LICENSE).
