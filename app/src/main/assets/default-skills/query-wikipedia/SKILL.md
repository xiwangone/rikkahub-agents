---
name: query-wikipedia
description: 对维基百科做模糊搜索，返回词条引言与信息框摘要。需要联网。
compatibility: js
auto_load: false
---

# 查询维基

## 操作说明

用 `script: scripts/index.html` 调用 `run_js` 工具，`data` 传入包含以下字段的 JSON 字符串：
- **topic**: 必填。只提取主要的实体、人物或事件（例如 "2026 Oscars"、"Albert Einstein"）。你必须删除所有具体的提问细节、动作词或会话性文字（例如不要包含 "winner"、"best picture"、"who won"、"history of" 这类词）。搜索宽泛主题，让工具能返回主词条。
- **lang**: 必填。2 字母语言代码。该代码必须与你在 `topic` 字段中给出的关键词语言一致。使用标准代码，如 "en"（英语）、"es"（西班牙语）、"zh"（中文）、"fr"（法语）、"de"（德语）、"ja"（日语）、"ko"（韩语）、"it"（意大利语）、"pt"（葡萄牙语）、"ru"（俄语）、"ar"（阿拉伯语）、"hi"（印地语）。

**约束：**
- 提供简洁的摘要（1-3 个完整句子）以节省上下文。始终确保回复以完整的句子结尾。你的回复必须使用与用户原始提问相同的语言。
- 对于周期性事件或时效性事实，查询具体的那一届（例如 "2026 Oscars"）。如果用户省略年份，默认使用当前年份。
- 如果在提取内容中没有找到用户问题的确切答案，简要说明这一点，然后主动提供文本中*确实*找到的相关信息。

## 来源

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，Apache-2.0 许可。原始版权归 Google LLC 所有。
