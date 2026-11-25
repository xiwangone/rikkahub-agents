#!/usr/bin/env python3
"""AI-driven Android Emulator / Device Deep UI & Stress Test Runner (ADB + VLM)."""
import argparse
import base64
import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET


def adb(serial, *args, binary=False):
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    res = subprocess.run(cmd, capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr.decode(errors='replace')}")
    return res.stdout if binary else res.stdout.decode("utf-8", errors="replace")


def get_pid(serial, package):
    res = subprocess.run(
        (["adb", "-s", serial] if serial else ["adb"]) + ["shell", "pidof", package],
        capture_output=True, text=True,
    )
    return res.stdout.strip().split()[0] if res.returncode == 0 and res.stdout.strip() else ""


def reset_gfx_and_logs(serial, package):
    subprocess.run((["adb", "-s", serial] if serial else ["adb"]) + ["shell", "dumpsys", "gfxinfo", package, "reset"], capture_output=True)
    subprocess.run((["adb", "-s", serial] if serial else ["adb"]) + ["logcat", "-c"], capture_output=True)


def collect_telemetry(serial, package):
    gfx = adb(serial, "shell", "dumpsys", "gfxinfo", package)
    janky_m = re.search(r"Janky frames:\s*(\d+)\s*\(([^)]+)\)", gfx)
    p95_m = re.search(r"95th percentile:\s*(\d+ms)", gfx)
    p99_m = re.search(r"99th percentile:\s*(\d+ms)", gfx)

    mem = adb(serial, "shell", "dumpsys", "meminfo", package)
    pss_m = re.search(r"TOTAL PSS:\s*(\d+)", mem)

    logs = adb(serial, "logcat", "-d", "-v", "time", "AndroidRuntime:E", "*:S")
    has_crash = "FATAL EXCEPTION" in logs or "Process: " + package in logs
    return {
        "janky": f"{janky_m.group(1)} ({janky_m.group(2)})" if janky_m else "n/a",
        "p95": p95_m.group(1) if p95_m else "n/a",
        "p99": p99_m.group(1) if p99_m else "n/a",
        "total_pss_kb": int(pss_m.group(1)) if pss_m else 0,
        "crash_log": logs[-1200:] if has_crash else "",
    }


def run_stress_burst(serial, nodes):
    """Execute rapid flings + rapid clickable taps + search churn to expose races/jank."""
    # Rapid vertical flings
    for _ in range(3):
        adb(serial, "shell", "input", "swipe", "540", "1600", "540", "500", "120")
        adb(serial, "shell", "input", "swipe", "540", "600", "540", "1600", "120")
    # Rapidly tap toggle/filter/button nodes if present (skip destructive keywords
    # and confirmation buttons: tapping e.g.「确定」in a delete dialog would
    # actually execute the removal, especially in provider_batch_remove_dialog)
    skip_words = ("delete", "remove", "clear", "reset", "删除", "清空", "重置", "移除", "确定", "确认", "ok")
    tapped = 0
    for n in nodes:
        if not n["clickable"]:
            continue
        label = (n["text"] + " " + n["desc"]).lower()
        if any(w in label for w in skip_words):
            continue
        cx, cy = n["center"]
        if cy < 200 or cy > 2100:
            continue
        adb(serial, "shell", "input", "tap", str(cx), str(cy))
        tapped += 1
        if tapped >= 3:
            break
    time.sleep(0.5)


def dump_ui_nodes(serial, max_nodes=90):
    raw = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty")
    idx = raw.find("<hierarchy")
    if idx < 0:
        return []
    xml_text = raw[idx:]
    end = xml_text.rfind("</hierarchy>")
    if end >= 0:
        xml_text = xml_text[: end + len("</hierarchy>")]
    root = ET.fromstring(xml_text)
    nodes = []
    for el in root.iter("node"):
        clickable = el.attrib.get("clickable") == "true"
        scrollable = el.attrib.get("scrollable") == "true"
        text = el.attrib.get("text", "").strip()
        desc = el.attrib.get("content-desc", "").strip()
        rid = el.attrib.get("resource-id", "").split("/")[-1]
        bounds = el.attrib.get("bounds", "")
        if not (clickable or scrollable or text or desc):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        nodes.append({
            "text": text,
            "desc": desc,
            "id": rid,
            "clickable": clickable,
            "scrollable": scrollable,
            "center": [(x1 + x2) // 2, (y1 + y2) // 2],
            "bounds": [x1, y1, x2, y2],
        })
        if len(nodes) >= max_nodes:
            break
    return nodes


def capture_png_b64(serial, out_path):
    png = adb(serial, "exec-out", "screencap", "-p", binary=True)
    with open(out_path, "wb") as f:
        f.write(png)
    return base64.b64encode(png).decode("ascii")


def ask_vlm(base_url, api_key, model, goal, step, nodes, png_b64, history, extra_hint=""):
    prompt = (
        f"You are an Android UI QA & Stress agent testing RikkaHub.\n"
        f"Goal: {goal}\n"
        f"Step: {step}\n"
        f"Previous actions: {json.dumps(history[-5:], ensure_ascii=False)}\n"
        f"Visible UI nodes (up to 90, passed in full): {json.dumps(nodes, ensure_ascii=False)}\n\n"
        "Respond with ONLY a single JSON object:\n"
        '{"action": "tap|swipe|input_text|back|wait|stress_burst|pass|fail", '
        '"x": int, "y": int, "x2": int, "y2": int, "text": str, "reason": str}\n'
        "Use stress_burst on dense lists/forms to test rapid scrolling/tapping, pass when verified, or fail if clipped/frozen/crashed."
    )
    if extra_hint:
        prompt += f"\nNote: {extra_hint}"
    payload = {
        "model": model,
        "temperature": 0.1,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{png_b64}"}},
            ],
        }],
    }
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    try:
        raw = data["choices"][0]["message"]["content"].strip()
    except (KeyError, IndexError, TypeError, AttributeError) as exc:
        # 网关回的 200 里没有 choices/message/content：当成一次失败调用走重试，
        # 别让 KeyError 直接崩掉整轮。
        raise ValueError(f"Unexpected model response shape: {exc}")
    m = re.search(r"\{.*\}", raw, re.S)
    if not m:
        raise ValueError(f"Non-JSON model response: {raw}")
    return json.loads(m.group(0))


COORD_MAX = 1080
# Appended to the VLM prompt when a previous decision came back with an
# unusable coordinate, so the re-ask states the format explicitly.
COORD_HINT = "Coordinates (x, y, x2, y2) must be integers between 0 and 1080."


def ask_vlm_with_retries(name, base_url, api_key, model, goal, step, nodes, png_b64, history, extra_hint=""):
    """Ask the VLM once, retrying call failures (max 2 retries, 15s/30s backoff).

    Returns (decision, retries_made). Raises after 3 failed attempts; the
    caller aborts the whole run in that case. Retries are logged, never silent.
    """
    retries_made = 0
    for attempt in range(3):
        try:
            return ask_vlm(base_url, api_key, model, goal, step, nodes, png_b64, history, extra_hint), retries_made
        except (ValueError, urllib.error.URLError, TimeoutError) as exc:
            last_error = str(exc)[:300]
            if attempt < 2:
                retries_made += 1
                # 指数退避（15s、30s）：免费档 429 是按窗口限速，5s 间隔不够它恢复
                delay = 15 * (attempt + 1)
                print(f"[{name}] step {step}: VLM call failed (attempt {attempt + 1}/3), retrying in {delay}s: {last_error}")
                time.sleep(delay)
            else:
                print(f"[{name}] step {step}: VLM call failed after 3 attempts, aborting run: {last_error}")
                raise
    raise AssertionError("unreachable")


def parse_coords(decision, act):
    """Return the integer coordinate list for tap/swipe, or None for other actions.

    Raises KeyError/ValueError/TypeError when a required field is missing, is
    not a number, or is outside 0..COORD_MAX — the caller re-asks the VLM
    (see acquire_valid_decision) instead of failing the scenario outright.
    """
    required = {"tap": ("x", "y"), "swipe": ("x", "y", "x2", "y2")}.get(act)
    if required is None:
        return None
    coords = [int(decision[k]) for k in required]
    if any(c < 0 or c > COORD_MAX for c in coords):
        raise ValueError(f"coordinate out of range 0-{COORD_MAX}: {coords}")
    return coords


def acquire_valid_decision(ask_fn, max_reasks=2):
    """Get a decision whose tap/swipe coordinates parse and are in range.

    ask_fn(reask) returns one decision dict; reask is 0 for the first ask,
    1..max_reasks for re-asks (the caller appends COORD_HINT on re-asks).
    Returns (decision, coords, error): coords is None for non-tap/swipe
    actions; error is None on success, otherwise the last parse exception
    after all re-asks were exhausted. pass/fail decisions are accepted
    immediately without coordinate validation.
    """
    decision = None
    coords = None
    error = None
    for reask in range(max_reasks + 1):
        decision = ask_fn(reask)
        act = decision.get("action", "wait")
        if act in ("pass", "fail"):
            return decision, None, None
        try:
            coords = parse_coords(decision, act)
            return decision, coords, None
        except (KeyError, ValueError, TypeError) as exc:
            error = exc
    return decision, coords, error


def nodes_contain(nodes, needles):
    """True if any node's text/desc contains any of the needle strings."""
    hay = " ".join((n["text"] + " " + n["desc"]) for n in nodes)
    return any(needle in hay for needle in needles)


def is_home_screen(nodes):
    """True if the dump looks like the Chat home screen.

    Markers grounded in a real home dump (p9-patrol-repro/02-newchat.xml):
    the "New Chat" button plus the "Chat History" entry. A drawer left open
    also matches; the drawer belongs to the Chat home, so that is fine.
    """
    return nodes_contain(nodes, ["New Chat"]) and nodes_contain(nodes, ["Chat History"])


def reset_ui_state(serial, package, max_backs=5):
    """Close leftover sheets/dialogs and return to the Chat home screen.

    Sends back until the dump shows the home markers, or max_backs is
    reached. Back on the root screen backgrounds the app; the caller
    re-launches with monkey afterwards, so overshoot is harmless. A sheet
    left open directly over the home screen is not detectable from the
    dump (underlying nodes stay visible) — the VLM can close it in 1-2
    steps; the costly case this fixes is a leftover sheet over some other
    page, which back-until-home does clear. Best-effort: any adb failure
    here is logged and the patrol continues (never aborts the run).
    """
    try:
        for attempt in range(max_backs):
            if is_home_screen(dump_ui_nodes(serial)):
                print(f"reset_ui_state: already home after {attempt} back(s)")
                return True
            adb(serial, "shell", "input", "keyevent", "4")
            time.sleep(0.8)
        home = is_home_screen(dump_ui_nodes(serial))
        print(f"reset_ui_state: {'home' if home else 'NOT home after max backs'}")
        return home
    except Exception as exc:
        print(f"reset_ui_state: failed ({exc}), continuing without reset")
        return False


def run_scenario(serial, package, base_url, api_key, model, scenario, out_dir, max_steps, stress_mode):
    name = scenario["name"]
    goal = scenario["goal"]
    scen_dir = os.path.join(out_dir, name)
    os.makedirs(scen_dir, exist_ok=True)
    # 先关掉上一场景残留的 sheet/对话框、回到 Chat 首页；否则残留状态会
    # 泄漏进本场景开场（本轮代价约 13 步 + 1 个场景全废）。
    reset_ui_state(serial, package)
    reset_gfx_and_logs(serial, package)
    adb(serial, "shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2.0)
    start_pid = get_pid(serial, package)

    history = []
    total_retries = 0
    rejected_passes = 0
    status = "TIMEOUT"
    summary = "Reached max steps"
    for step in range(1, max_steps + 1):
        shot_path = os.path.join(scen_dir, f"step_{step:02d}.png")
        png_b64 = capture_png_b64(serial, shot_path)
        nodes = dump_ui_nodes(serial)
        # 决策获取：VLM 调用失败走重试（最多 2 次，15s/30s 退避）；坐标非法
        # （缺字段、非数字如 "x"、越界）时重问 VLM，最多 2 次重问。
        retry_holder = []

        def ask_once(reask):
            decision, made = ask_vlm_with_retries(
                name, base_url, api_key, model, goal, step, nodes, png_b64, history,
                COORD_HINT if reask else "")
            retry_holder.append(made)
            return decision

        decision, coords, coord_error = acquire_valid_decision(ask_once)
        total_retries += sum(retry_holder)
        act = decision.get("action", "wait")
        reason = decision.get("reason", "")
        if coord_error is not None:
            # 两次重问仍非法：只记本场景 FAIL，不崩整轮，后面场景继续跑。
            status = "FAIL"
            summary = f"Malformed VLM decision for action '{act}': {coord_error}"
            print(f"[{name}] step {step}: malformed decision, failing scenario: {coord_error}")
            break
        history.append({"step": step, "action": act, "reason": reason})
        print(f"[{name}] step {step}: {act} - {reason}")

        if act == "pass":
            # 到达断言（scenario["assert_contains"]）：只判"没崩"会放水——
            # pass 须落在目标页上，否则驳回、让模型继续导航；驳回 2 次后 FAIL。
            asserts = scenario.get("assert_contains") or []
            if asserts and not nodes_contain(nodes, asserts):
                rejected_passes += 1
                reason = f"Pass rejected: target not reached (missing: {asserts}); navigate to the target page first."
                history.append({"step": step, "action": "pass-rejected", "reason": reason})
                print(f"[{name}] step {step}: {reason}")
                if rejected_passes >= 2:
                    status = "FAIL"
                    summary = reason
                    break
                continue
            if stress_mode:
                run_stress_burst(serial, nodes)
            status = "PASS"
            summary = reason
            break
        if act == "fail":
            status = "FAIL"
            summary = reason
            break
        if act == "stress_burst":
            run_stress_burst(serial, nodes)
        elif act == "tap":
            adb(serial, "shell", "input", "tap", str(coords[0]), str(coords[1]))
        elif act == "swipe":
            adb(
                serial, "shell", "input", "swipe",
                str(coords[0]), str(coords[1]),
                str(coords[2]), str(coords[3]), "250",
            )
        elif act == "input_text":
            txt = (decision.get("text") or "").replace(" ", "%s")
            if txt:
                # 文本经 `adb shell` 交设备端 /system/bin/sh 解析：模型回的文本
                # 含括号等 shell 元字符（如模型名 "Gemini Nano (FAST)"）会直接报
                # syntax error 崩掉整轮，先用 shlex.quote 包一层再传。
                adb(serial, "shell", "input", "text", shlex.quote(txt))
            else:
                # 空文本时 `adb shell input text` 无参数会直接报错崩掉整轮：
                # 跳过本次输入，由模型看下一帧自行决定（如点清除按钮）。
                print(f"[{name}] step {step}: empty input_text, skipping adb input")
        elif act == "back":
            adb(serial, "shell", "input", "keyevent", "4")
        time.sleep(1.2)

    tel = collect_telemetry(serial, package)
    end_pid = get_pid(serial, package)
    if tel["crash_log"] or (start_pid and end_pid and start_pid != end_pid) or not end_pid:
        status = "CRASH"
        summary = f"Process died or fatal log detected (pid {start_pid} -> {end_pid})"
        with open(os.path.join(scen_dir, "crash.log"), "w", encoding="utf-8") as cf:
            cf.write(tel["crash_log"])
    return {
        "name": name,
        "status": status,
        "summary": summary,
        "steps": len(history),
        "retries": total_retries,
        "janky": tel["janky"],
        "p95": tel["p95"],
        "pss_kb": tel["total_pss_kb"],
    }


MOCK_HEALTH_URL = os.environ.get("MOCK_HEALTH_URL", "http://127.0.0.1:8080/health")


def check_mock_server(scenarios, allow_no_mock):
    """Pre-flight: scenarios with requires="mock-provider" need the Mock
    provider (OpenAI-compatible, model mock-chat) configured in the app and
    at least one logged request; without it they cannot pass. Probe the mock
    server's /health endpoint. Exits 2 when the mock is down, unless
    --allow-no-mock is given (those scenarios will then fail, loudly).
    """
    needed = [s["name"] for s in scenarios if s.get("requires") == "mock-provider"]
    if not needed:
        return
    try:
        with urllib.request.urlopen(MOCK_HEALTH_URL, timeout=5) as resp:
            ok = resp.status == 200
    except Exception:
        ok = False
    if ok:
        print(f"Pre-flight: mock server OK ({MOCK_HEALTH_URL}); mock-dependent scenarios: {', '.join(needed)}")
        return
    print(f"Pre-flight: mock server NOT reachable at {MOCK_HEALTH_URL}")
    print("  The following scenarios require a Mock provider and cannot pass without it:")
    for name in needed:
        print(f"    - {name}")
    print("  Start the mock server (python3 mock-openai-server.py), create the Mock")
    print("  provider in the app (OpenAI-compatible, baseUrl http://10.0.2.2:8080/v1,")
    print("  model mock-chat), and send one message so at least one request is logged.")
    print("  Or re-run with --allow-no-mock to run anyway (those scenarios will fail).")
    if not allow_no_mock:
        sys.exit(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", default="scenarios.json")
    ap.add_argument("--serial", default="")
    ap.add_argument("--package", default="excp.rikkahub.agents.debug")
    ap.add_argument("--out", default="reports")
    ap.add_argument("--max-steps", type=int, default=14)
    ap.add_argument("--stress", action="store_true", help="Enable rapid fling/tap stress burst & gfxinfo/PSS telemetry")
    ap.add_argument("--allow-no-mock", action="store_true", help="Run even when the mock server is unreachable (mock-dependent scenarios will fail)")
    args = ap.parse_args()

    base_url = os.environ.get("OPENAI_BASE_URL", "")
    api_key = os.environ.get("OPENAI_API_KEY", "")
    model = os.environ.get("MODEL", "gpt-4o-mini")
    if not base_url or not api_key:
        print("Set OPENAI_BASE_URL and OPENAI_API_KEY first.", file=sys.stderr)
        sys.exit(2)

    with open(args.scenarios, "r", encoding="utf-8") as f:
        scenarios = json.load(f)

    check_mock_server(scenarios, args.allow_no_mock)

    os.makedirs(args.out, exist_ok=True)
    results = [
        # Per-scenario step budget: a scenario may set "max_steps" in
        # scenarios.json (used for long flows); default is --max-steps.
        run_scenario(args.serial, args.package, base_url, api_key, model, s, args.out, s.get("max_steps", args.max_steps), args.stress)
        for s in scenarios
    ]
    report_path = os.path.join(args.out, "report.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("# Emulator AI Deep UI & Stress Test Report\n\n")
        f.write("| Scenario | Status | Steps | Retries | Janky Frames | P95 | Total PSS (KB) | Summary |\n")
        f.write("|---|---|---:|---:|---|---|---:|---|\n")
        for r in results:
            f.write(f"| {r['name']} | `{r['status']}` | {r['steps']} | {r['retries']} | {r['janky']} | {r['p95']} | {r['pss_kb']} | {r['summary']} |\n")
    print(f"Report written to {report_path}")
    if any(r["status"] != "PASS" for r in results):
        sys.exit(1)


if __name__ == "__main__":
    main()
