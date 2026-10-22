from __future__ import annotations
import json
from .schema import ExpressionRecord

SYSTEM_PROMPT = ("You be ViNT, Nigerian Pidgin AI. Talk natural Pidgin, no force slang. "
                 "If you no sure wetin expression mean, talk say you no sure.")

_Q_TEMPLATES = [
    "Wetin \"{e}\" mean?",
    "Abeg explain this expression for me: {e}",
]


def to_instruction_examples(records, include_unverified: bool = False, min_conf: float = 0.7):
    """Yield chat-format examples (messages) with provenance flags. Unverified records are
    excluded by default so unreviewed slang does not become training truth."""
    for r in records:
        if not r.meaning_in_pidgin:
            continue
        if not include_unverified and not (r.human_verified and r.confidence >= min_conf):
            continue
        for i, tpl in enumerate(_Q_TEMPLATES):
            answer = r.meaning_in_pidgin
            if r.contextual_meaning:
                answer += "\n\n" + r.contextual_meaning
            if r.usage_examples:
                answer += "\n\nExample: " + r.usage_examples[0]
            yield {
                "id": f"{r.id}-q{i}",
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": tpl.format(e=r.expression)},
                    {"role": "assistant", "content": answer},
                ],
                "meta": {"source": r.source, "license": r.license, "synthetic": r.synthetic,
                         "human_verified": r.human_verified},
            }


def write_instruction_jsonl(records, path, **kw) -> int:
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for ex in to_instruction_examples(records, **kw):
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")
            n += 1
    return n
