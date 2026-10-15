# RikkaHub 技能规范（Skills Spec）

本规范定义 `assets/default-skills/` 内置技能、`assets/skill-archive/` 存档技能与用户安装技能的**统一格式、七语言组织方式与覆盖安装语义**，以及 Phase 19E 起「技能商店」的目录格式与远端源合并规则。技能是注入系统提示词 / 经 `use_skill` 按需加载的 Markdown 指令包，代码实现在
`app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt`（存取与种入）、
`SkillLocale.kt` / `SkillPaths.kt`（本地化解析）、
`skills/SkillCatalog.kt` / `SkillCatalogMerger.kt` / `RemoteCatalogFetcher.kt` / `SkillStoreSourceStore.kt`（商店目录与远端源）、
`data/ai/tools/SkillsTools.kt`（注入与 `use_skill`）。

## 1. 目录与文件组织

```
assets/
  default-skills/              # 内置技能：随 APK 分发，首次启动自动 seed
    <skill-name>/              # 目录名即技能名（安装后存于 filesDir/skills/）
      SKILL.md                 # 默认语言版（内置技能默认中文），必填
      SKILL.<lang>.md          # 本地化版，可选，与 SKILL.md 同级
      SOUL.md                  # sidecar 附属文件（如 auto_load_path 指向它）
      SOUL.<lang>.md           # sidecar 同样支持本地化
      assets/                  # 该技能的静态资源（JS/HTML/图片）
  skill-archive/               # 存档技能：随 APK 分发但不自动 seed，用户在商店按需安装
    <skill-name>/              # 组织方式与 default-skills 完全相同
  skill-catalog.json           # 商店目录（见 §7）
skills/                        # 运行时目录（filesDir/skills/），上两者安装后的落点
```

- `SKILL.md` 是**兜底默认版**，必须存在；导入 / 安装时缺少它会被拒绝。
- `SKILL.<lang>.md` 是**完整文件**（自带 frontmatter + 正文），不是片段。
- 任意 sidecar（`auto_load_path` 指向的文件、正文里 Markdown 链接引用的文件）都遵循同一命名规则。

### 语言代码表

文件后缀使用 BCP-47 的 language 或 language-Script（**不带 region**），与 App 的 `values-*` 资源目录一一对应：

| values 目录 | 语言代码 | 文件后缀 |
| --- | --- | --- |
| `values/`（默认） | en | `.en.md` |
| `values-zh/` | zh | `.zh.md` |
| `values-b+zh+Hant/` | zh-Hant | `.zh-Hant.md` |
| `values-ja/` | ja | `.ja.md` |
| `values-ko-rKR/` | ko | `.ko.md` |
| `values-ru/` | ru | `.ru.md` |
| `values-ar/` | ar | `.ar.md` |

解析规则（`SkillLocale.localizedCandidates`）：设备 locale 为 `zh-Hant-TW` 时按
`SKILL.zh-Hant.md` → `SKILL.zh.md` → `SKILL.md` 顺序取第一个存在的；`en-US` 时按
`SKILL.en.md` → `SKILL.md`。任何语言取不到翻译都回落到默认文件，**技能不会因为缺翻译而消失**。

## 2. Frontmatter 规范

```yaml
---
name: my-skill                    # 必填。与目录名一致；各语言版本必须完全相同
description: 一句话说明…          # 必填。使用该文件的语言书写
compatibility: 可选的环境说明      # 选填
auto_load: true                   # 选填。true = 每轮内联进系统提示词（仅核心技能用）
auto_load_path: SOUL.md           # 选填。auto_load 时改内联这个文件而非 SKILL.md 正文
---
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 技能唯一标识。各语言版本、各 sidecar 引用必须一致；安装后不可改 |
| `description` | 是 | 发现用的一句话描述，**随文件语言本地化**。会原样注入系统提示词 |
| `compatibility` | 否 | 环境 / 依赖说明 |
| `auto_load` | 否 | `true` 时每轮内联（目前仅 `agent-core`） |
| `auto_load_path` | 否 | 内联的 sidecar 路径（如 `SOUL.md`），同样按语言解析 |
| `args_schema` | 否 | 单行 JSON object，LLM 工具 `use_skill` 用 |
| `format` / `source-url` | 否 | 安装时由导入器写入，标识来源 |
| `source-format` | 否 | 导入器写入：`openclaw` / `hermes` |

注意：`allowed-tools` 历史上不解析、不强制执行，不要在技能里依赖它。

### description 写法规范

- 一句话，说清**做什么 + 什么时候用**（触发场景），因为模型只靠它在技能列表里做匹配。
- 用该文件的语言书写；默认文件用中文。
- 祈使句 / 名词短语，不要营销词。
- 建议 ≤ 120 字符（无硬限制，但列表 UI 只显示两行）。

## 3. 覆盖安装与「用户覆写优先」

所有安装路径（GitHub 目录导入、本地 .md/.zip、LLM `skill_install_from_*`、目录页 Install、手写新建）最终都走 `SkillManager.saveSkillFileBytesAtomically`：**同名即原子整目录覆盖**，没有跳过或建副本。

覆盖一个**内置同名技能**时，安装完成会写入 `.user-overridden` 哨兵：

- 之后 `seedDefaultSkillsIfNeeded` 跳过该技能——**用户的版本不再被 assets 旧版打回**（这正是修复前的 bug：覆盖安装中文版 `agent-core` 后，下次启动被 seed 回退成 assets 英文版）。
- 用户主动「恢复内置版」（技能目录里对 `is_bundled` 条目点 Install）走 `reinstallBundledSkill`：先删目录（连哨兵一起）再重种，磁盘上必然得到 assets 当前版本。
- 删除技能（`deleteSkill`）连目录带哨兵一起删，并记入 `deletedBundledSkills`，不会被静默种回。

非内置技能（用户自建）从来不受 seed 影响。

### 种子（seed）行为速查

| 场景 | 行为 |
| --- | --- |
| 首次安装，目录不存在 | 种入 |
| 内置 non-core，`.seeded` 在 | 跳过 |
| 内置 non-core，无 `.seeded` 且目录非空（用户装过 / 覆盖装过） | 跳过，绝不覆盖 |
| 内置 core（`auto_load: true`），hash 一致 | 跳过 |
| 内置 core，bundled hash 变化（APK 升级带新内容） | 重种（**除非** `.user-overridden` 存在） |
| 用户覆盖安装过（`.user-overridden`） | 永远跳过，直到用户点「恢复内置版」 |

## 4. 内置技能的多语言现状与补齐方式

内置技能位于 `app/src/main/assets/default-skills/`，随 APK 分发。

- 除 `agent-core` 外，其余 9 个 `default-skills/` 内置技能目前只有中文默认版（`SKILL.md`）。
- 另有 5 个技能位于 `assets/skill-archive/`（`interactive-map` / `kitchen-adventure` / `mood-tracker` / `text-spinner` / `virtual-piano`），**不自动 seed**，仅在商店中按需安装（见 §7）。
- `agent-core` 已按本规范拆分：`SKILL.md` / `SOUL.md` / `HEARTBEAT.md` / `TOOLS.md` 为中文默认版，`.en.md` 为英文版，是**七语言改造的范例**。
- 补齐其他五个语言（ja / ko / ru / ar / zh-Hant）时：复制默认文件为 `SKILL.<lang>.md` 等，翻译正文与 frontmatter `description`，**保持 `name`、`auto_load`、`auto_load_path` 不变**，正文里的内部链接继续用基名（`[SOUL.md](SOUL.md)`）——读取时会自动解析到对应语言变体。
- App 侧 `values-*/strings.xml` 的 UI 字符串翻译不走本机制；技能内容不引用字符串资源。

## 5. 安装 / 导入器的约束

- `name` 字符集：导入路径要求 1..40 字符 `[a-z0-9-_]`（`SkillUrlImporter.isValidSkillName`）；Add 对话框手写路径只校验非空。
- 单文件上限 512KB；zip 必须含 `SKILL.md`；多语言 sibling 随包原样落盘，无需在导入器里登记。
- 覆盖安装是**整目录替换**：旧目录里的未翻译 sidecar（如只有中文的 `SOUL.md`）会被新包内容取代，不会合并。

## 6. 自查命令

```bash
# 某技能已有哪些语言（应含默认版之外的后缀）
ls app/src/main/assets/default-skills/<name>/

# 确认所有语言文件的 name 字段一致
grep -h '^name:' app/src/main/assets/default-skills/<name>/SKILL*.md | sort -u

# 确认默认文件存在
ls app/src/main/assets/default-skills/<name>/SKILL.md
```

## 7. 技能商店与远端源（Phase 19E）

技能目录页顶部的商店入口打开 `FeaturedCatalogSheet`：**本地内置目录 + 用户自配远端源**合并后的条目列表，每条目带状态徽标（可安装 / 安装中 / 已安装 / 恢复）与来源标签（内置 / 远端源名）。

### 目录 JSON 格式（`assets/skill-catalog.json` 与远端源同构）

```json
{
  "version": 1,
  "updated_at": "2026-05-07",
  "skills": [
    {
      "name": "auto-reply",
      "title": "自动回复",
      "description": "通过屏幕上的聊天 App 回复某个联系人的消息。",
      "category": "automation",
      "license": "Apache-2.0",
      "size_kb": 4,
      "compatibility": "any",
      "source_url": null,
      "is_bundled": true,
      "archived": false
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `name` / `title` / `description` | 标识与展示文案；`name` 与技能目录名一致 |
| `source_url` | 非 null 时点 Install 走 `SkillUrlImporter.importFromUrl` 拉取**原始 SKILL.md 文本**（不是 zip） |
| `is_bundled` | true = 随 APK 分发，安装即「恢复内置版」（从 assets 重种） |
| `archived` | true = 位于 `assets/skill-archive/`，不自动 seed，商店按需安装（`installArchivedSkill`） |

### 合并规则（`SkillCatalogMerger.mergeCatalogs`，纯函数有单测）

1. 本地内置目录全量收录，同名去重（先到先得）。
2. 远端源按用户配置顺序合并；远端条目 `is_bundled` **强制 false**（防止抄来的 "bundled" 声明把安装误导到不存在的 assets），并打上 `sourceId` / `sourceName` 来源标记。
3. 同名冲突**本地赢**；远端之间先到先得。
4. 拉取失败的源只记状态（`FAILED`，UI 显示「无法访问」），不贡献条目；有缓存时降级展示并标 `OK_CACHED`（「显示缓存副本」）。

### 远端源配置（`SkillStoreSourceStore`）

- 用户可在「管理商店源」里增删改：名称（可选，缺省从 URL 派生）+ catalog 链接。
- URL 归一化（`normalizeUrl`）：无 scheme 补 `https://`，仅接受 `http`/`https`；最多 20 个源。
- 存储于专用 DataStore（`skill_store_sources`），`encodeSources`/`parseSources` 为纯函数有单测。
- 拉取走 OkHttp（`RemoteCatalogFetcher`）：1MB body 上限，成功写缓存 `cacheDir/skill-store-catalog/<id>.json`，失败读缓存降级；加源/删源后自动刷新合并目录。
- 源列表按启用/停用过滤；停用的源不参与合并。

## 8. 参考

- 种入与覆盖逻辑：`SkillManager.seedDefaultSkillsIfNeeded` / `saveSkillFileBytesAtomically` / `reinstallBundledSkill` / `installArchivedSkill`
- 纯策略函数（有单测）：`decideSeedAction`、`SkillLocale`、`SkillPaths.resolveLocalizedSkillFile`、`SkillCatalogMerger.mergeCatalogs`、`SkillStoreSourceStore`（companion 纯函数）、`parseSkillCatalogJson`
- 单测：`SkillLocaleTest`、`SeedDecisionTest`、`SkillPathsTest`、`SkillsToolsTest`、`SkillCatalogMergerTest`、`SkillStoreSourceTest`
