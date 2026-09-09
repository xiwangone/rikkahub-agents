---
name: qr-code
description: 为给定地址生成 512x512 的二维码 PNG，返回 base64 编码的图片。完全离线可用。
compatibility: js
auto_load: false
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skill_name: `qr-code`
- script: `scripts/index.html`
- data: A JSON string with the following fields:
  - url: String - the url to create QR code for

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
