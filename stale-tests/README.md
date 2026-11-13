# stale-tests

Unit tests quarantined on 2026-09-10 because the test source set did NOT compile
(722 errors), which blocked `:app:testDebugUnitTest` entirely. They came from an
abandoned upstream merge (2.4.10 / 2.5.0, i.e. the ExTV/rikkahub-agent fork) and
referenced feature code the local tree did not have.

Nothing here is compiled or executed. When a feature is ported, move its test back
with `git mv` into `app/src/test/java/...`, make it compile against the real local
API, and keep `:app:compileDebugUnitTestKotlin` green.

## 已回收（2026-11-13，fix/stale-tests 分支）

| 测试 | 处理 |
|---|---|
| WorkspaceTreeTest | 原样回收（tree()/maxListEntries 已在） |
| DoctorReportTest | DoctorReport.format 需要 Context，改走 Robolectric |
| CronJobSchedulerTest / CronJobWorkerHistoryRetentionTest / CronJobWorkerRunLlmTimeoutTest / ScheduleJobToolValidationTest | 移植 terminalJobUpdate、historyRetentionFor/MAX_HISTORY_RETENTION、finishRunLlm；max_runs 上限 1000 |
| TermuxDefaultsTest / TermuxRuntimeWiringTest | 移植 clampMaxToolSteps 与校验通过状态持久化 |
| AudioFocusHolderTest | 移植 AudioFocusHolder |
| ConversationRepositoryChatModelIdTest | 移植 encode/decodeChatModelId |
| SkillWebviewCardMetaTest / SkillWebviewCardIframeTest | 移植 hasSkillWebviewMeta、shouldWrapInIframe、buildIframeWrapperHtml（以 data: URL 打开） |
| BrowserTypeScriptTest / HistoryNavResolutionTest | 移植 buildTypeScript、resolveHistoryNav |
| SettingLocalLlmCapabilitiesTest | 移植 deriveLocalModelCapabilities、enableAfterFirstDownload、registerInstalledModel |
| WorkspaceToolsTest | 按本地 pathOutsideWritableRoots + WorkspaceLayout 改写 |
| PreferenceStoreMigrationHelpersTest | 只缺 JsonNull 导入 |
| WorkspaceDetailVMTest 的导出计划用例 | 拆到 WorkspaceFolderExportPlanTest（planWorkspaceFolderExport 本地已有） |

## 仍留在这里的

| 测试 | 原因 |
|---|---|
| browser/BrowserAiActionTest.kt | 依赖 ExTV 的浏览器 AI 操作条重设计（BrowserAiActionKind / BrowserAiActionOutcome 等新模型），外加 16 个新字符串 × 7 种语言；属于 UI 功能移植，不是测试修补。 |
| data/datastore/PreferencesStoreTest.kt | 依赖 AutoCompactionThresholdMode / getContextCompactionTargetTokens 等 ExTV 的自动压缩阈值体系。本地已有 autoCompressMode，照搬会造成两套并存的自动压缩配置，需要先做设计取舍。 |
| service/ChatServiceTest.kt | 18 例：2 例依赖 AutoCompactionThresholdMode（同上，会形成第二套自动压缩）；另有 findToolCallPart / replaceToolCallPart / isStalledTurn 本地不存在。外部搜索策略（shouldUseExternalWebSearch）与 backgroundTextGenerationParams 的 6 例理论上可拆出单独回收。 |
| ui/pages/extensions/workspace/WorkspaceDetailVMTest.kt | 只剩 flattenWorkspaceTree 用例；本地文件页没有可展开的树形视图，等该 UI 移植后再回收。 |

## 备注

* ImportedDatabaseReconcilerTest 中真正开库的 3 个用例依赖 Robolectric native SQLite，
  该运行时没有 Linux aarch64 构建，在 arm64 Linux 上会被 `assumeFalse` 跳过；CI（x86_64）照常执行。
