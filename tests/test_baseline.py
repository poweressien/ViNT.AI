import json, os, re, shutil, sys
from pathlib import Path
import pytest
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-training/evaluation"))
import baseline_llamaserver as B

PROMPTS_KT = (ROOT / "android-app/app/src/main/java/ng/pidgingpt/inference/Prompts.kt").read_text(encoding="utf-8")
SP = json.loads((ROOT / "ai-training/evaluation/system_prompts.json").read_text(encoding="utf-8"))


def _lits(seg):
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', seg))


def test_system_prompts_json_matches_kotlin_exactly():
    strict = _lits(PROMPTS_KT[PROMPTS_KT.index("LanguageMode.STRICT_PIDGIN ->"):PROMPTS_KT.index("LanguageMode.NATURAL_PIDGIN ->")])
    natural = _lits(PROMPTS_KT[PROMPTS_KT.index("LanguageMode.NATURAL_PIDGIN ->"):PROMPTS_KT.index("/** Keeps the system prompt")].split("\n    }")[0])
    voice = _lits(PROMPTS_KT[PROMPTS_KT.index("private const val VOICE_NOTE"):].split("\n")[0].split("=", 1)[1])
    assert SP["strict"] == strict and SP["natural"] == natural and SP["voice_note"] == voice
    assert 'noThink) " /no_think"' in PROMPTS_KT.replace('\\"', '"') or '" /no_think"' in PROMPTS_KT


def test_system_prompt_composition():
    assert B.system_prompt("natural", False, False) == SP["natural"]
    assert B.system_prompt("strict", True, True) == SP["strict"] + SP["voice_note"] + " /no_think"
    assert SP["natural"].startswith("You be ViNT")


def test_no_hardcoded_model_replies_anywhere_in_app_sources():
    # The app must only ever show text produced by the loaded model. Guard against canned Pidgin answers sneaking into UI/logic code.
    suspicious = re.compile(r'(reply|response|answer)\w*\s*=\s*"[^"]{20,}"', re.I)
    for f in (ROOT / "android-app/app/src/main/java").rglob("*.kt"):
        for ln in f.read_text(encoding="utf-8").splitlines():
            assert not suspicious.search(ln), f"{f.name}: {ln.strip()[:100]}"


@pytest.mark.skipif(not (os.environ.get("LLAMA_SERVER") and os.environ.get("MECHANICS_GGUF")), reason="needs LLAMA_SERVER and MECHANICS_GGUF (see docs/HOST_VERIFICATION.md)")
def test_baseline_tool_end_to_end_with_real_llama_server(tmp_path):
    prompts = [json.loads(l) for l in (ROOT / "ai-training/evaluation/eval_prompts.jsonl").read_text(encoding="utf-8").splitlines()][:3]
    rows, load_s = B.run(os.environ["LLAMA_SERVER"], os.environ["MECHANICS_GGUF"], prompts, ctx=512, max_tokens=8)
    assert [r["id"] for r in rows] == [p["id"] for p in prompts] and all(r["completion_tokens"] for r in rows)
