#!/usr/bin/env python3
"""Validate newly added English resource keys against all seven supported locales.

Reads the actual worktree, not HEAD, so local uncommitted integration is checked.
This is a static XML/format check, not Android rendering or translation approval.
"""
import argparse
import collections
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LOCALES = ('values', 'values-zh', 'values-b+zh+Hant', 'values-ja', 'values-ko-rKR', 'values-ru', 'values-ar')
REQUIRED = {
    'chain_of_thought_collapsed', 'chain_of_thought_expanded',
    'chain_of_thought_running', 'chain_of_thought_step_count',
    'chat_nerd_latest_usage', 'chat_nerd_message_total',
    'chat_nerd_model_request_count', 'tool_status_interrupted',
    'search_detail_tavily_include_answer', 'search_detail_tavily_include_answer_desc',
}
FORMAT = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])')


def resources(text):
    tree = ET.fromstring(text)
    nodes = [n for n in tree if n.tag in ('string', 'plurals', 'string-array') and n.get('name')]
    duplicates = [name for name, count in collections.Counter(n.get('name') for n in nodes).items() if count > 1]
    return {n.get('name'): n for n in nodes}, duplicates


def formats(node):
    return sorted((index or '1', kind) for index, kind in FORMAT.findall(''.join(node.itertext())) if kind != '%')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='HEAD')
    parser.add_argument('--output')
    args = parser.parse_args()
    rel = 'app/src/main/res/values/strings.xml'
    old, _ = resources(subprocess.check_output(['git', 'show', f'{args.base}:{rel}'], cwd=ROOT, text=True))
    english, _ = resources((ROOT / rel).read_text())
    keys = sorted((set(english) - set(old)) | REQUIRED)
    errors = [f'values: missing required {key}' for key in keys if key not in english]
    keys = [key for key in keys if key in english]
    checked = {}
    for locale in LOCALES:
        try:
            current, duplicate = resources((ROOT / f'app/src/main/res/{locale}/strings.xml').read_text())
            errors.extend(f'{locale}: duplicate {key}' for key in duplicate)
            for key in keys:
                ref, node = english[key], current.get(key)
                if node is None:
                    errors.append(f'{locale}: missing {key}')
                    continue
                if node.tag != ref.tag:
                    errors.append(f'{locale}: type mismatch {key}')
                if ref.tag == 'plurals':
                    if not any(item.get('quantity') == 'other' for item in node):
                        errors.append(f'{locale}: missing other plural {key}')
                    expected = formats(next(iter(ref)))
                    for item in node:
                        actual = formats(item)
                        # Arabic zero/one/two forms can spell out the count; Android
                        # safely ignores the unused format argument in these forms.
                        implicit_count = locale == 'values-ar' and item.get('quantity') in ('zero', 'one', 'two') and not actual
                        if actual != expected and not implicit_count:
                            errors.append(f'{locale}: format mismatch {key}/{item.get("quantity")}')
                elif formats(node) != formats(ref):
                    errors.append(f'{locale}: format mismatch {key}')
            checked[locale] = len(keys)
        except (ET.ParseError, OSError) as exc:
            errors.append(f'{locale}: {exc}')
    report = {'base': args.base, 'scope': 'static worktree XML/new-key coverage/printf placeholders; not Android rendering', 'keys': keys, 'locales': checked, 'errors': errors, 'passed': not errors}
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        Path(args.output).write_text(text)
    print(text, end='')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
