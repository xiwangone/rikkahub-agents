# Contributing to RikkaHub Agents

感谢参与！本文件汇总从 fork 到合并的关键路径，细节约定见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（必读，尤其是第 1 节「隐式约定」的 8 条隐式约定）。

## 环境准备

- **JDK 17+**（local-llm 模块的单测需要 **JDK 21**，见 `.github/workflows/tests.yml` 中单独的 job）
- Android SDK（**API 37**）、**NDK**（`:llama-cpp` 本地推理库需要）
- 初始化子模块（缺一不可，否则编译失败）：

```bash
git clone https://github.com/xiwangone/rikkahub-agents.git
cd rikkahub-agents
git submodule update --init --recursive
```

## 构建与测试

```bash
./gradlew :app:assembleDebug          # 构建调试包
./gradlew :app:testDebugUnitTest      # app 模块单测（CI 同款命令）
./gradlew :ai:testDebugUnitTest       # 协议层单测
./gradlew :local-llm:testDebugUnitTest  # 需 JDK 21
```

CI 实际执行的模块清单与重试策略以 `.github/workflows/tests.yml` 为准。

## 提交前自查

1. **领域模型与数据库实体成对改**：给会话/消息加字段时，Entity 列 + 双向映射 + 数据库版本/迁移三件套缺一不可，并补一条实体往返单测（ARCHITECTURE.md §1.2）。
2. **新工具必须登记 ToolIndex**：`app/src/main/java/.../tools/registry/ToolIndex.kt` 中登记一行，`ToolIndexTest` 会校验定义/注册/渲染三方对齐，漏登记直接构建失败。
3. **新文案走本地化资源**，不要硬编码 Kotlin 字符串——CI 有 `check-new-localization.py` 拦截；新增资源需保证七语言覆盖（`check-port-resources.py`）。
4. **给会话/UI 加逻辑优先看是否有既有策略函数**（`StreamUiUpdatePolicy.kt`、`ChatMessageQueuePolicy.kt` 等策略文件）可复用，并为其补纯 JVM 单测。
5. 数据库迁移改动需同步在 `app/src/androidTest/.../migrations/` 补 Migration 测试。

## 测试策略

- 纯逻辑（策略、解析、分帧）写 `src/test` 纯 JVM 测试，跑得快、CI 全量执行。
- 需要 Android 运行时（Room、Worker、崩溃恢复）的写 `src/androidTest`，当前 CI 无模拟器，这类测试本地跑 `connectedCheck` 验证。
- 被隔离的历史测试在 `stale-tests/`（含台账 README）：恢复对应功能时用 `git mv` 收回并保证编译通过，不要直接改坏 `:app:compileDebugUnitTestKotlin` 的绿色状态。

## 提交与 PR

- 提交信息用简体中文、一行说明改动语义（与现有 git 历史风格一致）。
- PR 会自动触发：单元测试（16 个模块，以 `.github/workflows/tests.yml` 为准）、本地化/资源检查、detekt、CodeQL。依赖图提交只在依赖文件推到 master 时触发，PR 不触发。
- 涉及 `.github/scripts/` 下 Python 门禁脚本的改动，`scripts-check.yml` 会做 `py_compile` 检查，PR 描述中请说明触发场景。

## 许可证

AGPLv3。提交即表示同意以同协议授权你的贡献。
