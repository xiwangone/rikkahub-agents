---
name: text-spinner
description: 在 webview 里渲染一个 3D 旋转的文字标签。返回一个聊天界面可嵌入的相对 webview 地址。
compatibility: js
auto_load: false
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skill_name: `text-spinner`
- script: `scripts/index.html`
- data: A JSON string with the following fields:
  - label: The text string to spin on my head.

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
