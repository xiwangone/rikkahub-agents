"""Offline tests for glossary prompting and safe Android XML batch writes."""
from pathlib import Path
from types import SimpleNamespace
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from services.translator import AITranslator
from services.validation import escape_android_quotes, validate_translation
from services.xml_parser import StringsXmlParser


class _MockCompletions:
    def __init__(self, content):
        self.content = content
        self.last_request = None

    async def create(self, **kwargs):
        self.last_request = kwargs
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=self.content))])


class _MockClient:
    def __init__(self, content):
        self.chat = SimpleNamespace(completions=_MockCompletions(content))


def _config():
    return SimpleNamespace(
        openai_api_key="", openai_base_url="", project_root=Path("."),
        languages=[SimpleNamespace(code="values-ar", name="Arabic", glossary={"prompt": "مطالبة"})],
        translation_model="offline-test", translation_prompt="Translate to {target_language}: {source_strings}",
        batch_size=3, get_glossary=lambda language: {"prompt": "مطالبة"} if language in ("Arabic", "values-ar") else {},
    )


def test_glossary_is_language_specific_and_prompt_is_sent_to_mock():
    client = _MockClient('{"welcome": "أهلًا"}')
    translator = AITranslator(_config(), client=client)
    result = __import__("asyncio").run(translator.translate_batch({"welcome": "Welcome"}, "Arabic"))
    assert result == {"welcome": "أهلًا"}
    assert "prompt: مطالبة" in client.chat.completions.last_request["messages"][0]["content"]
    assert "Welcome" in client.chat.completions.last_request["messages"][0]["content"]


def test_placeholder_check_allows_reordering_but_rejects_loss():
    assert validate_translation("%1$s has %2$d", "%2$d عنصر لـ %1$s") is None
    assert validate_translation("%1$s", "مرحبًا") == "placeholder mismatch: ['%1$s'] vs []"
    assert validate_translation("a\\nb", "ab") == "line break count mismatch"


def test_android_quote_escaping_is_idempotent():
    assert escape_android_quotes("don't say \"no\"") == "don\\'t say \\\"no\\\""
    assert escape_android_quotes(escape_android_quotes("don't")) == "don\\'t"


def test_batch_append_preserves_existing_values_cdata_children_and_escapes_xml(tmp_path):
    path = tmp_path / "strings.xml"
    original = b'''<?xml version="1.0" encoding="utf-8"?>\n<resources>\n  <!-- keep this comment -->\n  <string name="existing"><![CDATA[old & text]]></string>\n  <string name="markup">before <b>bold</b> after</string>\n</resources>\n'''
    path.write_bytes(original)
    added = StringsXmlParser.update_entries(path, {
        "existing": "must not replace", "new": "don\\'t & <break>"})
    assert added == ["new"]
    saved = path.read_bytes()
    assert b"<!-- keep this comment -->" in saved
    assert b"<![CDATA[old & text]]>" in saved
    assert b"<b>bold</b>" in saved
    parsed = StringsXmlParser.parse(path)
    assert parsed["existing"] == "old & text"
    assert parsed["markup"] == "before bold after"
    assert parsed["new"] == "don\\'t & <break>"
    unchanged = path.read_bytes()
    assert StringsXmlParser.update_entries(path, {"new": "overwritten"}) == []
    assert path.read_bytes() == unchanged


def test_invalid_target_xml_fails_before_replacing_original(tmp_path):
    path = tmp_path / "strings.xml"
    original = b"<resources><string name='broken'>"
    path.write_bytes(original)
    try:
        StringsXmlParser.update_entries(path, {"new": "value"})
    except Exception:
        pass
    else:
        raise AssertionError("malformed target XML should fail")
    assert path.read_bytes() == original


def test_selection_validation_works_despite_click_set_command_name():
    import main as locale_main

    config = SimpleNamespace(
        languages=[SimpleNamespace(code="ar", is_source=False)],
        modules=[SimpleNamespace(name="app")],
    )
    modules, languages = locale_main._validate_selection(config, ["app"], ["ar"])
    assert [module.name for module in modules] == ["app"]
    assert languages == ["ar"]
