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
