---
name: calculate-hash
description: 通过 WebView 的 WebCrypto API 计算给定文本的 SHA-1 哈希。
compatibility: js
auto_load: false
---

# Calculate hash

This skill calculates the hash of a given text.

## Examples

* "Calculate hash of..."
* "What is the hash of..."

## Instructions

Call the `run_js` tool with the following exact parameters:

- skill_name: `calculate-hash`
- script: `scripts/index.html`
- data: A JSON string with the following field
  - text: the text to calculate hash for

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
