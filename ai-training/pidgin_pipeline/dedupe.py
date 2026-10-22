from __future__ import annotations
import zlib
from collections import defaultdict
from .schema import ExpressionRecord, normalize


def dedupe_exact(records) -> list[ExpressionRecord]:
    """Merge records with the same normalized_expression: keep the best-confidence one,
    union spellings/examples/related. Memory is O(unique expressions)."""
    best: dict[str, ExpressionRecord] = {}
    for r in records:
        k = r.normalized_expression
        cur = best.get(k)
        if cur is None:
            best[k] = r
            continue
        keep, other = (r, cur) if (r.human_verified, r.confidence) > (cur.human_verified, cur.confidence) else (cur, r)
        for f in ("usage_examples", "alternative_spellings", "related_expressions"):
            merged = list(dict.fromkeys(getattr(keep, f) + getattr(other, f)))
            setattr(keep, f, merged)
        if other.expression != keep.expression and other.expression not in keep.alternative_spellings:
            keep.alternative_spellings.append(other.expression)
        best[k] = keep
    return list(best.values())


def _shingles(text: str, k: int = 4) -> set[str]:
    t = normalize(text).replace(" ", "_")
    return {t[i:i + k] for i in range(max(1, len(t) - k + 1))}


def _minhash(shingles: set[str], num_perm: int = 32) -> list[int]:
    return [min(zlib.crc32(f"{seed}:{s}".encode()) for s in shingles) for seed in range(num_perm)]


def near_duplicates(texts: list[str], threshold: float = 0.8, bands: int = 8) -> list[tuple[int, int, float]]:
    """MinHash + banding candidate search, verified with exact Jaccard on shingles.
    Returns (i, j, jaccard). Suitable for sentence-level corpora; shard by length for huge inputs."""
    sh = [_shingles(t) for t in texts]
    sigs = [_minhash(s) for s in sh]
    rows = len(sigs[0]) // bands if sigs else 0
    buckets: dict[tuple, list[int]] = defaultdict(list)
    for idx, sig in enumerate(sigs):
        for b in range(bands):
            buckets[(b, tuple(sig[b * rows:(b + 1) * rows]))].append(idx)
    seen, out = set(), []
    for ids in buckets.values():
        for a in range(len(ids)):
            for b in range(a + 1, len(ids)):
                i, j = ids[a], ids[b]
                if (i, j) in seen:
                    continue
                seen.add((i, j))
                jac = len(sh[i] & sh[j]) / len(sh[i] | sh[j])
                if jac >= threshold and normalize(texts[i]) != normalize(texts[j]):
                    out.append((i, j, round(jac, 3)))
    return sorted(out)
