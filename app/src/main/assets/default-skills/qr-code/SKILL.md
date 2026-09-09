---
name: qr-code
description: 为给定地址生成 512x512 的二维码 PNG，返回 base64 编码的图片。完全离线可用。
compatibility: js
auto_load: false
---

# 操作说明

你必须用以下精确参数调用 `run_js` 工具：

- skill_name: `qr-code`
- script: `scripts/index.html`
- data: 一个包含以下字段的 JSON 字符串：
  - url: 字符串 - 要生成二维码的地址

## 来源

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，Apache-2.0 许可。原始版权归 Google LLC 所有。
