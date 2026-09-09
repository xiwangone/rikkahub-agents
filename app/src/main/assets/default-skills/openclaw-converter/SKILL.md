---
name: openclaw-converter
description: 把 OpenClaw 技能（来自 ClawHub 或原始 markdown）转换成 RikkaHub 兼容的技能。只要用户给你一个 OpenClaw 技能地址、指向 OpenClaw 技能的 GitHub 链接，或要转换的 OpenClaw 技能 markdown，就用它。
auto_load: false
---

# OpenClaw → RikkaHub 技能转换器

把 OpenClaw 技能（来自 ClawHub 或原始 markdown）转换成 RikkaHub 兼容的技能。只要用户给你一个 OpenClaw 技能地址、指向 OpenClaw 技能的 GitHub 链接，或要转换的 OpenClaw 技能 markdown，就应用本技能。

## 如何获取源

1. ClawHub 地址（`https://clawhub.ai/<owner>/<slug>`）：页面是 JS 单页应用，普通 fetch 返回空。改用应用内浏览器：`browser_open` 打开地址，等内容渲染，提取渲染后的 SKILL.md 文本（上限约 16000 字符），然后关闭浏览器。
2. GitHub raw 地址：如果知道仓库，试 `https://raw.githubusercontent.com/<owner>/<repo>/main/SKILL.md`。有些技能在 `https://github.com/<owner>/<repo>/tree/main/skills/<name>` 下。
3. 用户给的原始 markdown：如果用户直接粘贴了 SKILL.md，原样使用。

## 转换规则

### 路径

| OpenClaw | RikkaHub |
|---|---|
| `~/.openclaw/workspace/` | `~/` |
| `~/.openclaw/workspace/.learnings/` | `~/learnings/` |
| `~/.openclaw/skills/<name>/` | 通过 `skill_install_from_text` 安装；文件放到 `~/` |
| 任意项目根的 `.learnings/` | `~/learnings/` |

### 工具引用

| OpenClaw 工具 / 概念 | RikkaHub 对应 | 备注 |
|---|---|---|
| `sessions_list` | 不可用 | 删除该部分或注明限制 |
| `sessions_history` | 不可用 | 删除该部分或注明限制 |
| `sessions_send` | `telegram_send_message` | 跨会话变成跨设备通知 |
| `sessions_spawn` | `subagent_dispatch` | 仅当用户已启用子代理时 |
| `clawdhub install <name>` | `skill_install_from_url` 或 `skill_install_from_text` | 替换安装说明 |
| `openclaw hooks enable` | `schedule_job` 或 `workflow_create` | Hooks 变成定时/工作流自动化 |
| `memory`（OpenClaw） | `memory_tool`（create/edit/delete） | 概念相同，API 不同 |
| Shell 命令 | `termux_run_command` | root 命令加 `su -c` 前缀 |
| 文件读写 | `write_text_file`、`read_file`、`list_files` | 路径相同，做适配 |

### 升级目标

| OpenClaw 目标 | RikkaHub 目标 |
|---|---|
| `CLAUDE.md` | 项目级文件（如果项目存在）或 `~/learnings/` |
| `AGENTS.md` | RikkaHub 技能文件或 `memory_tool` |
| `SOUL.md` | 用 `memory_tool` 存行为模式 |
| `TOOLS.md` | 更新相关技能的内容 |
| `MEMORY.md` | `memory_tool` |
| `.github/copilot-instructions.md` | 保留原样，供 GitHub Copilot 用户使用 |

### 要删除或替换的章节

- "OpenClaw Setup" / "OpenClaw Workspace Structure"：替换为 RikkaHub 工作区路径。
- "Inter-Session Communication"：删除 `sessions_*` 工具；如果概念有价值，建议用 `telegram_send_message` 作为跨会话通知的变通方案。
- "Hook Integration" / "Enable Hook"：替换为 RikkaHub 工作流（`workflow_create`）或定时任务（`schedule_job`）。
- "Claude Code / Codex Setup"：整体删除（那是别的代理平台）。
- 通过 `clawdhub` 或 `git clone` 安装：替换为"用 `skill_install_from_url` 或 `skill_install_from_text` 安装"以及 RikkaHub 兼容路径。

### 原样保留的章节

日志格式（LEARNINGS.md / ERRORS.md 结构）、触发条件、优先级指南、领域标签、最佳实践（除非引用了已删除的工具）、以及核心工作流 / 速查表。

### 格式头

RikkaHub 技能使用简单的 frontmatter 块：`name`、`description` 和 `auto_load`。剥离任何 OpenClaw YAML frontmatter，用第一个 `# Heading` 作为标题。
