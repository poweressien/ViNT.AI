from __future__ import annotations
import csv, json
from pathlib import Path
from typing import Iterator
from .schema import ExpressionRecord


def read_jsonl(path) -> Iterator[ExpressionRecord | dict]:
    """Streams records; malformed lines are yielded as {'_malformed': line, '_line_no': n}."""
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                yield ExpressionRecord.from_dict(json.loads(line))
            except (json.JSONDecodeError, TypeError, AttributeError):
                yield {"_malformed": line[:200], "_line_no": n}


def read_csv(path) -> Iterator[ExpressionRecord]:
    """List fields use '|' as separator inside a cell."""
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            yield ExpressionRecord.from_dict(row)


def read_txt(path, source: str = "", license: str = "") -> Iterator[ExpressionRecord]:
    """Plain text: one expression/sentence per line. No meaning is invented (confidence 0, unverified)."""
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield ExpressionRecord(expression=line, source=source, license=license,
                                       provenance=f"plain-text:{Path(path).name}").finalize()


def read_any(path, **kw) -> Iterator[ExpressionRecord | dict]:
    ext = Path(path).suffix.lower()
    if ext == ".jsonl":
        yield from read_jsonl(path)
    elif ext == ".csv":
        yield from read_csv(path)
    elif ext == ".txt":
        yield from read_txt(path, **kw)
    else:
        raise ValueError(f"Unsupported format: {ext} (use .jsonl, .csv, .txt)")


def write_jsonl(records, path) -> int:
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r.to_dict() if hasattr(r, "to_dict") else r, ensure_ascii=False) + "\n")
            n += 1
    return n
