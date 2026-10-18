"""Validation helpers for translated Android string values."""
import re

# Android/Java format specifiers, e.g. %s, %d, %1$s, %.2f and %%.
_PLACEHOLDER = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]")
_UNESCAPED_QUOTE = re.compile(r"(?<!\\)(['\"])")


def extract_placeholders(value: str) -> list[str]:
    """Return format specifiers sorted so translations may reorder them."""
    return sorted(match.group(0) for match in _PLACEHOLDER.finditer(value))


def escape_android_quotes(value: str) -> str:
    """Escape unescaped single and double quotes for Android string resources."""
    return _UNESCAPED_QUOTE.sub(r"\\\1", value)


def validate_translation(source: str, translated: str) -> str | None:
    """Return a reason when translated text cannot safely replace source text."""
    if not translated or not translated.strip():
        return "empty translation"
    source_placeholders = extract_placeholders(source)
    translated_placeholders = extract_placeholders(translated)
    if source_placeholders != translated_placeholders:
        return f"placeholder mismatch: {source_placeholders} vs {translated_placeholders}"
    if source.count("\\n") != translated.count("\\n"):
        return "line break count mismatch"
    return None
