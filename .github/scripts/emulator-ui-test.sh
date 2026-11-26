#!/usr/bin/env bash
# 模拟器测试步骤：仪器测试与 AI 巡检，由 03 工作流在模拟器启动后调用。
# 环境变量：MODE / VARIANT / API_LEVEL / ONLY_SCENARIOS / AI_PATROL_MODEL / ALLOW_NO_MOCK
# 以及 AI_PATROL_OPENAI_BASE_URL / AI_PATROL_OPENAI_API_KEY（缺失时跳过 AI 巡检）。
set -uo pipefail

MODE="${MODE:-instrumentation}"
VARIANT="${VARIANT:-debug}"
echo "模式：$MODE / 变体：$VARIANT / API：${API_LEVEL:-?}"
chmod +x gradlew
INST_RC=0
PATROL_RC=0

if [ "$MODE" = "instrumentation" ] || [ "$MODE" = "both" ]; then
  echo "== 仪器测试 :app:connectedDebugAndroidTest =="
  ./gradlew :app:connectedDebugAndroidTest --build-cache --continue 2>&1 | tee connected-test.log
  INST_RC=${PIPESTATUS[0]}
  echo "仪器测试退出码：$INST_RC"
fi

if [ "$MODE" = "ai_patrol" ] || [ "$MODE" = "both" ]; then
  echo "== AI 巡检（$VARIANT）=="
  if [ -z "${AI_PATROL_OPENAI_BASE_URL:-}" ] || [ -z "${AI_PATROL_OPENAI_API_KEY:-}" ]; then
    echo "::warning::未配置 AI_PATROL_OPENAI_BASE_URL / AI_PATROL_OPENAI_API_KEY，跳过 AI 巡检（不是失败）"
    echo "AI 巡检已跳过：未配置 Secrets AI_PATROL_OPENAI_BASE_URL / AI_PATROL_OPENAI_API_KEY" > ai-patrol-skipped.txt
  else
    if [ "$VARIANT" = "release" ]; then
      PKG="excp.rikkahub.agents"
      APK_DIR="app/build/outputs/apk/release"
      echo "构建 release APK（R8）…"
      ./gradlew :app:assembleRelease --build-cache 2>&1 | tail -20
      APK=$(ls -S "$APK_DIR"/*.apk 2>/dev/null | head -1)
      if [ -z "$APK" ]; then
        echo "::error::没找到 release APK（$APK_DIR）"
        PATROL_RC=1
      else
        echo "安装 $APK"
        adb install -r "$APK" || { echo "::error::release APK 安装失败（签名冲突？先卸载旧包）"; PATROL_RC=1; }
      fi
    else
      PKG="excp.rikkahub.agents.debug"
      if ! adb shell pm list packages 2>/dev/null | grep -q "$PKG"; then
        echo "安装 debug APK…"
        ./gradlew :app:installDebug --build-cache 2>&1 | tail -5
      fi
    fi

    if [ "$PATROL_RC" = "0" ]; then
      SCEN_PATH="tools/emulator-ai-tester/scenarios.json"
      ONLY="${ONLY_SCENARIOS:-}"
      if [ -n "$ONLY" ]; then
        echo "只跑指定场景：$ONLY"
        jq --arg names "$ONLY" '
          [ .[] | select(.name as $n
              | ($names | split(",") | map(gsub("^\\s+|\\s+$";""))
              | index($n)) != null) ]' \
          "$SCEN_PATH" > /tmp/scenarios-filtered.json
        echo "选中 $(jq 'length' /tmp/scenarios-filtered.json) 个场景"
        SCEN_PATH="/tmp/scenarios-filtered.json"
      fi

      export OPENAI_BASE_URL="${AI_PATROL_OPENAI_BASE_URL:-}"
      export OPENAI_API_KEY="${AI_PATROL_OPENAI_API_KEY:-}"
      export MODEL="${AI_PATROL_MODEL:-gpt-4o-mini}"
      EXTRA=()
      if [ "${ALLOW_NO_MOCK:-true}" = "true" ]; then EXTRA+=(--allow-no-mock); fi
      python3 tools/emulator-ai-tester/run_ai_ui_test.py \
        --scenarios "$SCEN_PATH" \
        --package "$PKG" \
        "${EXTRA[@]}" \
        --out "ai-patrol-reports-$VARIANT" 2>&1 | tee ai-patrol.log
      PATROL_RC=${PIPESTATUS[0]}
      echo "AI 巡检退出码：$PATROL_RC"
    fi
  fi
fi

if [ "$INST_RC" != "0" ] || [ "$PATROL_RC" != "0" ]; then
  echo "::error::测试失败（仪器=$INST_RC，巡检=$PATROL_RC）"
  exit 1
fi
echo "全部通过"