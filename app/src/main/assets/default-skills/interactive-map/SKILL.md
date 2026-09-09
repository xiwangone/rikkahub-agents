---
name: interactive-map
description: 为某个地点展示可交互的 Google 地图嵌入。返回一个聊天界面可渲染的 webview 地址。
compatibility: js
auto_load: false
---

# 交互地图

## 示例

- "在地图上显示[某个地点]"
- "查找[某个地点]的交互地图"

## 操作说明

用以下精确参数调用 `run_js` 工具：

- skill_name: `interactive-map`
- script: `scripts/index.html`
- data: 一个包含以下字段的 JSON 字符串
  - location: 要在地图上显示的地点。

## 来源

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，Apache-2.0 许可。原始版权归 Google LLC 所有。
