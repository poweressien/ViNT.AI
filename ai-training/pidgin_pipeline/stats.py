from __future__ import annotations
from .schema import normalize


def corpus_stats(records, conversations=None) -> dict:
    """Reports SEPARATE counts. These must never be summed into a single 'dataset size'."""
    words, tokens, exprs, examples = set(), 0, set(), 0
    verified = synthetic = 0
    for r in records:
        exprs.add(r.normalized_expression)
        examples += len(r.usage_examples)
        verified += bool(r.human_verified)
        synthetic += bool(r.synthetic)
        for text in [r.expression, r.meaning_in_pidgin, r.contextual_meaning, *r.usage_examples]:
            toks = normalize(text).split()
            tokens += len(toks)
            words.update(toks)
    turns = sum(len(c.get("messages", [])) for c in (conversations or []))
    return {
        "unique_expressions": len(exprs),
        "sentence_examples": examples,
        "unique_words": len(words),
        "total_tokens_whitespace": tokens,  # whitespace tokens, NOT model tokenizer tokens
        "conversation_turns": turns,
        "human_verified_records": verified,
        "synthetic_records": synthetic,
    }
