#!/usr/bin/env bash
# 准备 Android SDK：优先使用运行器预装，缺失时下载命令行工具（多源回退）。
#
# 用法: setup-android-sdk.sh
# 成功时把 SDK 路径写入 GITHUB_ENV / GITHUB_PATH，供后续步骤使用。
#
# 不依赖第三方 setup 动作：它在部分镜像上会因尝鲜安装已下线的旧组件而整步失败，
# 而运行器本身通常已带完整 SDK，直接复用更稳。
set -uo pipefail

log() { printf '[android-sdk] %s\n' "$*"; }

is_sdk() {
  [ -n "${1:-}" ] && [ -d "$1" ] && { [ -d "$1/platforms" ] || [ -x "$1/platform-tools/adb" ]; }
}

find_preinstalled() {
  local c
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" /usr/local/lib/android/sdk "$HOME/Android/Sdk"; do
    if is_sdk "$c"; then printf '%s' "$c"; return 0; fi
  done
  return 1
}

download_cmdline_tools() {
  local dest="$1" zip="$1/clt.zip"
  # 多源回退：官方直连优先，镜像兜底
  local sources=(
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip"
    "https://mirrors.aliyun.com/android.googlesource.com/commandlinetools-linux-11076708_latest.zip"
  )
  local url
  for url in "${sources[@]}"; do
    log "尝试下载命令行工具: $url"
    if curl -fsSL --retry 2 --connect-timeout 20 --max-time 300 -o "$zip" "$url"; then
      rm -rf "$dest/cmdline-tools-tmp"
      if unzip -q "$zip" -d "$dest/cmdline-tools-tmp"; then
        mkdir -p "$dest/cmdline-tools"
        rm -rf "$dest/cmdline-tools/latest"
        mv "$dest/cmdline-tools-tmp/cmdline-tools" "$dest/cmdline-tools/latest"
        rm -rf "$dest/cmdline-tools-tmp" "$zip"
        return 0
      fi
    fi
  done
  return 1
}

accept_licenses() {
  local sdk="$1"
  local sm="$sdk/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sm" ]; then
    sm="$(command -v sdkmanager 2>/dev/null || true)"
  fi
  if [ -z "$sm" ] || [ ! -x "$sm" ]; then
    log "未找到 sdkmanager，跳过许可确认"
    return 0
  fi
  log "确认 SDK 许可"
  timeout 180 bash -c "yes 2>/dev/null | '$sm' --sdk_root='$sdk' --licenses" >/dev/null 2>&1 \
    || log "许可确认未完成（构建时若缺组件会自动补齐）"
}

# AGP 现场下载 NDK 时既无重试也无完整性校验，包一截断（"Archive is not a ZIP archive"）整条流程就挂；
# 这里预装一次（带重试 + 装完校验），版本与 llama-cpp 模块要求保持一致。
ensure_ndk() {
  local sdk="$1" ver="${NDK_VERSION:-28.2.13676358}"
  local sm="$sdk/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sm" ]; then
    sm="$(command -v sdkmanager 2>/dev/null || true)"
  fi
  if [ -z "$sm" ] || [ ! -x "$sm" ]; then
    log "未找到 sdkmanager，跳过 NDK 预装"
    return 0
  fi
  if [ -f "$sdk/ndk/$ver/source.properties" ]; then
    log "NDK $ver 已就绪"
    return 0
  fi
  local i
  for i in 1 2 3; do
    log "预装 NDK $ver（第 $i 次）"
    rm -rf "$sdk/ndk/$ver" 2>/dev/null || true
    if timeout 1500 bash -c "yes 2>/dev/null | '$sm' --sdk_root='$sdk' 'ndk;$ver'" >/dev/null 2>&1 \
       && [ -f "$sdk/ndk/$ver/source.properties" ]; then
      log "NDK $ver 预装完成"
      return 0
    fi
    log "预装失败（多为下载包损坏），清理后重试"
    rm -rf "$sdk/ndk/$ver" 2>/dev/null || true
    sleep 5
  done
  log "::warning::NDK $ver 预装三次均失败，交由后续构建自行处理"
  return 0
}

main() {
  local sdk
  if sdk="$(find_preinstalled)"; then
    log "使用运行器预装 SDK: $sdk"
  else
    sdk="${RUNNER_TEMP:-/tmp}/android-sdk"
    log "未找到预装 SDK，准备下载到 $sdk"
    mkdir -p "$sdk"
    if ! download_cmdline_tools "$sdk"; then
      log "全部下载源均失败"
      return 1
    fi
  fi

  accept_licenses "$sdk"
  ensure_ndk "$sdk"

  if [ -n "${GITHUB_ENV:-}" ]; then
    {
      echo "ANDROID_HOME=$sdk"
      echo "ANDROID_SDK_ROOT=$sdk"
    } >> "$GITHUB_ENV"
  fi
  if [ -n "${GITHUB_PATH:-}" ]; then
    {
      echo "$sdk/cmdline-tools/latest/bin"
      echo "$sdk/platform-tools"
    } >> "$GITHUB_PATH"
  fi

  log "SDK 就绪，目录内容:"
  ls "$sdk" 2>/dev/null | head -12
  return 0
}

main "$@"
