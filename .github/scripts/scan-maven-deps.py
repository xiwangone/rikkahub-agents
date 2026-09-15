#!/usr/bin/env python3
"""查询依赖清单中各组件在公开漏洞库中的已知问题。

用法: scan-maven-deps.py <cyclonedx.json> [--ignore-file <path>] [--fail-on <level>]

输入为 CycloneDX JSON（由 export-runtime-deps.gradle 生成），逐个组件批量查询，
汇总后打印报告。发现达到阈值的问题时以非零码退出，便于作为检查门禁。

只读取公开漏洞库，不上传任何仓库内容；查询内容是包坐标与版本号。
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

API = "https://api.osv.dev/v1/querybatch"
DETAIL = "https://api.osv.dev/v1/vulns/"
BATCH = 200
RETRIES = 3
WORKERS = 8

LEVELS = {"low": 1, "medium": 2, "moderate": 2, "high": 3, "critical": 4}


def post(payload: dict) -> dict:
    body = json.dumps(payload).encode("utf-8")
    last = None
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(
                API, data=body, headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=90) as resp:
                return json.load(resp)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:  # 网络抖动时重试
            last = exc
            time.sleep(2 * (attempt + 1))
    raise SystemExit(f"查询漏洞库失败：{last}")


def fetch_detail(vuln_id: str) -> dict:
    """批量查询只返回 id，详情需按 id 单独取；失败时退回只含 id 的占位。"""
    url = DETAIL + urllib.parse.quote(vuln_id, safe="")
    last = None
    for attempt in range(RETRIES):
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                return json.load(resp)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last = exc
            time.sleep(1.5 * (attempt + 1))
    print(f"  [警告] 取 {vuln_id} 详情失败：{last}")
    return {"id": vuln_id}


def version_key(text: str) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", text))


def pick_fix(candidates: list[str], current: str) -> str:
    """挑一个可读的修复版本：优先与当前版本同分支（major.minor）中的最小修复版本。"""
    if not candidates:
        return "—"
    cur = version_key(current)
    same = [c for c in candidates if version_key(c)[:2] == cur[:2]]
    pool = same or candidates
    try:
        return min(pool, key=version_key)
    except (ValueError, TypeError):
        return sorted(pool)[0]


def severity_of(vuln: dict) -> str:
    """尽量取一个可比较的严重度标签；取不到时返回 unknown。"""
    spec = vuln.get("database_specific") or {}
    label = spec.get("severity")
    if isinstance(label, str) and label.lower() in LEVELS:
        return label.lower()

    # 退回 CVSS 分数
    best = 0.0
    for item in vuln.get("severity") or []:
        score = item.get("score")
        if not isinstance(score, str):
            continue
        tail = score.rsplit("/", 1)[-1]
        try:
            best = max(best, float(tail))
        except ValueError:
            continue
    if best >= 9.0:
        return "critical"
    if best >= 7.0:
        return "high"
    if best >= 4.0:
        return "medium"
    if best > 0:
        return "low"
    return "unknown"


def fixed_versions(vuln: dict, name: str, version: str) -> list[str]:
    out = []
    for affected in vuln.get("affected") or []:
        pkg = (affected.get("package") or {}).get("name")
        if pkg and pkg != name:
            continue
        for rng in affected.get("ranges") or []:
            for event in rng.get("events") or []:
                fixed = event.get("fixed")
                if fixed:
                    out.append(fixed)
    return sorted(set(out))


def load_ignores(path: str | None) -> set[str]:
    """忽略清单：每行一个漏洞 ID，或 `包名@版本#漏洞ID` 的精确条目。"""
    if not path or not os.path.exists(path):
        return set()
    out = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip() if line.startswith("#") else line.strip()
            if line and not line.startswith("#"):
                out.add(line)
    return out


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="查询依赖清单中的已知漏洞")
    parser.add_argument("sbom", help="CycloneDX JSON 路径")
    parser.add_argument("--ignore-file", default=None, help="忽略清单文件（每行一个漏洞 ID）")
    parser.add_argument("--fail-on", default="high",
                        choices=["low", "medium", "high", "critical"],
                        help="达到该级别即视为阻断（默认 high）")
    ns = parser.parse_args()

    ignores = load_ignores(ns.ignore_file)
    threshold = LEVELS.get(ns.fail_on, 3)

    with open(ns.sbom, encoding="utf-8") as fh:
        sbom = json.load(fh)
    components = sbom.get("components") or []
    print(f"运行时依赖组件数：{len(components)}")

    queries, labels = [], []
    for comp in components:
        group = comp.get("group") or ""
        name = f"{group}:{comp['name']}" if group else comp["name"]
        queries.append(
            {"package": {"ecosystem": "Maven", "name": name}, "version": comp["version"]}
        )
        labels.append(f"{name}@{comp['version']}")

    # 第一步：批量查询，只取漏洞 id
    hits_by_id: dict[str, list[str]] = {}
    for start in range(0, len(queries), BATCH):
        chunk = queries[start:start + BATCH]
        results = post({"queries": chunk}).get("results") or []
        for label, result in zip(labels[start:start + BATCH], results):
            for vuln in result.get("vulns") or []:
                vid = vuln.get("id")
                if vid:
                    hits_by_id.setdefault(vid, []).append(label)
        print(f"  已查询 {min(start + BATCH, len(queries))}/{len(queries)}")

    pending = {vid: hits for vid, hits in hits_by_id.items() if vid not in ignores}
    if not pending:
        print("未发现已知漏洞。")
        return 0

    # 第二步：并发取详情（严重度、修复版本）
    print(f"  获取 {len(pending)} 个漏洞的详情…")
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        details = dict(zip(pending.keys(), pool.map(fetch_detail, pending.keys())))

    unique: dict[str, dict] = {}
    for vid, hits in pending.items():
        vuln = details.get(vid) or {"id": vid}
        kept = [lab for lab in hits if f"{lab}#{vid}" not in ignores]
        if not kept:
            continue
        candidates = sorted(
            {fixed for label in kept for fixed in fixed_versions(vuln, *label.rsplit("@", 1))}
        )
        unique[vid] = {
            "severity": severity_of(vuln),
            "hits": kept,
            "fix": pick_fix(candidates, kept[0].rsplit("@", 1)[-1]),
        }

    if not unique:
        print("未发现已知漏洞。")
        return 0

    print(f"\n发现 {len(unique)} 个已知漏洞：")
    print(f"{'漏洞':<24}{'严重度':<10}{'修复版本':<16}命中组件")
    print("-" * 100)
    blocking = []
    for vid, info in sorted(unique.items(), key=lambda kv: -LEVELS.get(kv[1]["severity"], 0)):
        fix = info["fix"][:14]
        hits = sorted(set(info["hits"]))
        shown = ", ".join(hits[:2]) + (f" (+{len(hits) - 2})" if len(hits) > 2 else "")
        print(f"{vid:<24}{info['severity']:<10}{fix:<16}{shown}")
        if LEVELS.get(info["severity"], 0) >= threshold:
            blocking.append(vid)

    if blocking:
        print(f"\n其中 {len(blocking)} 个达到或超过阈值（{ns.fail_on}），需处理。")
        return 1
    print(f"\n无达到阈值（{ns.fail_on}）的问题。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
