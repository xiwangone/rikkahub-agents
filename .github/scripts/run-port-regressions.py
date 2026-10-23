#!/usr/bin/env python3
"""Compile and run focused port-regression tests without configuring the Android app.

The Kotlin compiler and all dependencies are pinned by Maven coordinates. The driver
validates each cached/downloaded artifact against the checksum published beside that
exact artifact on Maven Central (SHA-256 where available, otherwise SHA-1). These
checksums protect against corruption/mismatched bytes; because the sidecars come from
the same repository over HTTPS, they are not an independent trust root.
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(os.environ.get("PORT_REGRESSION_OUT", ROOT / "port-regression-results")).resolve()
CACHE = pathlib.Path(os.environ.get("PORT_REGRESSION_CACHE", pathlib.Path.home() / ".cache/rikka-port-regressions/jars")).resolve()
MAVEN = "https://repo.maven.apache.org/maven2"

# group, artifact, version, jar filename. All versions are explicit, not ranges.
ARTIFACTS = [
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20", "kotlin-compiler-embeddable-2.4.20.jar"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20", "kotlin-stdlib-2.4.20.jar"),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.20", "kotlin-script-runtime-2.4.20.jar"),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10", "kotlin-reflect-1.6.10.jar"),
    ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.4.20", "kotlin-daemon-embeddable-2.4.20.jar"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0", "kotlinx-coroutines-core-jvm-1.8.0.jar"),
    ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330", "trove4j-1.0.20200330.jar"),
    ("org.jetbrains", "annotations", "24.1.0", "annotations-24.1.0.jar"),
    ("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.20", "kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar"),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0", "kotlinx-serialization-core-jvm-1.11.0.jar"),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.11.0", "kotlinx-serialization-json-jvm-1.11.0.jar"),
    ("org.jetbrains.kotlinx", "kotlinx-datetime-jvm", "0.8.0", "kotlinx-datetime-jvm-0.8.0.jar"),
    ("junit", "junit", "4.13.2", "junit-4.13.2.jar"),
    ("org.hamcrest", "hamcrest-core", "1.3", "hamcrest-core-1.3.jar"),
]

PRODUCTION = [
    "ai/src/main/java/me/rerere/ai/core/Usage.kt",
    "ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt",
    "ai/src/main/java/me/rerere/ai/util/Json.kt",
    "app/src/main/java/me/rerere/rikkahub/utils/Json.kt",
    "app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt",
]
TESTS = [
    "ai/src/test/java/me/rerere/ai/core/UsageTest.kt",
    "app/src/test/java/me/rerere/rikkahub/ui/components/message/GroupMessagePartsTest.kt",
    "app/src/test/java/me/rerere/rikkahub/ui/components/message/MessagePartGroupingTest.kt",
    "app/src/test/java/me/rerere/rikkahub/ui/components/message/tools/ToolStepStatusTest.kt",
]
TOOL_STEP_SOURCE = "app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolRenderingComponents.kt"
START_MARKER = "/** Visual status shared by registered, generic and MCP tool steps. */"
END_MARKER = "/** Compact status chip used in every tool step title row. */"


def sha(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def request(url: str, attempts: int = 4) -> bytes:
    last = None
    for n in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "rikka-port-regression/1.0"})
            with urllib.request.urlopen(req, timeout=45) as response:
                return response.read()
        except Exception as e:  # retry only transient/public network errors
            last = e
            if n + 1 < attempts:
                time.sleep(1 + n * 2)
    raise RuntimeError(f"Unable to fetch public Maven artifact/checksum {url}: {last}")


def artifact_url(group: str, artifact: str, version: str, filename: str) -> str:
    return f"{MAVEN}/{group.replace('.', '/')}/{artifact}/{version}/{filename}"


def download_artifacts() -> tuple[dict[str, pathlib.Path], dict[str, dict[str, str]]]:
    CACHE.mkdir(parents=True, exist_ok=True)
    jars, metadata = {}, {}
    for group, artifact, version, filename in ARTIFACTS:
        url = artifact_url(group, artifact, version, filename)
        sidecar, algorithm = None, None
        errors = []
        for algo in ("sha256", "sha1"):
            try:
                raw = request(url + "." + algo).decode("ascii", "strict").strip()
                digest = raw.split()[0].lower()
                if len(digest) != (64 if algo == "sha256" else 40) or any(c not in "0123456789abcdef" for c in digest):
                    raise ValueError(f"invalid {algo} sidecar: {raw!r}")
                sidecar, algorithm = digest, algo
                break
            except Exception as e:
                errors.append(str(e))
        if not sidecar:
            raise RuntimeError(f"No Maven-published SHA-256/SHA-1 sidecar for {url}: {'; '.join(errors)}")

        target = CACHE / filename
        expected = sidecar
        def matches(path: pathlib.Path) -> bool:
            h = hashlib.new(algorithm)
            with path.open("rb") as f:
                for block in iter(lambda: f.read(1024 * 1024), b""):
                    h.update(block)
            return h.hexdigest() == expected
        if not target.exists() or not matches(target):
            target.unlink(missing_ok=True)
            data = request(url)
            actual = hashlib.new(algorithm, data).hexdigest()
            if actual != expected:
                raise RuntimeError(f"Checksum mismatch from Maven Central: {filename} expected {expected}, got {actual}")
            tmp = target.with_suffix(target.suffix + ".part")
            tmp.write_bytes(data)
            os.replace(tmp, target)
        jars[filename] = target
        metadata[filename] = {
            "coordinate": f"{group}:{artifact}:{version}",
            "url": url,
            "checksumAlgorithm": algorithm,
            "mavenSidecarUrl": url + "." + algorithm,
            "mavenPublishedChecksum": expected,
            "computedSha256": sha(target),
        }
    return jars, metadata


def extract_step_status(source: str) -> tuple[str, dict[str, str]]:
    text = pathlib.Path(source).read_text(encoding="utf-8")
    start = text.find(START_MARKER)
    end = text.find(END_MARKER, start + len(START_MARKER))
    if start < 0 or end < 0 or text.find(START_MARKER, start + 1) >= 0:
        raise RuntimeError(f"Could not locate unique ToolStepStatus production boundary in {source}")
    snippet = text[start:end].rstrip()
    if "internal fun resolveToolStepStatus(" not in snippet or "enum class ToolStepStatus" not in snippet:
        raise RuntimeError("ToolStepStatus extraction boundary no longer contains expected production declarations")
    package = "package me.rerere.rikkahub.ui.components.message.tools\n\n"
    info = {
        "path": source,
        "sha256": sha(ROOT / source),
        "includedBoundaryStart": START_MARKER,
        "excludedBoundaryStart": END_MARKER,
        "scope": "actual enum and pure resolver extracted verbatim; Compose badge/rendering excluded",
    }
    return package + snippet + "\n", info


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    # Avoid stale evidence from making a failing/no-test run look like a success.
    for p in OUT.iterdir():
        if p.is_file():
            p.unlink()
        elif p.is_dir():
            shutil.rmtree(p)
    started = datetime.now(timezone.utc).isoformat()
    report: dict = {
        "startedUtc": started,
        "repository": str(ROOT),
        "workingDirectory": str(pathlib.Path.cwd()),
        "runner": {"python": sys.version, "java": None},
        "commands": [],
        "tests": [],
        "sourceSha256": {},
        "artifactChecksums": {},
        "result": "failed",
        "limitations": [
            "This is a focused JVM regression, not a full Android/Gradle/Compose build or runtime test.",
            "ChatMessageCot.kt (including groupMessageParts and chart grouping) is compiled verbatim with only a tiny source-compatible fastForEachIndexed utility shim; Compose runtime/rendering is not exercised.",
            "ToolStepStatus enum and resolveToolStepStatus are extracted verbatim by the recorded source markers; Compose badge UI is excluded.",
            "Checksum sidecars are fetched from the same public Maven Central HTTPS repository as artifacts; checksum verification detects corruption/mismatch but is not an independent provenance/signature guarantee.",
        ],
    }
    log_lines = []
    def log(s: str) -> None:
        print(s, flush=True)
        log_lines.append(s)

    try:
        for rel in PRODUCTION + TESTS + [TOOL_STEP_SOURCE]:
            path = ROOT / rel
            if not path.is_file():
                raise RuntimeError(f"Required production or test source is missing: {rel}")
            report["sourceSha256"][rel] = sha(path)
        java = shutil.which("java")
        if not java:
            raise RuntimeError("Java runtime not found (need JDK 17 or newer)")
        version = subprocess.run([java, "-version"], capture_output=True, text=True)
        report["runner"]["java"] = (version.stderr or version.stdout).strip()
        log("== Public dependency bootstrap (pinned Maven coordinates; published sidecar verified) ==")
        jars, report["artifactChecksums"] = download_artifacts()
        with tempfile.TemporaryDirectory(prefix="rikka-port-regressions-") as temp:
            tempdir = pathlib.Path(temp)
            sources = tempdir / "src"
            classes = tempdir / "classes"
            classes.mkdir()
            generated = tempdir / "generated"
            generated.mkdir()
            extracted, boundary = extract_step_status(TOOL_STEP_SOURCE)
            (generated / "ToolStepStatus.kt").write_text(extracted, encoding="utf-8")
            report["extractions"] = [boundary]
            fast_util = generated / "FastForEachIndexed.kt"
            fast_util.write_text(
                """package androidx.compose.ui.util\n\n"""
                """/** JVM-only compatibility shim for Compose's inline collection helper. */\n"""
                """inline fun <T> List<T>.fastForEachIndexed(action: (Int, T) -> Unit) {\n"""
                """    for (index in indices) action(index, this[index])\n""" """}\n""",
                encoding="utf-8",
            )
            source_paths = [ROOT / p for p in PRODUCTION + TESTS]
            source_paths += [generated / "ToolStepStatus.kt", fast_util]
            # Explicit classpath lists avoid wildcard expansion order differences.
            compiler_cp = os.pathsep.join(str(jars[x]) for x in [
                "kotlin-compiler-embeddable-2.4.20.jar", "kotlin-stdlib-2.4.20.jar",
                "kotlin-script-runtime-2.4.20.jar", "kotlin-reflect-1.6.10.jar",
                "kotlin-daemon-embeddable-2.4.20.jar", "kotlinx-coroutines-core-jvm-1.8.0.jar",
                "trove4j-1.0.20200330.jar", "annotations-24.1.0.jar",
            ])
            serialization = jars["kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar"]
            runtime_files = [
                "kotlin-stdlib-2.4.20.jar", "annotations-24.1.0.jar",
                "kotlinx-serialization-core-jvm-1.11.0.jar", "kotlinx-serialization-json-jvm-1.11.0.jar",
                "kotlinx-datetime-jvm-0.8.0.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar",
            ]
            runtime_cp = os.pathsep.join(str(jars[x]) for x in runtime_files)
            compile_cmd = [
                java, "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-opt-in=kotlin.time.ExperimentalTime",
                "-Xplugin=" + str(serialization), "-classpath", runtime_cp, "-d", str(classes),
                *map(str, source_paths),
            ]
            report["commands"].append(["java", "-cp", "<pinned compiler jars>", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", *compile_cmd[5:]])
            log("== Compile actual selected production + JUnit sources with Kotlin 2.4.20 and serialization compiler plugin ==")
            log("Sources: " + ", ".join(str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else p.name for p in source_paths))
            compiled = subprocess.run(compile_cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if compiled.stdout:
                log_lines.extend(compiled.stdout.rstrip().splitlines())
                print(compiled.stdout, end="", flush=True)
            if compiled.returncode:
                raise RuntimeError(f"Kotlin compilation failed with exit {compiled.returncode}")
            test_classes = [
                "me.rerere.ai.core.UsageTest",
                "me.rerere.rikkahub.ui.components.message.GroupMessagePartsTest",
                "me.rerere.rikkahub.ui.components.message.MessagePartGroupingTest",
                "me.rerere.rikkahub.ui.components.message.tools.ToolStepStatusTest",
            ]
            run_cp = os.pathsep.join([str(classes), runtime_cp])
            run_cmd = [java, "-cp", run_cp, "org.junit.runner.JUnitCore", *test_classes]
            report["commands"].append(["java", "-cp", "<compiled classes and pinned test runtime>", "org.junit.runner.JUnitCore", *test_classes])
            report["tests"] = test_classes
            log("== Execute JUnit regression classes ==")
            executed = subprocess.run(run_cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if executed.stdout:
                log_lines.extend(executed.stdout.rstrip().splitlines())
                print(executed.stdout, end="", flush=True)
            if executed.returncode:
                raise RuntimeError(f"JUnit exited {executed.returncode}")
            import re
            match = re.search(r"OK \((\d+) tests?\)", executed.stdout)
            if not match or int(match.group(1)) <= 0:
                raise RuntimeError("JUnit did not report a positive executed-test count; refusing a no-test success")
            report["testCount"] = int(match.group(1))
        report["result"] = "passed"
        log(f"PASS: {report['testCount']} JUnit tests executed")
        return_code = 0
    except Exception as e:
        report["error"] = str(e)
        log(f"FAIL: {e}")
        return_code = 1
    finally:
        report["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        report["exitCode"] = return_code if "return_code" in locals() else 1
        (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (OUT / "run.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")
        summary = [
            "# Port regression runner evidence", "", f"- Result: **{report['result']}**",
            f"- Executed JUnit tests: **{report.get('testCount', 0)}**",
            f"- Started (UTC): {report['startedUtc']}", f"- Finished (UTC): {report['finishedUtc']}",
            "- See `report.json` for exact production/test source SHA-256, extraction boundary, commands, and Maven artifact checksums.",
            "- See `run.log` for compiler and JUnit output.", "", "## Scope and limitations", "",
            *[f"- {item}" for item in report["limitations"]],
        ]
        (OUT / "README.md").write_text("\n".join(summary) + "\n", encoding="utf-8")
    return return_code


if __name__ == "__main__":
    sys.exit(main())
