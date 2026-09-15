#!/usr/bin/env python3
"""把 `gradlew :app:dependencies --configuration releaseRuntimeClasspath` 的输出
转换成 CycloneDX JSON 清单。

用法: parse-gradle-deps.py <dependencies.txt> <out.json>

用内置的 dependencies 任务取依赖，避免自行解析配置在新版 Gradle 上受限；
:app 的运行时配置已包含各本地模块及其外部依赖，一条命令即可覆盖分发内容。
"""

from __future__ import annotations

import json
import re
import sys

# 形如:  +--- io.ktor:ktor-client-core:3.5.2
#        \--- com.squareup.okhttp3:okhttp:5.4.0
#        |    +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.0 (*)
# 版本冲突时形如: group:name:1.0 -> 2.0，取箭头后的实际版本
COORD = re.compile(
    r"^[|\s+\\\-]*"
    r"(?:project\s+)?"
    r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-+]+)"
    r"(?:\s*->\s*([A-Za-z0-9_.\-+]+))?"
)


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2

    src, dst = sys.argv[1], sys.argv[2]
    seen: set[tuple[str, str, str]] = set()
    unresolved: list[str] = []

    with open(src, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            if "FAILED" in line or "FAILURE" in line:
                unresolved.append(line.strip()[:160])
            m = COORD.match(line.rstrip())
            if m:
                group, name, version, resolved = m.groups()
                seen.add((group, name, resolved or version))

    components = [
        {
            "type": "library",
            "group": group,
            "name": name,
            "version": version,
            "purl": f"pkg:maven/{group}/{name}@{version}",
        }
        for group, name, version in sorted(seen)
    ]

    with open(dst, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "bomFormat": "CycloneDX",
                "specVersion": "1.5",
                "version": 1,
                "components": components,
            },
            fh,
            ensure_ascii=False,
        )

    print(f"解析出 {len(components)} 个组件 -> {dst}")
    if unresolved:
        print("以下依赖未能解析（仅供排查，不影响其余组件）：")
        for item in unresolved[:10]:
            print("  " + item)

    # 一个组件都没有说明输出格式与预期不符，显式失败避免“看起来在扫、其实没扫”
    if not components:
        print("错误：未解析到任何组件，请检查依赖输出格式", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
