from __future__ import annotations
import json, sqlite3
from .schema import ExpressionRecord, normalize

DDL = """
CREATE TABLE IF NOT EXISTS expressions (
  id TEXT PRIMARY KEY, expression TEXT NOT NULL, normalized_expression TEXT NOT NULL,
  meaning_in_pidgin TEXT, contextual_meaning TEXT, usage_examples TEXT, alternative_spellings TEXT,
  related_expressions TEXT, category TEXT, tone TEXT, region_or_variant TEXT, source TEXT, license TEXT,
  provenance TEXT, confidence REAL, human_verified INTEGER, synthetic INTEGER);
CREATE INDEX IF NOT EXISTS idx_norm ON expressions(normalized_expression);
CREATE VIRTUAL TABLE IF NOT EXISTS expressions_fts USING fts5(
  id UNINDEXED, expression, alt_norm, meaning, tokenize='unicode61');
"""


def build_db(records, path: str, batch: int = 5000) -> int:
    """Streams records into SQLite (+FTS5). Never holds the corpus in memory; the same .db file
    is bundled into the Android app and queried with android.database.sqlite."""
    con = sqlite3.connect(path)
    con.executescript(DDL)
    n, buf, fbuf = 0, [], []

    def flush():
        con.executemany("INSERT OR REPLACE INTO expressions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", buf)
        con.executemany("INSERT INTO expressions_fts VALUES (?,?,?,?)", fbuf)
        buf.clear(); fbuf.clear()

    for r in records:
        buf.append((r.id, r.expression, r.normalized_expression, r.meaning_in_pidgin, r.contextual_meaning,
                    json.dumps(r.usage_examples, ensure_ascii=False),
                    json.dumps(r.alternative_spellings, ensure_ascii=False),
                    json.dumps(r.related_expressions, ensure_ascii=False), r.category, r.tone,
                    r.region_or_variant, r.source, r.license, r.provenance, r.confidence,
                    int(r.human_verified), int(r.synthetic)))
        fbuf.append((r.id, r.expression, " ".join(normalize(a) for a in r.alternative_spellings),
                     r.meaning_in_pidgin))
        n += 1
        if n % batch == 0:
            flush()
    flush()
    con.commit(); con.close()
    return n


def search(path: str, query: str, limit: int = 20) -> list[dict]:
    q = normalize(query)
    if not q:
        return []
    con = sqlite3.connect(path)
    con.row_factory = sqlite3.Row
    fts_q = " ".join(f'"{t}"*' for t in q.split())
    rows = con.execute(
        "SELECT e.* FROM expressions_fts f JOIN expressions e ON e.id=f.id "
        "WHERE expressions_fts MATCH ? ORDER BY rank LIMIT ?", (fts_q, limit)).fetchall()
    con.close()
    return [dict(r) for r in rows]
