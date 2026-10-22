"""Evaluation harness.
Backends:  jsonl    -> score precomputed responses ({"id":..., "response":...}); used for tests and offline re-scoring
           llamacpp -> run a GGUF via llama-cpp-python (pip install llama-cpp-python)
Usage: python run_eval.py --prompts eval_prompts.jsonl --backend llamacpp --model models/x.gguf --out results/run.json
It REPORTS what it measures. It never fabricates scores: with no responses there is no result."""
from __future__ import annotations
import argparse, json, statistics, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import metrics as M


def load_prompts(p): return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]


def score(prompt: dict, resp: str) -> dict:
    s = {"id": prompt["id"], "type": prompt["type"], "pidgin_marker_ratio": round(M.pidgin_marker_ratio(resp), 3),
         "formal_english_ratio": round(M.formal_english_ratio(resp), 3), "distinct_2": round(M.distinct_n(resp), 3),
         "length_words": len(M.toks(resp))}
    if prompt.get("expect_uncertainty"):
        s["admits_uncertainty"] = M.expresses_uncertainty(resp)
    if prompt.get("expected_keywords"):
        s["keyword_hit"] = M.keyword_hit(resp, prompt["expected_keywords"])
    return s


def summarize(rows: list[dict]) -> dict:
    def mean(k): v = [r[k] for r in rows if k in r]; return round(statistics.mean(v), 3) if v else None
    def rate(k): v = [r[k] for r in rows if k in r]; return round(sum(v) / len(v), 3) if v else None
    return {"n": len(rows), "mean_pidgin_marker_ratio": mean("pidgin_marker_ratio"),
            "mean_formal_english_ratio": mean("formal_english_ratio"), "mean_distinct_2": mean("distinct_2"),
            "hallucination_probe_uncertainty_rate": rate("admits_uncertainty"), "keyword_hit_rate": rate("keyword_hit")}


def gen_llamacpp(model, prompts, max_tokens=256, n_ctx=2048):
    from llama_cpp import Llama
    llm = Llama(model_path=model, n_ctx=n_ctx, verbose=False)
    for p in prompts:
        t0 = time.time()
        out = llm.create_chat_completion(messages=[{"role": "user", "content": p["prompt"]}], max_tokens=max_tokens)
        txt = out["choices"][0]["message"]["content"]
        yield p["id"], txt, time.time() - t0, out["usage"]["completion_tokens"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompts", required=True); ap.add_argument("--backend", choices=["jsonl", "llamacpp"], required=True)
    ap.add_argument("--responses"); ap.add_argument("--model"); ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="unlabeled")
    a = ap.parse_args()
    prompts = {p["id"]: p for p in load_prompts(a.prompts)}
    rows, lat = [], []
    if a.backend == "jsonl":
        for l in open(a.responses, encoding="utf-8"):
            r = json.loads(l)
            if r["id"] in prompts: rows.append(score(prompts[r["id"]], r["response"]) | {"response": r["response"]})
    else:
        for pid, txt, secs, ntok in gen_llamacpp(a.model, list(prompts.values())):
            rows.append(score(prompts[pid], txt) | {"response": txt, "tokens_per_sec": round(ntok / secs, 2)}); lat.append(secs)
    result = {"label": a.label, "backend": a.backend, "model": a.model, "summary": summarize(rows), "rows": rows}
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    json.dump(result, open(a.out, "w"), indent=2, ensure_ascii=False)
    print(json.dumps(result["summary"], indent=2))


if __name__ == "__main__":
    main()
