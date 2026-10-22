from __future__ import annotations
import hashlib, re, unicodedata
from dataclasses import dataclass, field, asdict
from typing import Any

LIST_FIELDS = ("usage_examples", "alternative_spellings", "related_expressions")

_PUNCT = re.compile(r"[^\w\s']", re.UNICODE)
_WS = re.compile(r"\s+")


def normalize(text: str) -> str:
    """Lowercase, NFKC, strip punctuation (keeps apostrophes), collapse whitespace.
    Spelling variants are NOT merged here; they are kept as alternative_spellings."""
    t = unicodedata.normalize("NFKC", text or "").lower()
    t = _PUNCT.sub(" ", t).replace("_", " ")
    return _WS.sub(" ", t).strip()


@dataclass
class ExpressionRecord:
    expression: str
    id: str = ""
    normalized_expression: str = ""
    meaning_in_pidgin: str = ""
    contextual_meaning: str = ""
    usage_examples: list[str] = field(default_factory=list)
    alternative_spellings: list[str] = field(default_factory=list)
    related_expressions: list[str] = field(default_factory=list)
    category: str = ""
    tone: str = ""
    region_or_variant: str = ""
    source: str = ""
    license: str = ""
    provenance: str = ""
    confidence: float = 0.0
    human_verified: bool = False
    synthetic: bool = False  # extra field: synthetic content must always be labeled

    def finalize(self) -> "ExpressionRecord":
        if not self.normalized_expression:
            self.normalized_expression = normalize(self.expression)
        if not self.id:
            h = hashlib.sha1((self.normalized_expression + "|" + self.source).encode()).hexdigest()[:12]
            self.id = f"expr-{h}"
        return self

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "ExpressionRecord":
        known = set(cls.__dataclass_fields__)
        kw = {k: v for k, v in d.items() if k in known}
        for f in LIST_FIELDS:
            v = kw.get(f, [])
            if isinstance(v, str):
                v = [x.strip() for x in v.split("|") if x.strip()]
            kw[f] = list(v or [])
        if "confidence" in kw:
            try:
                kw["confidence"] = float(kw["confidence"])
            except (TypeError, ValueError):
                kw["confidence"] = -1.0  # flagged as malformed by QC
        for b in ("human_verified", "synthetic"):
            if b in kw and isinstance(kw[b], str):
                kw[b] = kw[b].strip().lower() in ("1", "true", "yes")
        kw.setdefault("expression", "")
        return cls(**kw).finalize()
