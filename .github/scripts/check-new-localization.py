#!/usr/bin/env python3
"""Block newly added hardcoded UI text in Kotlin and missing zh strings.

Usage: check-new-localization.py <base> [head]
- base/head are git revisions (base defaults to the merge-base in CI usage;
  the workflow fetches base by SHA first because the checkout is shallow).
- Part 1: keys added to */src/main/res/values/strings.xml must exist in
  values-zh (or values-zh-rCN).
- Part 2: added Kotlin lines that put a literal into a UI slot
  (Text( / title = / description = / label = / contentDescription = /
  toaster.show() must not contain a hardcoded English literal.
- Part 3: any string literal added in Kotlin must not contain CJK
  characters, except the allowlisted identifier tokens below (preset keys
  the model must type verbatim, and the `# SSH公钥:` file-format marker).
  Comments are not string literals and are ignored.
- Only *added* lines are checked: pre-existing hardcoded text is
  grandfathered, so this script never blocks unrelated old code.
"""
import re
import subprocess
import sys
from pathlib import Path

CJK = re.compile(r"[\u4e00-\u9fff]")

# Identifier tokens that must stay verbatim (never translated): model-facing
# preset keys and the load-creds file-format marker. A literal is allowed
# only if no CJK remains after removing these tokens.
ALLOWLIST_TOKENS = (
    "系统信息", "磁盘", "内存", "进程", "最近日志", "服务列表",
    "# SSH公钥:", "SSH公钥",
)

UI_TRIGGER_RE = re.compile(
    r"(?<![A-Za-z0-9_])"
    r"(Text\(|title =|description =|label =|contentDescription =|toaster\.show\()"
)
# Skip literals that are clearly not UI prose: URLs, absolute paths, home
# paths, ALL_CAPS constants, and bare identifiers.
PROTOCOL = re.compile(
    r"(https?://|/data/|~/|[A-Z_]{3,}|allow-external-apps|termux\.properties|^[a-z0-9_.-]+$)"
)


def git_diff(base, head, paths):
    return subprocess.check_output(
        ["git", "diff", "--unified=0", base, head, "--", *paths],
        text=True, errors="ignore",
    )


def check_resource_zh(diff):
    keys = set(re.findall(r'^\+.*?<string name="([^"]+)"', diff, re.M))
    zh = "\n".join(
        p.read_text(errors="ignore")
        for p in (list(Path(".").glob("*/src/main/res/values-zh/strings.xml"))
                  + list(Path(".").glob("*/src/main/res/values-zh-rCN/strings.xml")))
    )
    return ["missing zh-CN string: " + k for k in sorted(keys)
            if not re.search(r'<string name="' + re.escape(k) + r'"', zh)]


def check_ui_literals(diff):
    missing = []
    for line in diff.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        if not UI_TRIGGER_RE.search(line):
            continue
        for v in re.findall(r'"([A-Za-z][^"\\]{2,})"', line):
            if not PROTOCOL.search(v):
                missing.append("hardcoded UI text: " + v)
    return missing


def extract_string_literals(added_lines):
    '''Yield the contents of string literals in added-line text.

    Mini tokenizer: skips // and /* */ comments and char literals, handles
    "..." escapes and triple-quoted raw strings, and ${...} templates (the
    outer literal's span covers nested literals). An unterminated literal
    at the end of the added lines is still yielded: a raw string whose
    close sits in unchanged context still exposes its added content.
    '''
    src = "\n".join(added_lines)
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c == "/" and nxt == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
            continue
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                end = n if j < 0 else j
                out.append(src[i + 3:end])
                i = n if j < 0 else j + 3
                continue
            j = i + 1
            buf = []
            while j < n and src[j] != '"':
                if src[j] == "\\" and j + 1 < n:
                    buf.append(src[j:j + 2])
                    j += 2
                    continue
                if src[j] == "\n":
                    break
                buf.append(src[j])
                j += 1
            out.append("".join(buf))
            i = j + 1
            continue
        i += 1
    return out


def check_cjk_literals(diff):
    """Per-file: collect added lines, extract literals, flag remaining CJK."""
    missing = []
    current = None
    added = {}

    def flush():
        if current is None:
            return
        for lit in extract_string_literals(added[current]):
            stripped = lit
            for tok in ALLOWLIST_TOKENS:
                stripped = stripped.replace(tok, "")
            if CJK.search(stripped):
                missing.append(
                    "hardcoded Chinese text in %s: %s"
                    % (current, lit[:80].replace("\n", "\\n"))
                )

    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            flush()
            current = line[6:]
            added[current] = []
        elif line.startswith("+") and not line.startswith("+++") and current:
            added[current].append(line[1:])
    flush()
    return missing


def main():
    base = sys.argv[1]
    head = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
    missing = []
    missing += check_resource_zh(
        git_diff(base, head, ["*/src/main/res/values/strings.xml"]))
    kd = git_diff(base, head, ["*.kt"])
    missing += check_ui_literals(kd)
    missing += check_cjk_literals(kd)
    if missing:
        print("\n".join(sorted(set(missing))))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
