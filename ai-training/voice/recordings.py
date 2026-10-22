"""Consent-aware speech recording manifests for ViNT (ASR evaluation/training and, only with explicit permission, TTS).
Principles enforced in code:
  * no record without a consent object that covers the intended use;
  * voice cloning requires its own explicit, named permission (scope 'voice_cloning' + named_permission=True);
  * speaker background (languages, Pidgin variety, region, gender, age band) is SELF-REPORTED free text. Annotators and models must not infer it:
    any field starting with 'inferred_' is rejected;
  * splits are by SPEAKER (no speaker appears in two splits);
  * per-group results are reported only when enough distinct speakers and minutes exist, otherwise 'insufficient data';
  * a speaker can withdraw: revoke() removes all their records and lists the audio files to delete.
Records are JSON objects, one per line (JSONL). Nothing here records or stores audio itself."""
from __future__ import annotations
import hashlib, json, re
from collections import defaultdict
from pathlib import Path

CONSENT_SCOPES = {"asr_evaluation", "asr_training", "tts_training", "public_release", "voice_cloning"}
SELF_REPORTED = ("first_languages", "pidgin_variety", "region_of_upbringing", "gender", "age_band")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")


def validate_record(r: dict, use: str = "asr_evaluation") -> list[str]:
    """Returns a list of problems (empty = valid for the intended [use])."""
    p: list[str] = []
    for k in ("recording_id", "speaker_id", "audio_path", "audio_sha256", "sample_rate", "duration_s", "transcript", "license", "source", "consent"):
        if k not in r or r[k] in (None, "", {}): p.append(f"missing:{k}")
    if p: return p
    if not _HEX64.match(str(r["audio_sha256"])): p.append("bad_audio_sha256")
    if not isinstance(r["sample_rate"], int) or r["sample_rate"] < 16000: p.append("sample_rate_below_16k")
    if not isinstance(r["duration_s"], (int, float)) or not (0.5 <= r["duration_s"] <= 60): p.append("duration_out_of_range")
    if not str(r["transcript"]).strip(): p.append("empty_transcript")
    for k in r:
        if str(k).startswith("inferred_") or k in ("annotator_accent_label", "accent_label_by_annotator"): p.append(f"inferred_attribute_not_allowed:{k}")
    sr = r.get("speaker_self_reported", {})
    if not isinstance(sr, dict) or any(k not in SELF_REPORTED for k in sr): p.append("speaker_self_reported_has_unknown_or_non_dict_fields")
    c = r["consent"]
    if not isinstance(c, dict) or not c.get("consent_id") or not c.get("date"): p.append("consent_incomplete")
    else:
        scopes = set(c.get("scopes", []))
        if not scopes <= CONSENT_SCOPES or not scopes: p.append("consent_scopes_invalid")
        if use not in scopes: p.append(f"consent_does_not_cover:{use}")
        if use == "voice_cloning" and not c.get("named_permission"): p.append("voice_cloning_requires_named_permission")
        if "voice_cloning" in scopes and not c.get("named_permission"): p.append("voice_cloning_scope_without_named_permission")
        if c.get("can_withdraw") is not True: p.append("consent_must_be_withdrawable")
    return p


def load_manifest(path, use: str = "asr_evaluation"):
    """Returns (valid_records, problems_by_id). Duplicate audio hashes are rejected after the first."""
    good, bad, seen = [], {}, set()
    for i, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip(): continue
        try: r = json.loads(line)
        except json.JSONDecodeError: bad[f"line{i}"] = ["malformed_json"]; continue
        probs = validate_record(r, use) if isinstance(r, dict) else ["not_an_object"]
        if not probs and r["audio_sha256"] in seen: probs = ["duplicate_audio"]
        if probs: bad[r.get("recording_id", f"line{i}") if isinstance(r, dict) else f"line{i}"] = probs
        else: seen.add(r["audio_sha256"]); good.append(r)
    return good, bad


def assign_split(speaker_id: str, seed: str = "vint-voice-v1", dev: float = 0.1, test: float = 0.2) -> str:
    b = int(hashlib.sha256(f"{seed}:{speaker_id}".encode()).hexdigest()[:12], 16) / float(16 ** 12)
    return "test" if b < test else "dev" if b < test + dev else "train"


def speaker_leakage(splits: dict[str, list[dict]]) -> list[str]:
    owner: dict[str, str] = {}; leaks = set()
    for name, recs in splits.items():
        for r in recs:
            if owner.setdefault(r["speaker_id"], name) != name: leaks.add(r["speaker_id"])
    return sorted(leaks)


def split_by_speaker(records: list[dict], **kw) -> dict[str, list[dict]]:
    out = {"train": [], "dev": [], "test": []}
    for r in records: out[assign_split(r["speaker_id"], **kw)].append(r)
    return out


def group_report(records: list[dict], field: str, min_speakers: int = 10, min_minutes: float = 30.0) -> dict[str, dict]:
    """Coverage per SELF-REPORTED value. 'reportable' is False until there are enough distinct speakers AND minutes: below that, no accuracy
    number may be quoted for the group (small groups give meaningless, and potentially unfair, statistics)."""
    spk, mins = defaultdict(set), defaultdict(float)
    for r in records:
        v = r.get("speaker_self_reported", {}).get(field)
        v = ",".join(sorted(v)) if isinstance(v, list) else v
        key = v if v else "not_reported"
        spk[key].add(r["speaker_id"]); mins[key] += r["duration_s"] / 60
    return {k: {"speakers": len(spk[k]), "minutes": round(mins[k], 1), "reportable": k != "not_reported" and len(spk[k]) >= min_speakers and mins[k] >= min_minutes} for k in sorted(spk)}


def revoke(records: list[dict], speaker_id: str) -> tuple[list[dict], list[str]]:
    """Withdrawal of consent: returns (remaining records, audio files that must be deleted)."""
    keep = [r for r in records if r["speaker_id"] != speaker_id]
    return keep, [r["audio_path"] for r in records if r["speaker_id"] == speaker_id]
