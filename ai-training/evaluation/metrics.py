"""Heuristic automated metrics. These are SCREENING signals, not a substitute for the human rubric
(docs/HUMAN_EVAL_RUBRIC.md). They are deliberately simple and their limits are documented."""
from __future__ import annotations
import re

PIDGIN_MARKERS = {"dey", "wetin", "abeg", "wahala", "wey", "una", "oga", "sabi", "na", "no", "fit", "don",
                  "make", "dem", "wan", "sef", "kampe", "gist", "pikin", "comot", "o", "am", "e", "go"}
FORMAL_ENGLISH_MARKERS = {"the", "is", "are", "which", "however", "therefore", "would", "furthermore",
                          "that", "this", "of", "provides", "refers", "because"}
UNCERTAINTY_PHRASES = ("no sure", "no too sure", "i no know", "i no sabi", "no get correct", "no fit confirm",
                       "i no dey sure", "no dey sure", "no dey know", "not sure", "don't know", "do not know")


def toks(t: str) -> list[str]:
    return re.findall(r"[a-zA-Z']+", t.lower())


def pidgin_marker_ratio(t: str) -> float:
    w = toks(t); return sum(x in PIDGIN_MARKERS for x in w) / len(w) if w else 0.0


def formal_english_ratio(t: str) -> float:
    w = toks(t); return sum(x in FORMAL_ENGLISH_MARKERS for x in w) / len(w) if w else 0.0


def distinct_n(t: str, n: int = 2) -> float:
    w = toks(t)
    grams = [tuple(w[i:i + n]) for i in range(len(w) - n + 1)]
    return len(set(grams)) / len(grams) if grams else 1.0


def expresses_uncertainty(t: str) -> bool:
    low = t.lower(); return any(p in low for p in UNCERTAINTY_PHRASES)


def keyword_hit(t: str, kws: list[str]) -> bool:
    low = t.lower(); return any(k in low for k in kws)
