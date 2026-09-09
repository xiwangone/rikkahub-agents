---
name: virtual-piano
description: 在 webview 里展示一台可弹奏的 88 键虚拟钢琴，音符由合成音频生成。
compatibility: js
auto_load: false
---

# 虚拟钢琴

一台可弹奏、可横向滚动的虚拟钢琴键盘，使用 Web Audio 合成音符。

## 文件
- `scripts/index.html`：本地入口，加载 `scripts/index.js`。
- `scripts/index.js`：返回指向 `assets/` 下本地 UI 的 webview 地址 `ui.html?v=<时间戳>`。
- `assets/ui.html`：钢琴键盘 UI（3D、88 键、横向滚动）；音符由 WebAudio 合成。

## 触发词
- "打开虚拟钢琴"
- "弹钢琴"
- "我想弹钢琴"
- "给我看一个钢琴键盘"

## 操作说明

用以下参数调用 `run_js` 工具：
- skill_name: `virtual-piano`
- script: `scripts/index.html`
- data: 一个 JSON 字符串（任意内容，本技能会忽略它）。

技能返回一个带相对地址（`ui.html?v=...`）的 `webview` 结果。聊天界面决定如何渲染内嵌的 webview 返回；如果不能内嵌 iframe，则回退为可点击的链接。

主页：<https://github.com/google-ai-edge/gallery/tree/main/skills/featured/virtual-piano>

## 来源

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，Apache-2.0 许可。原始版权归 Google LLC 所有。
