import hashlib, io, json, sys, tarfile, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-training/voice")); sys.path.insert(0, str(ROOT / "tools"))
import speech_metrics as metrics, recordings as R, evaluate_asr as E, profiles as P
import fetch_voice_models as F

CATALOG = json.loads((ROOT / "android-app/app/src/main/assets/voices/voice_profiles.json").read_text())
MODELS = json.loads((ROOT / "tools/voice_models.json").read_text())


# ---- metrics
def test_wer_basics():
    assert metrics.wer("how body", "how body") == 0 and metrics.wer("how body", "how") == 0.5 and metrics.wer("a b c d", "a x c d e") == 0.5
    assert metrics.normalize_text("Wetin dey sup?!") == "wetin dey sup" and metrics.wer("No wahala.", "no wahala") == 0
    assert metrics.cer("wahala", "wahla") == 1 / 6
    assert abs(metrics.corpus_wer([("a b", "a b"), ("c d e f", "x y e f")]) - 2 / 6) < 1e-9      # corpus-level, not mean of line WERs


# ---- synthetic FIXTURES ONLY (fake speakers, fake hashes): they test the validators, not any speech data
def rec(i, spk, dur=5.0, scopes=("asr_evaluation",), variety="pidgin-A", **kw):
    r = {"recording_id": f"r{i}", "speaker_id": spk, "audio_path": f"a/{i}.wav", "audio_sha256": hashlib.sha256(str(i).encode()).hexdigest(), "sample_rate": 16000,
         "duration_s": dur, "transcript": "how body", "license": "test-fixture", "source": "synthetic-test",
         "consent": {"consent_id": f"c-{spk}", "date": "2026-10-21", "scopes": list(scopes), "can_withdraw": True},
         "speaker_self_reported": {"pidgin_variety": variety}}
    r.update(kw); return r


def test_valid_record_and_each_rule():
    assert R.validate_record(rec(1, "s1")) == []
    assert "missing:consent" in R.validate_record({**rec(1, "s1"), "consent": None})
    assert any(x.startswith("consent_does_not_cover") for x in R.validate_record(rec(1, "s1"), use="tts_training"))
    assert R.validate_record(rec(1, "s1", scopes=("asr_evaluation", "tts_training")), use="tts_training") == []
    assert "voice_cloning_requires_named_permission" in R.validate_record(rec(1, "s1", scopes=("voice_cloning",)), use="voice_cloning")
    assert "voice_cloning_scope_without_named_permission" in R.validate_record(rec(1, "s1", scopes=("asr_evaluation", "voice_cloning")))
    c = rec(1, "s1", scopes=("voice_cloning",)); c["consent"]["named_permission"] = True; assert R.validate_record(c, use="voice_cloning") == []
    assert "consent_must_be_withdrawable" in R.validate_record({**rec(1, "s1"), "consent": {**rec(1, "s1")["consent"], "can_withdraw": False}})
    assert "sample_rate_below_16k" in R.validate_record(rec(1, "s1", sample_rate=8000))
    assert "duration_out_of_range" in R.validate_record(rec(1, "s1", dur=0.1)) and "bad_audio_sha256" in R.validate_record(rec(1, "s1", audio_sha256="xyz"))
    assert "empty_transcript" in R.validate_record(rec(1, "s1", transcript="  "))


def test_inferred_accent_attributes_are_rejected():
    assert any("inferred_attribute_not_allowed" in x for x in R.validate_record(rec(1, "s1", inferred_accent="akwa ibom")))
    assert any("inferred_attribute_not_allowed" in x for x in R.validate_record(rec(1, "s1", annotator_accent_label="lagos")))
    bad = rec(1, "s1"); bad["speaker_self_reported"]["accent_guess"] = "x"; assert R.validate_record(bad)


def test_manifest_loading_flags_bad_lines_and_duplicates(tmp_path):
    p = tmp_path / "m.jsonl"; dup = rec(2, "s2"); dup["audio_sha256"] = rec(1, "s1")["audio_sha256"]
    p.write_text("\n".join([json.dumps(rec(1, "s1")), "not json", json.dumps(dup), json.dumps({**rec(3, "s3"), "consent": None})]))
    good, bad = R.load_manifest(p)
    assert len(good) == 1 and bad["r2"] == ["duplicate_audio"] and "line2" in bad and "missing:consent" in bad["r3"]


def test_speaker_level_split_has_no_leakage():
    recs = [rec(i, f"s{i % 60}") for i in range(600)]; sp = R.split_by_speaker(recs)
    assert R.speaker_leakage(sp) == [] and sum(map(len, sp.values())) == 600 and all(len(v) > 0 for v in sp.values())
    assert R.split_by_speaker(recs) == sp                                  # deterministic
    assert R.speaker_leakage({"train": [rec(1, "x")], "test": [rec(2, "x")]}) == ["x"]


def test_small_groups_are_never_reported():
    recs = [rec(i, f"s{i}", dur=60, variety="A") for i in range(12)] + [rec(100 + i, f"t{i}", dur=60, variety="B") for i in range(3)] + [rec(200, "u", variety=None)]
    recs[-1]["speaker_self_reported"] = {}
    g = R.group_report(recs, "pidgin_variety", min_speakers=10, min_minutes=10)
    assert g["A"]["reportable"] and not g["B"]["reportable"] and not g["not_reported"]["reportable"]
    hyps = {r["recording_id"]: "how body" for r in recs}; hyps["r0"] = "how"
    rep = E.evaluate(recs, hyps, "pidgin_variety", 10, 10)
    assert isinstance(rep["groups"]["A"]["wer"], float) and rep["groups"]["B"]["wer"] == "insufficient data: not reported"
    assert rep["groups"]["not_reported"]["wer"].startswith("insufficient")


def test_revocation_removes_speaker_and_lists_files():
    recs = [rec(1, "a"), rec(2, "a"), rec(3, "b")]; keep, files = R.revoke(recs, "a")
    assert [r["speaker_id"] for r in keep] == ["b"] and files == ["a/1.wav", "a/2.wav"]


# ---- voice profile catalog
def test_real_catalog_is_valid_and_consistent_with_model_manifest():
    tts_ids = {m["id"] for m in MODELS["models"] if m["kind"] == "tts"}
    assert P.validate_catalog(CATALOG, tts_ids) == []
    prof = CATALOG["profiles"]; assert len([p for p in prof if p["status"] == "unavailable"]) == 5
    assert all(p["nigerian_pidgin_style"] == "none" for p in prof if p["status"] != "unavailable")


def test_catalog_validator_catches_dishonest_profiles():
    base = {"id": "x", "status": "available", "model_id": "m", "license": "l", "attribution": "a", "source": "s", "nigerian_pidgin_style": "none", "voice_label": "male"}
    assert P.validate_catalog({"profiles": [base]}) == []
    assert any("claims_nigerian_style" in x for x in P.validate_catalog({"profiles": [{**base, "nigerian_pidgin_style": "akwa-ibom"}]}))
    assert any("unavailable_profile_references_model" in x for x in P.validate_catalog({"profiles": [{**base, "status": "unavailable", "status_reason": "r"}]}))
    assert any("missing_license" in x for x in P.validate_catalog({"profiles": [{**base, "license": ""}]}))
    assert any("model_not_in_voice_models_manifest" in x for x in P.validate_catalog({"profiles": [base]}, {"other"}))


# ---- speech model manifest + repack tool
def test_voice_models_manifest_is_pinned_and_licensed():
    for m in MODELS["models"] + [MODELS["runtime"]]:
        assert len(m["sha256"]) == 64 and all(c in "0123456789abcdef" for c in m["sha256"]) and m["download_bytes"] > 1_000_000 and m["license"] and m["url"].startswith("https://github.com/")
    assert all(m["pidgin_support"].startswith("none") for m in MODELS["models"])        # nothing claims Pidgin support


def test_repack_flattens_drops_test_audio_and_fp32_twins(tmp_path):
    tar = tmp_path / "w.tar.bz2"
    with tarfile.open(tar, "w:bz2") as tf:
        for name, data in {"m/tiny-encoder.onnx": b"a" * 10, "m/tiny-encoder.int8.onnx": b"b" * 5, "m/tiny-tokens.txt": b"t", "m/test_wavs/0.wav": b"w"}.items():
            ti = tarfile.TarInfo(name); ti.size = len(data); tf.addfile(ti, io.BytesIO(data))
    z = tmp_path / "w.zip"; assert F.repack(tar, z, "whisper") == 2
    assert sorted(zipfile.ZipFile(z).namelist()) == ["m/tiny-encoder.int8.onnx", "m/tiny-tokens.txt"]


def test_repack_rejects_path_traversal(tmp_path):
    tar = tmp_path / "e.tar.bz2"
    with tarfile.open(tar, "w:bz2") as tf:
        ti = tarfile.TarInfo("../evil.txt"); ti.size = 1; tf.addfile(ti, io.BytesIO(b"x"))
    try: F.repack(tar, tmp_path / "e.zip", "piper-vits"); assert False
    except ValueError: pass


def test_fetch_rejects_wrong_hash(tmp_path):
    (tmp_path / "moonshine-tiny-en-int8.tar.bz2").write_bytes(b"not the real archive")
    assert F.main(["--out", str(tmp_path / "o"), "--from-dir", str(tmp_path), "--only", "sherpa-onnx-moonshine-tiny-en-int8"]) == 2
