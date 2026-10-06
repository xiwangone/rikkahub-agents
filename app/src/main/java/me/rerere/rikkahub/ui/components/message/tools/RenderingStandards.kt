package me.rerere.rikkahub.ui.components.message.tools

/**
 * 工具渲染分层标准、一致性规范与性能红线。
 *
 * 本文件为纯文档：把评审约定的量化标准写进代码注释，后续新增渲染器以此为依据。
 * 行为常量（截断行数、折叠阈值等）的唯一真相在 [ToolUI.kt]，此处只引用不重复定义。
 *
 * ## 一、渲染分层：Default vs 专用渲染器
 *
 * Default（[ToolUIRegistry] 的 fallback）按输出特征分四分支给内联摘要：
 * ① 终端型（`stdout`/`exit_code` → 前 8 行，非零退出码标 `[exit N]`）；
 * ② 列表型（40 个 `GENERIC_LIST_KEYS` 命中或单字段数组兜底 → 逐条取标签，超 8 条折叠）；
 * ③ 长文本型（>200 字符字段 → 取最长字段前 8 行）；
 * ④ 键值型（标量小对象 → 逐行 `k: v`，值截 120 字符，最多 8 行）。
 *
 * 新建专用渲染器的启用标准（满足任一）：
 * 1. **标题信息熵**：核心信息（坐标/路径/包名/命令/收件人）可从入参或输出稳定提取，
 *    且该工具单会话可见 ≥3 次（以 `ToolUsageTracker` 统计为准）；
 * 2. **摘要纠错**：Default 对该工具产生误导性摘要（如显示数字 ID 而非可读标签）；
 * 3. **形态特化**：输出含图片、会话状态、结构化业务对象，文本摘要无法表达。
 *
 * 硬约束：单批新增类 ≤10；同类输出形态必须复用同一渲染器（多 key 挂同一实例）；
 * 一个类一个文件，文件名等于类名，放入 `tools/generic/`（通用）/`tools/builtin/`/
 * `tools/workspace/`/`tools/other/`；禁止回填 `BuiltinToolUIs.kt`。
 *
 * ## 二、标题 / 摘要 / 详情页三件套的信息分工
 *
 * - 标题（一行）：动作 + 核心对象，如 `点击 (520,1080)`、`发送短信 → 张三`。
 *   禁止只显示工具名；对象截断（路径取文件名、命令截 60 字、坐标保留 2 位小数）。
 * - 摘要（≤8 行）：结果要点。成功时标题已说清则无摘要；失败必须给 `reason`。
 * - 详情页：入参 JSON + 输出 JSON + 可读摘要并存（证据完整）。
 *
 * 图标：`icon()` 返回与工具域相关的 `HugeIcons`，禁止统一用默认图标；
 * 新增图标需核对 hugeicons jar 中存在。
 *
 * 文案：新增用户可见字符串 7 语言一次补齐
 * （en、zh-CN、ar、zh-Hant、ja、ko-rKR、ru），单引号转义，key 位置对齐。
 *
 * ## 三、性能红线
 *
 * - 单条工具消息首次渲染（含摘要计算）预算 16ms（1 帧）；
 *   `ToolUIContext` 预解析入参与输出，各渲染器不得重复 `parseToJsonElement`。
 * - 摘要计算必须是纯函数：无 IO、无网络、无图片解码；耗时操作走异步组件
 *   （如 `ZoomableAsyncImage` 按需加载原图，摘要行只放缩略图）。
 * - 列表摘要只取前 8 条并标注总数；详情页大数据量列表（>200 条）暂全量，
 *   虚拟化待排期（见统一设计文档 P1）。
 * - `summaryForContent` 返回 null = 不给摘要，调用方不得以此为由抛异常或吞输出。
 *
 * ## 四、与 AI 侧的协作边界
 *
 * 渲染层只消费 `ToolUIContext`（预解析的入参与输出），不介入工具调用路径；
 * 工具描述/schema 裁剪由 `ToolSurfacePolicy`（三档 HOT/WARM/COLD）负责，
 * 错误形状由 `ToolErrors.envelope` 统一；渲染层不重复造轮子。
 */
object RenderingStandards {
    // 纯文档载体，无行为。量化阈值的唯一真相在 ToolUI.kt（GENERIC_SUMMARY_MAX_LINES 等）。
}
