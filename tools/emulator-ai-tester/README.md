# RikkaHub Emulator AI Deep UI & Stress Tester

基于 `adb`（`uiautomator dump` + `screencap` + `gfxinfo` + `meminfo` + `logcat`）与任意 OpenAI 兼容多模态模型的模拟器深度巡检与压测脚本。

## 运行方式（在已连接模拟器或设备的宿主机执行）

```bash
export OPENAI_BASE_URL="https://your-endpoint/v1"
export OPENAI_API_KEY="sk-..."
export MODEL="qwen3-vl-plus"
python3 tools/emulator-ai-tester/run_ai_ui_test.py \
  --scenarios tools/emulator-ai-tester/scenarios.json \
  --package excp.rikkahub.agents.debug \
  --stress \
  --out /tmp/rikkahub-ai-qa
```

启用 `--stress` 后会在每个场景自动执行快速甩滑/连点压测，并在 `report.md` 输出 `Janky Frames`、`P95` 帧耗时、`TOTAL PSS` 内存占用与 `FATAL EXCEPTION` 崩溃捕获。

## 巡检前准备（Pre-flight）

模拟器上没有可用提供商时，提供商/请求类场景根本不可测（`provider_batch_remove_dialog`
无模型可删、`app_log_json_tree_light_dark` 无请求可点）。
开跑前先做完这三步：

1. 起 Mock 服务（宿主机上）：`python3 mock-openai-server.py`（监听 `127.0.0.1:8080`，
   提供 `GET /health`、`GET /v1/models`、`POST /v1/chat/completions`，模型 `mock-chat`）。
2. 在 App 里建 Mock 提供商：Settings -> Providers -> Add，选 OpenAI 兼容，
   baseUrl 填 `http://10.0.2.2:8080/v1`（模拟器访问宿主机的地址），模型 `mock-chat`。
3. 用该提供商先发一条消息（如 `hi`），让请求日志里至少有一条请求。

脚本开跑时会自动探 `http://127.0.0.1:8080/health`（可用 `MOCK_HEALTH_URL` 环境变量改地址）：
Mock 不通且场景里有 `requires: "mock-provider"` 的场景时直接退出（exit 2），并列出受影响的场景；
加 `--allow-no-mock` 可强制跑（那些场景会失败，别静默）。

Mock 的局限：当前模拟器上的 mock 回普通 JSON、非 SSE，App 的流式回复会卡在 `[…]` 占位。
因此只验“请求发出 + 底表能开”，别断言回复内容；要验端到端渲染须先把 mock 改成 SSE。
（若你用的 mock 已支持 SSE 流式回包，这条限制即失效。）

## 场景字段（scenarios.json）

除 `name`、`goal` 外，场景还支持：

- `requires: "mock-provider"`：该场景依赖 Mock 提供商（见上节预检）。目前用于
  `provider_add_models_immediate_refresh`、`provider_batch_remove_dialog`、
  `app_log_json_tree_light_dark`。
- `assert_contains: ["Test Search"]`：到达断言。模型报 `pass` 时脚本会检查当前 dump
  里是否出现所列文本（任一命中即算到达）；没到达则驳回这次 pass，让模型继续导航，
  驳回 2 次后记 FAIL。只判“没崩”会放水（`setting_mcp` 出现过的“软通过”就是模型
  根本没走到真页面），所以目标页明确的场景都应加断言。
- `max_steps: 20`：单个场景的步数预算，覆盖全局 `--max-steps`（默认 14）。
  只在流程确实更长的场景上放宽，别动全局默认。

## 场景间复位

每场景开场先发 `back` 直到回到 Chat 首页（dump 里出现 `New Chat` + `Chat History`，
最多 5 次），再 `monkey` 拉起。上一场景残留的 sheet/对话框不关会泄漏进下一场景
（出现过代价约 13 步 + 1 个场景全废的情况）。注意两点：

- 残留 sheet 如果直接盖在 Chat 首页上，dump 里看不出来（底层节点仍在），复位会提前停；
  这种情况模型 1–2 步内能自己关掉，代价小。
- 每场景开头的 `logcat -c` 会清日志，是已知可接受行为，别动它。
