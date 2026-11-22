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
    # 品牌 / 厂商专有名词：出现在“名称 → slug”映射表里，不是面向用户的可译文案。
    # 这些值只用于按名字挑图标文件，翻成别的语言反而匹配不到（见 utils/AIIconMatcher.kt）。
    "阿里通义", "通义千问", "通义", "千问", "阿里云", "阿里",
    "腾讯混元", "混元", "腾讯云", "腾讯",
    "百度文心", "文心一言", "文心", "百度云", "百度",
    "字节跳动", "字节", "讯飞星火", "讯飞", "火山方舟", "火山引擎", "豆包",
    "智谱清言", "智谱", "月之暗面", "深度求索", "百川智能", "百川",
    "书生浦语", "书生", "浦语", "昆仑万维", "天工", "零一万物",
    "无问芯穹", "硅基流动", "秘塔", "商汤", "阶跃星辰", "阶跃",
    "魔搭", "超算互联网", "华为云", "华为",
    "七牛云", "七牛",
    "百度千帆",
    "千帆",
    "面壁智能",
    "面壁",
    "澜舟科技",
    "澜舟",
    "生数科技",
    "生数",
    "智象未来",
    "智象",
    "潞晨科技",
    "潞晨云",
    "潞晨",
    "深言科技",
    "深言",
    "沐曦",
    "燧原科技",
    "燧原",
    "寒武纪",
    "壁仞科技",
    "壁仞",
    "摩尔线程",
    "天数智芯",
    "天数",
    "快手可灵",
    "可灵",
    "字节即梦",
    "即梦",
    "海螺",
    "京东云",
    "京东",
    "移动云",
    "中国移动",
    "电信云",
    "中国电信",
    "联通云",
    "中国联通",
    "优刻得",
    "金山云",
    "并行科技",
    "并行",
    # 品牌专名：同为「名称 → slug」映射表里的键，翻成别的语言匹配不到对应图标
    "阿里云百炼",
    "百炼",
    "随想",
    # 英文产品名 / 平台名：ASR provider 名称与 OS 标签，属标识符不翻译。
    # 白名单按「整串去干净才算放行」，所以 "Step 1 of 3"、"DashScope 语音" 这类仍会被报。
    "DashScope", "Volcengine", "MiMo", "Step", "Linux", "Windows",
)

# Design tokens are colour expressions, not prose: hsl()/rgb()/oklch()/#RRGGBB.
DESIGN_TOKEN_RE = re.compile(
    r"^(?:hsl|hsla|hsv|rgb|rgba|oklch|oklab|lab)\(|^#[0-9A-Fa-f]{3,8}$"
)

# Diagnostic log calls: AppLog / AppLogger / android.util.Log prose is developer-facing
# and never UI text, including multi-line argument lists.
LOG_CALL_RE = re.compile(
    r"(?:AppLog(?:ger)?|(?:android\.util\.)?Log)\s*\.\s*(?:v|d|i|w|e|wtf)\s*\("
)

UI_TRIGGER_RE = re.compile(
    r"(?<![A-Za-z0-9_.])"
    r"(Text\(|title =|description =|label =|contentDescription =|toaster\.show\()"
)
# Skip literals that are clearly not UI prose: URLs, absolute paths, home
# paths, ALL_CAPS constants, and bare identifiers.
PROTOCOL = re.compile(
    r"(https?://|/data/|~/|[A-Z_]{3,}|allow-external-apps|termux\.properties|^[a-z0-9_.-]+$)"
)


def git_diff(base, head, paths):
    """head 为 None 时比较 base 与工作区（含未提交改动），供本地提交前自检用。"""
    args = ["git", "diff", "--unified=0", base]
    if head is not None:
        args.append(head)
    args += ["--", *paths]
    return subprocess.check_output(args, text=True, errors="ignore")


def check_resource_zh(diff):
    keys = {
        m.group(1)
        for line in diff.splitlines()
        if line.startswith("+") and 'translatable="false"' not in line
        for m in [re.search(r'<string name="([^"]+)"', line)]
        if m
    }
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
            if PROTOCOL.search(v) or DESIGN_TOKEN_RE.match(v):
                continue
            # 品牌 / 平台标识符：去掉白名单后没有剩余内容就不算可译文案
            stripped = v
            for tok in ALLOWLIST_TOKENS:
                stripped = stripped.replace(tok, "")
            if not stripped.strip():
                continue
            missing.append("hardcoded UI text: " + v)
    return missing


def extract_string_literals(added_lines):
    added_lines = [ln for ln in added_lines if not re.match(r"^\s*\*(\s|$)", ln)]
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


def strip_log_calls(lines):
    """Blank log-call spans (AppLog / AppLogger / android.util.Log) keeping line count.

    Logs are developer-facing, not user copy: the project writes them in Chinese
    throughout, so those literals must not trip the localization gate. Only the log
    call span is blanked — UI text on the same line is still checked. Multi-line
    argument lists are handled by bracket matching that skips brackets inside strings.
    """
    src = "\n".join(lines)
    out = list(src)
    n = len(src)
    for m in LOG_CALL_RE.finditer(src):
        i = src.find("(", m.start())
        if i < 0:
            continue
        depth = 0
        j = i
        while j < n:
            c = src[j]
            if c == '"':
                j += 1
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        for k in range(m.start(), min(j + 1, n)):
            if out[k] != "\n":
                out[k] = " "
    return "".join(out).split("\n")


def check_cjk_literals(diff):
    """Per-file: collect added lines, extract literals, flag remaining CJK."""
    missing = []
    current = None
    added = {}

    def flush():
        if current is None:
            return
        for lit in extract_string_literals(strip_log_calls(added[current])):
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
    head: str | None = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
    # 传 WORKTREE（或 -）表示比较「base 与工作区」，本地提交前自检用：
    # 这样未提交的改动也能查，不必先 commit 一遍再回滚。
    if head in ("WORKTREE", "-"):
        head = None
    missing = []
    missing += check_resource_zh(
        git_diff(base, head, ["*/src/main/res/values/strings.xml"]))
    # 测试断言里的中文不是用户文案，与 src/test 同口径排除 androidTest；
    # 否则新增一条仪器测试就会因断言中文被门禁拦（改生产文案时仍需同步 grep 断言）。
    kd = git_diff(
        base, head, ["*.kt", ":!*/src/test/*", ":!*/src/androidTest/*", ":!stale-tests/*"]
    )
    missing += check_ui_literals(kd)
    missing += check_cjk_literals(kd)
    if missing:
        print("\n".join(sorted(set(missing))))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
