#!/usr/bin/env python3
"""Android Locale Manager TUI Application."""

import sys
import asyncio
import builtins
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent))

import click
from config import Config
from app import LocaleTuiApp
from services.xml_parser import StringsXmlParser
from services.translator import AITranslator
from models.entry import TranslationEntry
from services.validation import escape_android_quotes, validate_translation


def load_config() -> Config:
    """Load configuration from file."""
    config_path = Path(__file__).parent.parent / "config.yml"

    if not config_path.exists():
        click.echo(f"错误：未找到配置文件 {config_path}", err=True)
        click.echo("请基于模板创建 config.yml 文件。", err=True)
        sys.exit(1)

    try:
        config = Config.load(config_path)
    except Exception as e:
        click.echo(f"错误：加载配置失败 - {e}", err=True)
        sys.exit(1)

    # Validate configuration
    if not config.openai_api_key:
        click.echo("警告：未设置 OPENAI_API_KEY。AI 翻译功能将无法使用。", err=True)

    return config


@click.group(invoke_without_command=True)
@click.pass_context
def cli(ctx):
    """Android Locale Manager - 管理和翻译 Android 字符串资源

    不带参数启动 TUI 界面，使用子命令进行命令行操作。
    """
    if ctx.invoked_subcommand is None:
        # No command provided, launch TUI
        config = load_config()
        app = LocaleTuiApp(config)
        app.run()


@cli.command("test-connection")
def test_connection():
    """测试 AI 服务连接

    \b
    示例：
        locale-tui test-connection
    """
    config = load_config()

    if not config.openai_api_key:
        click.echo("错误：未设置 OPENAI_API_KEY，无法测试连接。", err=True)
        sys.exit(1)

    click.echo("AI 服务配置：")
    click.echo(f"  Base URL: {config.openai_base_url}")
    click.echo(f"  Model: {config.translation_model}")
    click.echo("正在测试连接...")

    async def test_async():
        translator = AITranslator(config)
        return await translator.test_connection()

    try:
        content = asyncio.run(test_async())
        click.echo("✓ 连接成功")
        if content:
            click.echo(f"响应: {content}")
        else:
            click.echo("响应为空，但 API 已返回有效结果。")
    except Exception as e:
        click.echo(f"✗ 连接失败: {e}", err=True)
        sys.exit(1)


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
@click.option("--skip-translate", is_flag=True, help="跳过自动翻译，仅添加源语言条目")
def add(key: str, value: str, module: str, skip_translate: bool):
    """添加新的语言条目并自动翻译

    \b
    示例：
        locale-tui add hello_world "Hello, World!"
        locale-tui add greeting "Welcome" -m app
        locale-tui add test_key "Test" --skip-translate
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    click.echo(f"使用模块: {selected_module.name}")

    # Get source language
    source_lang = config.get_source_language()
    if not source_lang:
        click.echo("错误：未配置源语言", err=True)
        sys.exit(1)

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"错误：资源目录不存在 {res_dir}", err=True)
        sys.exit(1)

    # Add entry to source language file
    source_file = res_dir / "values" / "strings.xml"
    click.echo(f"添加条目到 {source_file.relative_to(config.project_root)}...")

    try:
        StringsXmlParser.update_entry(source_file, key, value)
        click.echo(f"✓ 已添加条目: {key} = {value}")
    except Exception as e:
        click.echo(f"错误：添加条目失败 - {e}", err=True)
        sys.exit(1)

    # Translate to other languages
    if not skip_translate:
        target_languages = [lang.code for lang in config.languages if not lang.is_source]

        if not target_languages:
            click.echo("未配置目标语言，跳过翻译。")
            return

        click.echo(f"开始翻译到 {len(target_languages)} 种语言...")

        # Create entry for translation
        entry = TranslationEntry(key=key, translations={"values": value})

        async def translate_async():
            translator = AITranslator(config)

            async def translate_one(lang_code: str):
                lang_name = config.get_language_name(lang_code)

                try:
                    translations = await translator.translate_batch(
                        {key: value}, lang_name
                    )

                    if key in translations:
                        return lang_code, lang_name, translations[key], None
                    return lang_code, lang_name, None, "翻译失败（未返回结果）"
                except Exception as e:
                    return lang_code, lang_name, None, str(e)

            tasks = [translate_one(lang_code) for lang_code in target_languages]
            results = await asyncio.gather(*tasks)

            for lang_code, lang_name, translated_value, error in results:
                click.echo(f"翻译到 {lang_name}...", nl=False)

                if error:
                    click.echo(f" ✗ 错误: {error}", err=True)
                    continue

                entry.set_translation(lang_code, translated_value)

                # Save to file
                target_file = res_dir / lang_code / "strings.xml"
                StringsXmlParser.update_entry(target_file, key, translated_value)

                click.echo(f" ✓ {translated_value}")

        asyncio.run(translate_async())
        click.echo("完成！")


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--lang",
    "-l",
    default=None,
    help="语言代码（例如：values, values-zh, values-ja），默认为源语言",
)
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
def set(key: str, value: str, lang: str, module: str):
    """手动设置指定语言的条目值

    \b
    示例：
        locale-tui set hello_world "你好，世界！" -l values-zh
        locale-tui set greeting "Welcome" -l values
        locale-tui set test_key "テスト" -l values-ja -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve language directory
    if lang is None:
        lang = "values"  # Default to source language

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"错误：资源目录不存在 {res_dir}", err=True)
        sys.exit(1)

    # Target file
    target_file = res_dir / lang / "strings.xml"
    lang_name = config.get_language_name(lang) if lang != "values" else "源语言"

    click.echo(f"设置 {lang_name} 的条目: {key} = {value}")
    click.echo(f"目标文件: {target_file.relative_to(config.project_root)}")

    try:
        StringsXmlParser.update_entry(target_file, key, value)
        click.echo(f"✓ 设置成功")
    except Exception as e:
        click.echo(f"错误：设置失败 - {e}", err=True)
        sys.exit(1)


@cli.command()
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
def list_keys(module: str):
    """列出所有语言条目的键

    \b
    示例：
        locale-tui list-keys
        locale-tui list-keys -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    source_file = res_dir / "values" / "strings.xml"

    if not source_file.exists():
        click.echo(f"错误：源文件不存在 {source_file}", err=True)
        sys.exit(1)

    # Parse and display
    entries = StringsXmlParser.parse(source_file)

    click.echo(f"模块 '{selected_module.name}' 共有 {len(entries)} 个条目：")
    click.echo()

    for key in sorted(entries.keys()):
        value = entries[key]
        # Truncate long values
        if len(value) > 60:
            value = value[:57] + "..."
        click.echo(f"  {key:40} {value}")


async def _translate_selected(config, module, lang_code, keys, retries):
    """Translate selected strings and return only placeholder-safe key/value pairs."""
    from services.validation import validate_translation

    language = config.get_language_name(lang_code)
    translator = AITranslator(config)
    source_file = config.project_root / module.res_path / "values" / "strings.xml"
    target_file = config.project_root / module.res_path / lang_code / "strings.xml"
    source = StringsXmlParser.parse(source_file)
    requested = {key: source[key] for key in keys if key in source}
    successful = {}
    failed = {}
    size = max(1, config.batch_size)
    keys_to_translate = list(requested)
    for start in range(0, len(keys_to_translate), size):
        batch_keys = keys_to_translate[start:start + size]
        remaining = {key: requested[key] for key in batch_keys}
        for attempt in range(retries + 1):
            if not remaining:
                break
            try:
                response = await translator.translate_batch(remaining, language)
            except Exception as error:
                if attempt == retries:
                    failed.update({key: str(error) for key in remaining})
                    break
                continue
            for key in list(remaining):
                value = response.get(key) if isinstance(response, dict) else None
                issue = validate_translation(remaining[key], value) if isinstance(value, str) else "missing translation"
                if issue:
                    if attempt == retries:
                        failed[key] = issue
                    continue
                successful[key] = escape_android_quotes(value)
                del remaining[key]
            if attempt == retries:
                for key in remaining:
                    failed.setdefault(key, "missing or invalid translation")
    return successful, failed, target_file


def _validate_selection(config, module_names, lang_codes):
    known_langs = {lang.code for lang in config.languages if not lang.is_source}
    unknown_langs = builtins.set(lang_codes) - known_langs
    if unknown_langs:
        raise click.ClickException(f"未知语言: {', '.join(sorted(unknown_langs))}")
    known_modules = {module.name for module in config.modules}
    unknown_modules = builtins.set(module_names) - known_modules
    if unknown_modules:
        raise click.ClickException(f"未知模块: {', '.join(sorted(unknown_modules))}")
    return ([module for module in config.modules if not module_names or module.name in module_names],
            list(lang_codes) or [lang.code for lang in config.languages if not lang.is_source])


@cli.command("translate-missing")
@click.option("--lang", "langs", multiple=True, help="目标语言代码，可重复")
@click.option("--module", "module_names", multiple=True, help="模块名称，可重复")
@click.option("--keys", "keys", multiple=True, help="仅处理指定资源key，可重复")
@click.option("--retries", type=click.IntRange(min=0), default=2, show_default=True)
@click.option("--dry-run", is_flag=True, help="仅统计，不调用翻译器或写入")
@click.option("--no-write", is_flag=True, help="调用翻译器并校验，但不写文件")
def translate_missing(langs, module_names, keys, retries, dry_run, no_write):
    """Batch-translate absent strings; existing destination values are never overwritten."""
    config = load_config()
    modules, target_langs = _validate_selection(config, module_names, langs)
    work = []
    for module in modules:
        source = StringsXmlParser.parse(config.project_root / module.res_path / "values" / "strings.xml")
        for lang_code in target_langs:
            target_path = config.project_root / module.res_path / lang_code / "strings.xml"
            existing = StringsXmlParser.parse(target_path)
            selected = builtins.set(keys) if keys else builtins.set(source)
            missing = [key for key in source if key in selected and key not in existing]
            click.echo(f"{module.name}/{lang_code}: {len(missing)} missing")
            if missing:
                work.append((module, lang_code, missing))
    if dry_run or not work:
        return
    if not config.openai_api_key:
        raise click.ClickException("未设置 OPENAI_API_KEY；dry-run无需密钥")

    async def run():
        errors = []
        for module, lang_code, missing in work:
            translated, failed, target_path = await _translate_selected(
                config, module, lang_code, missing, retries
            )
            if translated and not no_write:
                added = StringsXmlParser.update_entries(target_path, translated)
                click.echo(f"{module.name}/{lang_code}: 写入 {len(added)} 条")
            else:
                click.echo(f"{module.name}/{lang_code}: 校验通过 {len(translated)} 条（未写入）")
            errors.extend((module.name, lang_code, key, message) for key, message in failed.items())
        return errors

    errors = asyncio.run(run())
    for module_name, lang_code, key, message in errors:
        click.echo(f"翻译失败 {module_name}/{lang_code} {key}: {message}", err=True)
    if errors:
        raise click.ClickException(f"{len(errors)} 条未写入，原资源文件保持安全")


@cli.command("retranslate")
@click.option("--lang", "langs", multiple=True, required=True, help="目标语言代码，可重复")
@click.option("--module", "module_names", multiple=True, help="模块名称，可重复")
@click.option("--keys", "keys", multiple=True, required=True, help="要重译的资源key，可重复")
@click.option("--retries", type=click.IntRange(min=0), default=2, show_default=True)
@click.option("--dry-run", is_flag=True, help="仅核对指定key，不调用翻译器或写入")
@click.option("--no-write", is_flag=True, help="调用翻译器并校验，但不写文件")
def retranslate(langs, module_names, keys, retries, dry_run, no_write):
    """Re-translate only explicitly named keys for selected languages."""
    config = load_config()
    modules, target_langs = _validate_selection(config, module_names, langs)
    work = []
    for module in modules:
        source = StringsXmlParser.parse(config.project_root / module.res_path / "values" / "strings.xml")
        target_path = config.project_root / module.res_path / "values" / target_langs[0] / "strings.xml"
        for lang_code in target_langs:
            target_path = config.project_root / module.res_path / lang_code / "strings.xml"
            missing_source = builtins.set(keys) - builtins.set(source)
            if missing_source:
                raise click.ClickException(f"{module.name}: 源资源不存在: {', '.join(sorted(missing_source))}")
            existing = StringsXmlParser.parse(target_path)
            absent = builtins.set(keys) - builtins.set(existing)
            if absent:
                raise click.ClickException(f"{module.name}/{lang_code}: 目标资源不存在: {', '.join(sorted(absent))}；请使用translate-missing")
            click.echo(f"{module.name}/{lang_code}: 将重译 {len(keys)} 个指定key")
            work.append((module, lang_code, list(keys)))
    if dry_run or not work:
        return
    if not config.openai_api_key:
        raise click.ClickException("未设置 OPENAI_API_KEY；dry-run无需密钥")

    async def run():
        errors = []
        for module, lang_code, selected in work:
            translated, failed, target_path = await _translate_selected(config, module, lang_code, selected, retries)
            if translated and not no_write:
                updated = StringsXmlParser.replace_entries(target_path, translated)
                click.echo(f"{module.name}/{lang_code}: 重译 {len(updated)} 条")
            else:
                click.echo(f"{module.name}/{lang_code}: 校验通过 {len(translated)} 条（未写入）")
            errors.extend((module.name, lang_code, key, message) for key, message in failed.items())
        return errors

    errors = asyncio.run(run())
    for module_name, lang_code, key, message in errors:
        click.echo(f"重译失败 {module_name}/{lang_code} {key}: {message}", err=True)
    if errors:
        raise click.ClickException(f"{len(errors)} 条未写入")


def main():
    """Main entry point."""
    cli()


if __name__ == "__main__":
    main()
