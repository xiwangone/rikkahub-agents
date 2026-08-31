# RikkaHub 直连 Reasonix：思考/工具不折叠诊断报告

日期：2026-08-25

## 一、问题现象（用户截图对比）
- 截图1（当前 RikkaHub Agents 内）：思考过程与工具调用**平铺混杂**，无独立折叠卡片。
- 截图2（云端 AI / 目标样式）：
  - 思考过程 → 单独可折叠卡片「思考了 x 秒」；
  - 工具调用 → 单独可折叠卡片「调用工具 xxx」；
  - 两者彼此独立、可展开/收起。

## 二、结论（三层链路均正确，唯一根因在 UI 分组）
排查了完整链路（服务端 → 客户端 SSE 解析 → UI 渲染），三层本身都正确：

| 层 | 结论 |
|----|------|
| 服务端 `/events` | `agent.go:1851` 把 `ChunkReasoning` 映射为 `event.Reasoning` **独立事件**（kind=`reasoning`）；工具走 `tool_dispatch`/`tool_result`。**不混进 text**。 |
| 客户端 `SseEvent`/`ReasonixProvider` | `ReasonixModels.kt` 的 `SseEvent` 有 `reasoning` 字段；`ReasonixProvider.kt` 正确把 `reasoning`→`ReasoningStart/Delta/End`，`tool_dispatch`/`tool_result`→`ServerToolStart/End`。 |
| UI 渲染 | `ChatMessageReasoningStep`（思考卡片）、`ChatMessage.kt:643` ServerTool 卡片都齐全。 |

**根因：`ChatMessageCot.kt` 的 `groupMessageParts()`**

```kotlin
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    ...
    when (part) {
        is UIMessagePart.Reasoning -> currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
        is UIMessagePart.Tool -> currentThinkingSteps.add(ThinkingStep.ToolStep(part))
        else -> {           // ← ServerTool 落到这里！
            flushThinkingSteps()
            result.add(MessagePartBlock.ContentBlock(part, index))
        }
    }
}
```

关键：Reasonix 直连产生的工具是 **`UIMessagePart.ServerTool`**（服务端执行），不是 `UIMessagePart.Tool`（客户端执行）。
- `groupMessageParts()` 只认 `Reasoning` + `Tool`，**漏掉 `ServerTool`** → ServerTool 落入 `else` 分支。
- 于是 ServerTool 永远进不了 `ThinkingBlock`（Chain-of-Thought 折叠卡片），只会作为 `ContentBlock` 里的独立 `Surface` 卡片（平铺）。
- 但 `ThinkingStep.ToolStep` 也只接受 `UIMessagePart.Tool`，不接受 `ServerTool` → 即使进了分组也无法折叠。

所以「思考」能折叠（截图2 上部），」工具」无法折叠（与思考分离、平铺），视觉上就是「思考混在工具调用内」的割裂观感。

## 三、修复方案（两处，均已确认方向）
1. **`groupMessageParts()`**：让 `UIMessagePart.ServerTool` 也进入 `ThinkingBlock`，与 `Reasoning` 一起折叠。
2. **`ThinkingStep`**：新增 `ServerToolStep`（泛化 `ToolStep` → `ServerToolStep` 或并存），使 ServerTool 可渲染在 Chain-of-Thought 内，并复用/新增对应 UI 块。

> 说明：`ServerTool` 与 `Tool` 的 UI 呈现不同（`Tool` 有审批交互，`ServerTool` 纯展示执行结果）。需在 `ChatMessage.kt` 的工具 step 渲染分支里补 `ServerTool` 对应卡片，并保证折叠后功能正常。

## 四、改动影响面
- 仅 UI 分组与渲染逻辑，**不触及**服务端、SSE 解析、Provider。
- `UIMessagePart.ServerTool` 在 `ChatMessage.kt:643` 的 `ContentBlock` 渲染分支需迁移到 `ThinkingBlock` 内的 ServerTool step。
- 涉及文件：`ChatMessageCot.kt`、`ChatMessage.kt`（可能连带 `ChatMessageReasoning.kt` 或工具卡片组件）。

## 五、待执行
- [ ] 改 `groupMessageParts()`：ServerTool → ThinkingBlock
- [ ] `ThinkingStep` 支持 ServerTool
- [ ] `ChatMessage.kt` ThinkingBlock 渲染 ServerTool 卡片
- [ ] 复compile via 可信检查（本机无法 Gradle 构建，用静态核验 + git diff 复核）
- [ ] git 提交（不 push）
