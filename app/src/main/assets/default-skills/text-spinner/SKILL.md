---
name: text-spinner
description: 在 webview 里渲染一个 3D 旋转的文字标签。返回一个聊天界面可嵌入的相对 webview 地址。
compatibility: js
auto_load: false
---

# 操作说明

你必须用以下精确参数调用 `run_js` 工具：

- skill_name: `text-spinner`
- script: `scripts/index.html`
- data: 一个包含以下字段的 JSON 字符串：
  - label: 要旋转显示的文字字符串。

## 来源

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，Apache-2.0 许可。原始版权归 Google LLC 所有。
