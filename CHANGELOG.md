# Changelog — RikkaHub Agents

> 基于 `xiwangone/rikkahub-agents` fork，从 `ExTV/rikkahub-agent` 分叉，随后合入官方 `rikkahub/rikkahub v2.4.3`。
> 只保留时间线的**功能改动**与**修复成功**记录。~~删除线~~ = 已回退或删除的变更。

---

## 2026-08-10

- **修复** Room 迁移链断裂导致 2.45.6 闪退 — v29 迁移误判「v28 未发布」只写 `AutoMigration(27→29)`，而 v28 已随 2.45.5（Vault MVP）发布；已装 2.45.5 的用户升级 2.45.6 时 Room 找不到 28→29 迁移路径抛 IllegalStateException。新增手写 `Migration_28_29`（建 `vault_audit_log` 表 + 2 索引），与 27→29 并存，Room 按设备版本自动选路径
- **chore** versionName 2.45.6 → 2.45.7（versionCode 176）

## 2026-08-09

- **功能** 密钥库凭证体系 MVP（Credential Vault）— 设置页「Credential Vault」入口 + 密钥列表三级页（分组展示/小眼睛显隐/新增/编辑/删除）+ SAF 文件导入 load-creds.sh + AES-GCM 密文入库（AndroidKeyStore 托管密钥）（`3b933718` / `bea70707`）
- **修复** VaultCredentialDao `ORDER BY group` — `group` 为 SQLite 保留字，KSP 编译失败；先后尝试反引号转义（KSP 报 No property named value），最终改为非保留字列名 `grp`（`09cf384d` / `bea70707`）
- **功能** 安全凭证库统一命名 + 多语言（中/繁/英）+ 设置入口移至模型与服务第一项 + 页面文本全部资源化（`fd230859`）
- **功能** 加回 OCR 模型设置与提示词选项（对照官方，OcrPrompt.kt + SettingModelPage/SettingModelPromptPage）+ 硬编码资源化第一批（AutoTaskDialog/ChatInput/NerdLine 11 处）（`07a084ad`）
- **功能** Doctor 诊断页资源化 — label/FixAction 改 @StringRes + 三语言资源包 150 key（`bad1417e`）
- **功能** Vault 指纹门禁 — 查看凭证明文前 BiometricPrompt 验证（复用 ToolHostActivity 承载）+ 开关偏好（`dc2a0e04`）
- **功能** Vault 导出 — 口令加密 .vault 包（PBKDF2-HMAC-SHA256 20 万次 + AES-GCM）+ SAF 保存 + 导出前指纹门禁（`bc9e4590`）
- **功能** Vault 密钥分组下拉选择/新建 + 加密备份恢复（.vault 含分组）（`33f27bb7`）
- **功能** 密钥调用审计日志 — 每次查看/导出/备份留痕 + 双上限清理（500 条 / 30 天）（`58865775`）
- **功能** 日志页三级分类 — 请求/文本/应用 Tab 切换 + 文本搜索 + 入口更名「日志」（`e2e5a870`）
- **功能** Vault 阶段 2 — 会话 token（HMAC-SHA256，30 分钟 TTL）+ 解密 API（POST /api/vault/decrypt）+ 解锁会话 UI（`b375d990`）
- **功能** 累计 token 行双指标 — 本轮命中率 + 平均命中率（UI 中文/复制英文）（`bc45e614` / `c6b695e5`）
- **功能** Web 设置页分两区（正常 Web + Web 桥全局配置）+ 提供商编辑页隐藏类型切换（去掉其他配置里的 Reasonix 选项）（`d0db6eac`）
- **chore** 清理 90 个孤儿资源 key（doctor_ detail/工具名 + vault_/log_，三语言同步）+ 更新 3 处过时注释（`5667946e`）
- **chore** versionName 2.45.3 → 2.45.5（versionCode 174）

## 2026-08-06

- **功能** 缓存命中优化（目标 50%→90%+）：`limitContext` 改「保前缀、只从末尾回收」（`c378dd4`）；tool schema 规范化排序保证跨轮前缀字节稳定（`8fd0645` / `7613917`）
- **修复** tool 规范化排序编译错误 — `BuiltInTools`(sealed class) 用 `simpleName`、`UIMessagePart.Tool` 用 `toolName` 排序，修复 `:ai:compileReleaseKotlin` 失败（`13bed253`）
- **chore** versionName 同步 2.4.5 → 2.45.2（与 release 对齐，`2f75b3d`）

## 2026-08-05

- **功能** P0 凭证加密 — providers 含 apiKey/私钥以 AES-GCM 密文入库（AndroidKeyStore），读路径解密失败回退原文平滑升级；备份规则注释同步（`5404e61`）
- **功能** 自动压缩上下文重构 — 双模式（百分比基准×阈值 / token 消耗累计上限）+ 会话级触发点 + 确认弹窗（`d5b3ce6`）
- **功能** 自动压缩生成中不弹窗、延后到对话结束再弹（wait conversationJob 完成，最多 5 分钟防死锁）（`0112d83`）
- **重构** 工具输出常量统一 1000 进制（16K/4K/5K）+ tool_outputs 补清理机制（保留最近 50 个，防无限累积）（`a814e64`）
- **重构** 工具输出限制单位统一为 1000 进制（/1000、×1000），同步三语言文案 5 = 5000 字符（`af5a7df`）
- **修复** AutoCompressDialog 百分比输入框 currentThreshold 改为 String 状态，修复删空不更新导致 5→50→500 追加；确认时 toIntOrNull 钳制 50-95 默认 80（`83a1831`）
- **修复** ToolOutputDialog 输入框 currentKB 改为 String 状态，修复删空不更新导致 5→50→500 追加（`cd520b9`）
- **文档** 首页互链 Reasonix Agents（开发中）（`055b740`）
- **文档** README_EN 互链 Reasonix Agents（开发中）保持中英一致（`74542fc`）
- **文档** 重要声明调整（本项目+并列在上、上游在下、保留全部上游）+ 中英一致（`a3517bd` / `902d66d`）
- **文档** 首页关于描述更新（AI维护迭代文案+使用前提+红色注）+ 中英同步（`d3cedda`）

## 2026-08-04

- **功能** 新增对话 token 上限设置 — 达到上限自动压缩；依赖自动压缩开关，与阈值并列可单独设置（`b9bad775` / `7dbb1ac6`）
- **修复** 自动压缩 token 估算改为 1:1 — 原 `/4` 低估中文，长会话永不触发压缩（`c05ee0d5`）
- **修复** 删除设置界面重复的对话 token 上限输入框 — 保留开关内与阈值并列的一个（`46edb765`）
- **修复** 补 `LaunchedEffect` import — 修复 CI 编译失败 Unresolved reference（`84eb4a7f`）
- **修复** 模型多选删除移到顶栏 + 工具图片按视觉能力降级 + OCR 提示区分本地/AI（`ce89d580`）
- **修复** `reasoning_effort` OFF 时不传 — ResponseAPI + opencode 分支，修复 Console Go 400（`3d99a936`）
- **文档** AI 自动维护改为 AI 协助维护 + 去掉固定签名表述（`dd5d0b6d` / `77c62769`）

## 2026-08-03

- **修复** OCR 提示仅在真正需要识别（未缓存）时显示，避免历史图片每次触发"正在识别图片"（`e8869ddb`）
- **修复** OCR 本地识别支持 `content://` 路径 + Codex OFF 不传 `reasoning_effort=none`（`7d43d0ef`）
- **修复** OCR content:// 改用 `BitmapFactory` 解码（`fromInputStream` 不存在，`f9bc39ac`）
- **修复** OCR content:// 分支 `fromBitmap` 缺 `rotationDegrees` 参数导致编译失败（`7f6ebb88`）
- **功能** 累计统计复制按钮 + 本地 OCR 开关（默认开）+ LiteRT 本地导入（`de355aa3`）
- **功能** 提供商双语描述 + 恢复本地 LLM 按钮 + 移除 LiteRt 添加选项 + 文件管理全选批量删除（`69ca514d`）
- **修复** 删除 `TokenBudgetTracker` 重复导入，修复 CI 编译错误（`5714ba69`）
- **修复** 删除误提交的 `review.txt`（reasonix 审查产物，`11b2533e`）

## 2026-08-02

- **功能** 累计 Token 统计 — 对话消息统计区域新增会话级累计行（↑输入 ↓输出 + 命中缓存），各带复制图标
- **功能** 工具输出限制开关 — 设置页新增 `toolOutputEnabled` 开关（ON/OFF）
- **功能** 统计条 UI 中文化 — 消息统计条标签改中文（输入/输出/命中缓存）
- **功能** 自动压缩/工具输出对话框 — 点击弹出设置对话框
- **修复** 工具输出默认值 + 单位修正 — 默认 4KB→5KB；修复 KB/byte 单位混淆
- **修复** 累计 Token 统计 UI 收尾 — `sessionTotals` 移至 `ChatPageContent` 确保输入栏可见
- **修复** 编译错误修复 — 补 `SettingModelPromptPage` 缺失 import

## 2026-08-01

- **功能** 对话底部累计 Token 统计 — 聊天输入栏底部新增会话级累计统计行（↑输入 ↓输出 + 命中缓存）
- **功能** 统计条 UI 中文化 — 消息统计条标签改中文
- **修复** 设置页 Kotlin 编译错误 — 补缺失 import + 字符串资源

## 2026-07-31

- **功能** 本地 OCR 优先 — `OcrTransformer` 新增 ML Kit 本地识别（中文+拉丁合并去重，覆盖中英日韩），失败才回退 AI OCR
- **功能** MCP Header 密钥显隐 — 新建 MCP 请求头 Value 输入框支持小眼睛显隐
- **功能** 模型多选删除 — Provider 模型列表左滑多选/全选/批量删除，保留长按拖拽排序
- **修复** `setting_provider_page_select_all` 字符串重复导致编译失败

## 2026-07-30

- **重构** 侧边栏精简 — 移除 Sparkles 菜单中的导出/分享码/导入
- **重构** 多选导出面板 — JSON 导出 + 导入移到 ChatExportSheet
- **重构** 移除分享码功能
- **功能** 导出会话为 JSON — 导出为可导入恢复的 JSON 文件
- **功能** 导入会话 — 侧边栏抽屉新增导入按钮
- **功能** 浏览器增强 — 地址栏「在浏览器打开」「分享链接」
- **功能** AI 后台浏览 — 默认 headless 模式，AI 后台浏览网页
- **修复** 导出面板编译错误 — import 路径 / `koinInject` 上下文 / 包名 `data.chat`→`data.repository`
- **修复** `FileImport` / `Download01` 缺失 import

## 2026-07-29

- **修复** 导出面板编译错误 — import 路径 / `koinInject` 上下文 / 包名
- **修复** `FileImport` / `Download01` 缺失 import
- ~~**功能** DuckDuckGo + AnySearch 免费搜索供应商（后撤回）~~
- **修复** 剩余 4 处字符串拼接和中文引号问题
- **修复** `DoctorChecks.kt` 字符串拼接符位置错误
- **修复** 字符串中单引号转义
- **修复** 签名 keystore 修复（多次迭代，最终使用 Secrets 固定签名 keystore）
- **修复** 汉化硬编码组件（诊断工具/Emoji/聊天列表/供应商描述/生物识别）+ 补全简繁中翻译 104+5 条对齐 1879/1879
- **修复** 字符串 `&gt;&gt;` 导致 AAPT2 编译失败

## 2026-07-28

- **初始** 从 `ExTV/rikkahub-agent` 分叉 + 合入官方 `rikkahub/rikkahub v2.4.3`
- **修复** 29 个文件冲突（strings.xml 汉化保留）
- **品牌** emoji README, CI 修复, 本地化门控

## 2026-07-27

- **重构** 重写合并策略 — 官方为主上游 + 智能冲突解决（`merge-upstream.yml`）
- **修复** 工作流：`GITHUB_OUTPUT` 格式 / 补 `workflows:write` 权限 / push 前删上游 workflow / `rm` 顺序
- **品牌** README 重塑 — SVG logo + 中文 i18n + 致谢 RikkaHub 官方 & ExTV
- **品牌** 更新 App 图标（现代 AI 主题）+ fork 专属主页描述


## 上游历史（2025-03 ~ 2026-07）

### 核心功能（来自官方 rikkahub/rikkahub + ExTV/rikkahub-agent）

| 类别 | 功能 | 来源 |
|------|------|------|
| **AI 引擎** | OpenAI / Claude / Gemini / DeepSeek / Grok / Ollama 等全部主流模型 | 上游 |
| **Agent 模式** | Function Calling, 工具调用, 本地工具系统 | 上游 |
| **工作区** | 沙箱执行环境, 文件读写, Shell 命令 | 上游 |
| **MCP 协议** | MCP 客户端, OAuth 2.1, 工具发现 | 上游 |
| **Skills 系统** | 技能安装/管理/沙箱 WebView | 上游 |
| **搜索** | Bing / DuckDuckGo / CustomJS / Exa / Grok / Jina / Brave / Tavily / SearXNG 等 | 上游 |
| **语音** | ElevenLabs TTS, MIMO TTS, Fish Audio, 系统 TTS, ASR 多引擎 | ExTV |
| **浏览器** | 17 个浏览器工具（读写/截图/提取文本/JS 执行）, 前台 + 后台模式 | 上游 |
| **Telegram Bot** | 完整的 Telegram 集成, 远程代理 | ExTV |
| **备份** | WebDAV / S3 备份恢复, Chatbox 导入 | 上游 |
| **导出** | Markdown / 图片导出 → 新增 JSON 导出（本 fork） | 本 fork |
| **文件夹** | 会话分组管理 | ExTV |