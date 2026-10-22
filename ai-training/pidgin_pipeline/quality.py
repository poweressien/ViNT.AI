from __future__ import annotations
from collections import defaultdict
from .schema import ExpressionRecord, normalize

# Heuristic only (NOT a language identifier). Humans must review flagged items.
STRONG_MARKERS = {"dey", "wetin", "abeg", "wahala", "wey", "una", "oga", "sabi", "naim", "kampe", "pikin",
                  "gist", "japa", "shey", "abi", "sef", "waka", "chop", "comot", "kasala", "wan", "dem"}
ENGLISH_FUNCTION = {"the", "of", "and", "to", "is", "are", "was", "were", "that", "with", "for", "this",
                    "which", "have", "has", "been", "their", "would", "should", "because", "however"}


def looks_english_only(text: str) -> bool:
    toks = normalize(text).split()
    if len(toks) < 4:
        return False  # too short to judge
    if any(t in STRONG_MARKERS for t in toks):
        return False
    return sum(t in ENGLISH_FUNCTION for t in toks) / len(toks) >= 0.2


def validate_record(r: ExpressionRecord) -> list[str]:
    issues = []
    if not r.expression.strip():
        issues.append("empty_expression")
    if len(r.expression) > 500:
        issues.append("expression_too_long")
    if not (0.0 <= r.confidence <= 1.0):
        issues.append("confidence_out_of_range")
    if not r.source:
        issues.append("missing_source")
    if not r.license:
        issues.append("missing_license")
    if not r.provenance:
        issues.append("missing_provenance")
    if r.meaning_in_pidgin and looks_english_only(r.meaning_in_pidgin):
        issues.append("meaning_looks_english_only")
    return issues


def _tok_jaccard(a: str, b: str) -> float:
    A, B = set(normalize(a).split()), set(normalize(b).split())
    return len(A & B) / len(A | B) if A and B else 0.0


def find_conflicts(records: list[ExpressionRecord], threshold: float = 0.2) -> list[dict]:
    """Same normalized expression with clearly different meanings -> flagged for human review."""
    groups: dict[str, list[ExpressionRecord]] = defaultdict(list)
    for r in records:
        if r.meaning_in_pidgin:
            groups[r.normalized_expression].append(r)
    out = []
    for key, rs in groups.items():
        for i in range(len(rs)):
            for j in range(i + 1, len(rs)):
                if _tok_jaccard(rs[i].meaning_in_pidgin, rs[j].meaning_in_pidgin) < threshold:
                    out.append({"expression": key, "ids": [rs[i].id, rs[j].id],
                                "meanings": [rs[i].meaning_in_pidgin, rs[j].meaning_in_pidgin]})
    return out


def flag_unverified(records, min_conf: float = 0.7) -> list[str]:
    return [r.id for r in records if not r.human_verified or r.confidence < min_conf]
