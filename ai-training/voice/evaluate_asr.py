#!/usr/bin/env python3
"""Score ASR hypotheses against a consented manifest. Overall WER/CER, plus per-self-reported-group WER ONLY where the group is reportable.
usage: evaluate_asr.py MANIFEST.jsonl HYPOTHESES.jsonl [--field pidgin_variety] [--split test] [--min-speakers 10] [--min-minutes 30]
HYPOTHESES.jsonl lines: {"recording_id": ..., "hypothesis": ...}. Writes nothing but a JSON report to stdout. Never prints per-group numbers for small groups."""
import argparse, json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from speech_metrics import corpus_wer, cer
from recordings import load_manifest, split_by_speaker, group_report


def evaluate(records, hyps, field="pidgin_variety", min_speakers=10, min_minutes=30.0):
    pairs = [(r, hyps[r["recording_id"]]) for r in records if r["recording_id"] in hyps]
    missing = [r["recording_id"] for r in records if r["recording_id"] not in hyps]
    rep = group_report(records, field, min_speakers, min_minutes); out = {}
    for g, info in rep.items():
        sub = [(r["transcript"], h) for r, h in pairs if (",".join(sorted(v)) if isinstance((v := r.get("speaker_self_reported", {}).get(field)), list) else (v or "not_reported")) == g]
        out[g] = {**info, "utterances": len(sub), "wer": round(corpus_wer(sub), 4) if info["reportable"] and sub else "insufficient data: not reported"}
    allp = [(r["transcript"], h) for r, h in pairs]
    return {"utterances_scored": len(allp), "missing_hypotheses": missing, "overall_wer": round(corpus_wer(allp), 4) if allp else None,
            "overall_cer": round(sum(cer(r, h) for r, h in allp) / len(allp), 4) if allp else None, "group_field": field, "groups": out}


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("manifest"); ap.add_argument("hypotheses"); ap.add_argument("--field", default="pidgin_variety")
    ap.add_argument("--split", default="test"); ap.add_argument("--min-speakers", type=int, default=10); ap.add_argument("--min-minutes", type=float, default=30.0); a = ap.parse_args()
    recs, bad = load_manifest(a.manifest, "asr_evaluation")
    if bad: print(json.dumps({"invalid_records": bad}, indent=2), file=sys.stderr)
    recs = split_by_speaker(recs)[a.split]
    hyps = {j["recording_id"]: j["hypothesis"] for j in map(json.loads, Path(a.hypotheses).read_text(encoding="utf-8").splitlines()) if j}
    print(json.dumps(evaluate(recs, hyps, a.field, a.min_speakers, a.min_minutes), indent=2, ensure_ascii=False))
