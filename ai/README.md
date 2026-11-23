# ai 模块

`me.rerere.ai` —— 纯 Kotlin 的 LLM 协议层库（Provider 抽象、SSE 流式解析、消息/工具序列化），供 `:app` 及其他模块复用。

## 构建

本模块**不含任何 native（C/C++）代码**，无需 CMake / NDK / libMNN.so（历史上的 MNN native 构建已随模块重构移除，此前的构建说明已过时）：

```bash
./gradlew :ai:build
```

## 单元测试

```bash
./gradlew :ai:testDebugUnitTest
```

- `:ai` 的单测已纳入 CI（`.github/workflows/tests.yml`），Provider 协议解析、流式分帧、消息序列化均有覆盖。
- 若新增 Provider 或改动流式协议，请同步补充 `src/test` 下的协议层测试。

> 注：`llama.cpp`（local-llm 本地推理）与 `material-color-utilities`（取色）在各自模块内以 git submodule 引入，与本模块无关；子模块初始化见根目录 README「从源码构建」。
