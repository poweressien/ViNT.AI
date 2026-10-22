"""Speech metrics. Pure Python, no dependencies: word/character error rate with explicit text normalisation."""
from __future__ import annotations
import re, unicodedata


def normalize_text(t: str) -> str:
    """Lowercase, NFKC, drop punctuation (keeps apostrophes), collapse spaces. Pidgin spellings are NOT rewritten."""
    t = unicodedata.normalize("NFKC", t).lower()
    t = re.sub(r"[^\w\s']", " ", t).replace("_", " ")
    return re.sub(r"\s+", " ", t).strip()


def _edit_distance(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(reference: str, hypothesis: str) -> float:
    r, h = normalize_text(reference).split(), normalize_text(hypothesis).split()
    if not r:
        return 0.0 if not h else float(len(h))
    return _edit_distance(r, h) / len(r)


def cer(reference: str, hypothesis: str) -> float:
    r, h = list(normalize_text(reference).replace(" ", "")), list(normalize_text(hypothesis).replace(" ", ""))
    if not r:
        return 0.0 if not h else float(len(h))
    return _edit_distance(r, h) / len(r)


def corpus_wer(pairs: list[tuple[str, str]]) -> float:
    """Total edits / total reference words (the standard corpus-level WER, not the mean of per-line WERs)."""
    edits = sum(_edit_distance(normalize_text(r).split(), normalize_text(h).split()) for r, h in pairs)
    words = sum(len(normalize_text(r).split()) for r, _ in pairs)
    return edits / words if words else 0.0
