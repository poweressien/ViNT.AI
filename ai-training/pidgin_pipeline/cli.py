from __future__ import annotations
import argparse, json, sys
from pathlib import Path
from . import ingest, quality, dedupe, split, stats, export, slangdb


def _load(paths):
    recs, bad = [], 0
    for p in paths:
        for r in ingest.read_any(p):
            if isinstance(r, dict):
                bad += 1
            else:
                recs.append(r)
    return recs, bad


def main(argv=None):
    ap = argparse.ArgumentParser(prog="pidgin-pipeline")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("validate", "stats", "dedupe", "split", "export", "build-db"):
        p = sub.add_parser(name); p.add_argument("inputs", nargs="+")
        if name in ("dedupe", "export", "build-db"): p.add_argument("-o", "--out", required=True)
        if name == "split": p.add_argument("-o", "--outdir", required=True)
        if name == "export": p.add_argument("--include-unverified", action="store_true")
    s = sub.add_parser("search"); s.add_argument("db"); s.add_argument("query")
    a = ap.parse_args(argv)

    if a.cmd == "search":
        for r in slangdb.search(a.db, a.query):
            print(f"{r['expression']}  ->  {r['meaning_in_pidgin']}  [conf={r['confidence']}, verified={bool(r['human_verified'])}]")
        return 0

    recs, bad = _load(a.inputs)
    if a.cmd == "validate":
        report = {r.id: quality.validate_record(r) for r in recs}
        report = {k: v for k, v in report.items() if v}
        print(json.dumps({"records": len(recs), "malformed_lines": bad, "records_with_issues": len(report),
                          "conflicts": quality.find_conflicts(recs), "issues": report}, indent=2, ensure_ascii=False))
    elif a.cmd == "stats":
        print(json.dumps(stats.corpus_stats(recs), indent=2))
    elif a.cmd == "dedupe":
        out = dedupe.dedupe_exact(recs); ingest.write_jsonl(out, a.out)
        print(f"{len(recs)} -> {len(out)} records after exact dedupe")
    elif a.cmd == "split":
        sp = split.split_records(dedupe.dedupe_exact(recs)); leaks = split.check_leakage(sp)
        Path(a.outdir).mkdir(parents=True, exist_ok=True)
        for k, v in sp.items(): ingest.write_jsonl(v, Path(a.outdir) / f"{k}.jsonl")
        print({k: len(v) for k, v in sp.items()}, "leaks:", leaks)
        return 1 if leaks else 0
    elif a.cmd == "export":
        n = export.write_instruction_jsonl(recs, a.out, include_unverified=a.include_unverified)
        print(f"wrote {n} instruction examples (include_unverified={a.include_unverified})")
    elif a.cmd == "build-db":
        print(f"built {slangdb.build_db(recs, a.out)} rows -> {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
