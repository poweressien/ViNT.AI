from __future__ import annotations
import hashlib
from .schema import ExpressionRecord, normalize


def _bucket(key: str, seed: str) -> float:
    return int(hashlib.sha256(f"{seed}:{key}".encode()).hexdigest()[:12], 16) / float(16 ** 12)


def assign_split(group_key: str, seed: str = "pidgingpt-v1", val: float = 0.05, test: float = 0.05) -> str:
    b = _bucket(group_key, seed)
    return "test" if b < test else "validation" if b < test + val else "train"


def split_records(records, seed="pidgingpt-v1", val=0.05, test=0.05):
    """Deterministic group split by normalized_expression: every variant/duplicate of an expression
    lands in the same split, so there is no expression-level leakage."""
    out = {"train": [], "validation": [], "test": []}
    for r in records:
        out[assign_split(r.normalized_expression, seed, val, test)].append(r)
    return out


def check_leakage(splits: dict[str, list[ExpressionRecord]]) -> list[str]:
    """Returns the normalized expressions (or alternative spellings) that appear in more than one split."""
    owner: dict[str, str] = {}
    leaks: set[str] = set()
    for name, recs in splits.items():
        for r in recs:
            for key in {r.normalized_expression, *(normalize(a) for a in r.alternative_spellings)}:
                if key in owner and owner[key] != name:
                    leaks.add(key)
                owner.setdefault(key, name)
    return sorted(leaks)
