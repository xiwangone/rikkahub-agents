---
name: calculate-hash
description: 通过 WebView 的 WebCrypto API 计算给定文本的 SHA-1 哈希。
compatibility: js
auto_load: false
---

# 计算哈希

本技能计算给定文本的哈希。

## 示例

* "计算……的哈希"
* "什么是……的哈希"

## 操作说明

用以下精确参数调用 `run_js` 工具：

- skill_name: `calculate-hash`
- script: `scripts/index.html`
- data: 一个包含以下字段的 JSON 字符串
  - text: 要计算哈希的文本

## 来源

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，Apache-2.0 许可。原始版权归 Google LLC 所有。
