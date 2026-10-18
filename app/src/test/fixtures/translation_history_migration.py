#!/usr/bin/env python3
"""Offline regression fixture for the exact additive 44→45 DDL."""
import re
import sqlite3
from pathlib import Path

MIGRATION_SOURCE = Path(__file__).resolve().parents[4] / "app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_44_45.kt"
source = MIGRATION_SOURCE.read_text()
CREATE_TABLE, = re.findall(r'"""(CREATE TABLE IF NOT EXISTS .*?)"""', source, re.DOTALL)
CREATE_INDEX, = re.findall(r'db\.execSQL\("(CREATE INDEX IF NOT EXISTS .*?)"\)', source)


def make_v44():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE conversationentity (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
    db.execute("CREATE INDEX legacy_conversation_title ON conversationentity(title)")
    db.execute("INSERT INTO conversationentity VALUES ('legacy-id', 'retained')")
    db.commit()
    return db


def migrate(db):
    db.execute(CREATE_TABLE)
    db.execute(CREATE_INDEX)


def test_optional_conversation_sort():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE conversationentity (id TEXT, is_pinned INTEGER, create_at INTEGER, update_at INTEGER)")
    db.executemany(
        "INSERT INTO conversationentity VALUES (?, ?, ?, ?)",
        [("b", 0, 20, 50), ("a", 0, 20, 50), ("old", 0, 10, 60), ("pin", 1, 1, 1)],
    )
    sql = "SELECT id FROM conversationentity ORDER BY is_pinned DESC, CASE WHEN ? THEN create_at ELSE update_at END DESC, id ASC"
    assert [r[0] for r in db.execute(sql, (0,))] == ["pin", "old", "a", "b"]
    assert [r[0] for r in db.execute(sql, (1,))] == ["pin", "a", "b", "old"]
    dao = Path(__file__).resolve().parents[4] / "app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt"
    query_source = dao.read_text()
    ordered_queries = re.findall(r'@Query\("(SELECT .*?ORDER BY .*?)"\)', query_source)
    assert ordered_queries and all(":sortByCreatedAt" in query and "id ASC" in query for query in ordered_queries)


def test_upgrade_and_rollback():
    db = make_v44()
    db.execute("BEGIN")
    migrate(db)
    assert db.execute("SELECT title FROM conversationentity WHERE id='legacy-id'").fetchone() == ("retained",)
    db.execute("ROLLBACK")
    assert db.execute("SELECT title FROM conversationentity WHERE id='legacy-id'").fetchone() == ("retained",)
    assert db.execute("SELECT name FROM sqlite_master WHERE name='translation_history'").fetchone() is None
    assert db.execute("SELECT name FROM sqlite_master WHERE name='legacy_conversation_title'").fetchone() == ("legacy_conversation_title",)

    db.execute("BEGIN")
    migrate(db)
    db.execute("INSERT INTO translation_history(source_text, translated_text, target_language, model_id, created_at) VALUES ('hello', '你好', 'zh-CN', 'test-model', 100)")
    db.execute("COMMIT")
    assert db.execute("SELECT source_text, translated_text FROM translation_history").fetchone() == ("hello", "你好")
    assert db.execute("SELECT name FROM sqlite_master WHERE name='index_translation_history_created_at'").fetchone() == ("index_translation_history_created_at",)
    assert db.execute("SELECT title FROM conversationentity WHERE id='legacy-id'").fetchone() == ("retained",)
    assert db.execute("SELECT name FROM sqlite_master WHERE name='legacy_conversation_title'").fetchone() == ("legacy_conversation_title",)
    print("PASS: rollback is atomic; 44 data/indexes survive; 45 table/index are created")


if __name__ == "__main__":
    test_upgrade_and_rollback()
    test_optional_conversation_sort()
    print("PASS: optional creation-time sort preserves pin priority and has stable ties")
