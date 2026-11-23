# Third-Party Notices

本文件记录随 RikkaHub Agents 二进制/资源一并分发（bundled）的第三方组件及其许可归属，以满足各开源许可的再分发署名（attribution）与版权保留义务。
项目整体采用 AGPL-3.0（见根目录 LICENSE）。

> 免责：本文档为尽责盘点，非法律意见；最终以各上游许可证原文为准。

## Bundled 组件

### Mermaid
- 文件：`app/src/main/assets/html/mermaid.min.js`
- 上游：https://github.com/mermaid-js/mermaid
- 许可：**MIT License**
- 版权：© The Mermaid project contributors
- 说明：压缩构建去除了版权头，特此补注。MIT 许可全文见 https://github.com/mermaid-js/mermaid/blob/develop/LICENSE ，保留署名。

### Mozilla Readability
- 文件：`app/src/main/assets/browser/readability.js`、`app/src/main/assets/browser/readability-readerable.js`
- 上游：https://github.com/mozilla/readability
- 许可：**Apache License 2.0**
- 版权：© 2010 Arc90 Inc（其后由 Mozilla 维护）
- 说明：文件头部已保留原许可与版权；仅为完整性再行列出。

### PRoot
- 文件：`workspace/src/main/jniLibs/**/libproot_exec.so`、`libproot_loader.so`
- 上游：https://github.com/proot-me/proot
- 许可：**GPL-2.0-or-later**（含部分 GPLv2+ 组件）
- 说明：以预编译二进制形式随工作区模块分发。

### MuPDF
- 文件：`document/src/main/jniLibs/**/libmupdf_java.so`
- 上游：https://mupdf.com / https://github.com/ArtifexSoftware/mupdf
- 许可：**AGPL-3.0**（Artifex 亦提供商业授权）
- 说明：Gradle 依赖未声明其许可，特此补注；使用/再分发请遵守 AGPL-3.0 或获取商业许可。

### libsimple（SQLite FTS5 中文分词扩展）
- 文件：`app/src/main/jniLibs/**/libsimple.so`；词典 `app/src/main/assets/simple_dict/`
- 上游：https://github.com/wangfenjin/simple
- 许可：**MIT License**（上游双许可 MIT 或 GPL-3.0-or-later，本项目按 MIT 分支使用）
- 版权：Copyright (c) 2020, 2025 Wang Fenjin；内含 cppjieba（MIT，Copyright YanYi Wu）与 pinyin-data 0.15.0（MIT，Copyright (c) 2016 mozillazg）
- 版本：当前分发的预编译二进制未标注对应上游版本号（未钉死）；v0.6.1 tag 的 LICENSE 头部双许可声明与 master 逐字一致。
- 说明：以预编译二进制形式随应用分发；通过 Requery SQLiteCustomExtension 加载，为消息全文检索提供 `simple` 分词器与 `jieba_query` / `simple_query`。MIT 许可全文见 https://github.com/wangfenjin/simple/blob/master/LICENSE ，保留署名。

### Skills（来自上游仓库）
- `app/src/main/assets/…/skills/` 及 `skills-lock.json` 指向的 GitHub 技能（anthropics/skills、google-gemini/gemini-skills 等）各带其独立 LICENSE（如 `claude-api` 目录内置 `LICENSE.txt`），以各技能包内许可证为准。

### 品牌标识资源（图标）
- `app/src/main/assets/icons/*.svg|png`（如 anthropic、claude、deepseek、doubao、elevenlabs 等各服务商标识）
- 说明：这些是各服务商的商标/标识，仅用于界面中标识对应服务商，**不构成任何商标授权**，不得用于误导或与各品牌无关的宣传。

---

_生成说明：该清单由 Minis 环境对仓库内置资源盘点生成，供项目所有者核对补齐（尤其 `libsimple.so` 与上架时的受限权限申报）。_