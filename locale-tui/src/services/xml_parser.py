"""Android strings.xml parser service."""

from pathlib import Path
import os
import tempfile
from lxml import etree


class StringsXmlParser:
    """Android strings.xml parser."""

    @staticmethod
    def parse(file_path: Path) -> dict[str, str]:
        """Parse strings.xml file, returns {name: value} dict."""
        if not file_path.exists():
            return {}

        try:
            parser = etree.XMLParser(strip_cdata=False, resolve_entities=False, no_network=True)
            with open(file_path, 'rb') as f:
                tree = etree.parse(f, parser)
            root = tree.getroot()
            result = {}

            for string_elem in root.findall("string"):
                name = string_elem.get("name")
                if name:
                    value = StringsXmlParser._get_text_content(string_elem)
                    result[name] = value

            return result
        except Exception:
            return {}

    @staticmethod
    def _get_text_content(elem) -> str:
        """Extract element text content."""
        return "".join(elem.itertext())

    @staticmethod
    def _atomic_write_tree(file_path: Path, tree) -> None:
        """Serialize to a sibling temp file then atomically replace the target."""
        data = etree.tostring(
            tree, encoding="utf-8", xml_declaration=True, pretty_print=True
        ) + b"\n"
        file_path.parent.mkdir(parents=True, exist_ok=True)
        old_mode = file_path.stat().st_mode & 0o777 if file_path.exists() else 0o644
        fd, temporary = tempfile.mkstemp(
            prefix=f".{file_path.name}.", suffix=".tmp", dir=str(file_path.parent)
        )
        try:
            os.fchmod(fd, old_mode)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, file_path)
        except BaseException:
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass
            raise

    @staticmethod
    def update_entries(file_path: Path, entries: dict[str, str]) -> list[str]:
        """Atomically append absent translations, preserving all existing XML nodes."""
        parser = etree.XMLParser(strip_cdata=False, resolve_entities=False, no_network=True)
        if file_path.exists():
            tree = etree.parse(str(file_path), parser)
            root = tree.getroot()
        else:
            root = etree.Element("resources")
            tree = etree.ElementTree(root)

        present = {
            elem.get("name") for elem in root.findall("string") if elem.get("name")
        }
        added = []
        for key, value in entries.items():
            if key in present:
                continue
            elem = etree.SubElement(root, "string", name=key)
            elem.text = value
            present.add(key)
            added.append(key)
        if not added:
            return []

        StringsXmlParser._atomic_write_tree(file_path, tree)
        return added

    @staticmethod
    def replace_entries(file_path: Path, entries: dict[str, str]) -> list[str]:
        """Atomically replace only the named keys requested for retranslation."""
        parser = etree.XMLParser(strip_cdata=False, resolve_entities=False, no_network=True)
        tree = etree.parse(str(file_path), parser)
        root = tree.getroot()
        replacements = dict(entries)
        updated = []
        for elem in root.findall("string"):
            key = elem.get("name")
            if key in replacements:
                elem.text = replacements.pop(key)
                updated.append(key)
        if replacements:
            raise ValueError(f"Target keys disappeared before write: {', '.join(sorted(replacements))}")
        if updated:
            StringsXmlParser._atomic_write_tree(file_path, tree)
        return updated

    @staticmethod
    def write(file_path: Path, entries: dict[str, str]) -> None:
        """Write strings.xml file."""
        root = etree.Element("resources")

        for name in sorted(entries.keys()):
            value = entries[name]
            string_elem = etree.SubElement(root, "string")
            string_elem.set("name", name)
            string_elem.text = value

        # Ensure directory exists
        file_path.parent.mkdir(parents=True, exist_ok=True)

        etree.indent(root, space="  ")
        tree = etree.ElementTree(root)
        tree.write(
            str(file_path), encoding="utf-8", xml_declaration=True, pretty_print=True
        )

    @staticmethod
    def update_entry(file_path: Path, key: str, value: str) -> None:
        """Update single entry."""
        if not file_path.exists():
            StringsXmlParser.write(file_path, {key: value})
            return

        with open(file_path, 'r', encoding='utf-8') as f:
            tree = etree.parse(f)
        root = tree.getroot()

        # Find existing entry
        found = False
        for string_elem in root.findall("string"):
            if string_elem.get("name") == key:
                string_elem.text = value
                found = True
                break

        # Add if not exists
        if not found:
            string_elem = etree.SubElement(root, "string")
            string_elem.set("name", key)
            string_elem.text = value

        # Clean up whitespace and format
        etree.indent(root, space="  ")

        tree.write(
            str(file_path), encoding="utf-8", xml_declaration=True, pretty_print=True
        )

        # Add newline at end of file
        with open(file_path, 'a', encoding='utf-8') as f:
            f.write('\n')

    @staticmethod
    def delete_entry(file_path: Path, key: str) -> bool:
        """Delete single entry."""
        if not file_path.exists():
            return False

        with open(file_path, 'r', encoding='utf-8') as f:
            tree = etree.parse(f)
        root = tree.getroot()

        for string_elem in root.findall("string"):
            if string_elem.get("name") == key:
                root.remove(string_elem)
                etree.indent(root, space="  ")
                tree.write(
                    str(file_path),
                    encoding="utf-8",
                    xml_declaration=True,
                    pretty_print=True,
                )
                return True

        return False
