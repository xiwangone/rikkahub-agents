# golib/tsnet —— 内嵌 Tailscale 的 Go 侧源码

这里是 `app/src/main/jniLibs/arm64-v8a/libgojni.so` 与 `app/libs/tsnetlib.jar` 的**唯一源码**。
`.so` 已入仓（约 40.8MB），**平时不需要重编译**；只有在改动本目录的 Go 代码、或升级 Tailscale 版本时才需要重新生成。

## 依赖版本

```
tailscale.com v1.104.4     （2026-10-28 发布，生成时的最新稳定版）
```

需要 Go 1.27+（`gomobile bind` 在 Go 1.27 起要求模块内有 `tool` 指令）。

## 重新生成步骤

前置：Go toolchain、`gomobile`、Android NDK、JDK（`javac` 必须在 PATH 里）。

```bash
# 1) 装 gomobile（Go 1.27+ 需要 tool 指令，否则 bind 会报
#    "requires golang.org/x/mobile in the current module"）
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# 2) 在本目录补 tool 依赖（只需一次，会写进 go.mod）
go get -tool golang.org/x/mobile/cmd/gobind

# 3) 生成 aar
gomobile bind -target=android/arm64 -androidapi 24 -o tsnet.aar tsnetlib

# 4) 解出产物，覆盖回工程
#    tsnet.aar 内含 jni/arm64-v8a/libgojni.so 与 classes.jar
```

产物落点：

| 产物 | 目标位置 |
|---|---|
| `libgojni.so` | `app/src/main/jniLibs/arm64-v8a/libgojni.so` |
| `classes.jar` | `app/libs/tsnetlib.jar` |

## 注意

- **注释与字符串保持纯 ASCII**：`gomobile` 会把 Go 的 doc 注释复制进生成的 Java 源码，而 `javac` 按平台编码（zh-CN Windows 上是 GBK）读取，中文会导致
  `错误: 编码 GBK 的不可映射字符` 并让 bind 失败。
- 包名固定为 `tsnetlib`（Kotlin 侧 `import tsnetlib.Tsnetlib`）。
- 只做 per-app 代理，不注册 `VpnService`，因此**不影响系统 DNS 与其他应用**。
