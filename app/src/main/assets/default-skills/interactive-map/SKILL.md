---
name: interactive-map
description: 为某个地点展示可交互的 Google 地图嵌入。返回一个聊天界面可渲染的 webview 地址。
compatibility: js
auto_load: false
---

# Interactive map

## Examples

- "Show [a place] on interactive map"
- "Find [a place] on interactive map"

## Instructions

Call the `run_js` tool with the following exact parameters:

- skill_name: `interactive-map`
- script: `scripts/index.html`
- data: A JSON string with the following field
  - location: The location to show on the map.

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
