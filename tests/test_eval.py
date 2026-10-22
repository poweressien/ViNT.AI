import json, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-training/evaluation"))
import metrics as M


def test_metrics_basic():
    assert M.pidgin_marker_ratio("Abeg no wahala, I dey kampe") > M.pidgin_marker_ratio("The system is operating normally")
    assert M.formal_english_ratio("However, this is therefore the case") > 0.3
    assert M.distinct_n("go go go go go go") < 0.3
    assert M.expresses_uncertainty("I no too sure wetin e mean")
    assert not M.expresses_uncertainty("E mean say person dey happy")


def test_harness_scores_precomputed_responses(tmp_path):
    resp = tmp_path / "r.jsonl"
    resp.write_text("\n".join(json.dumps(x) for x in [
        {"id": "halluc-001", "response": "I no too sure about this one, I no wan lie."},
        {"id": "slang-001", "response": "E mean say no problem, no worry."}]))
    out = tmp_path / "o.json"
    subprocess.run([sys.executable, str(ROOT / "ai-training/evaluation/run_eval.py"), "--prompts",
                    str(ROOT / "ai-training/evaluation/eval_prompts.jsonl"), "--backend", "jsonl",
                    "--responses", str(resp), "--out", str(out)], check=True, capture_output=True)
    s = json.loads(out.read_text())["summary"]
    assert s["n"] == 2 and s["hallucination_probe_uncertainty_rate"] == 1.0 and s["keyword_hit_rate"] == 1.0
