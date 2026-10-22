"""Validator for android-app/app/src/main/assets/voices/voice_profiles.json (mirror of the rules enforced by VoiceCatalog.kt)."""
import json
from pathlib import Path

NIGERIAN_STYLE_VALUES_FOR_SELECTABLE = {"none"}      # nothing selectable may claim a Nigerian Pidgin style until data + evaluation exist


def validate_catalog(cat: dict, known_tts_model_ids: set[str] | None = None) -> list[str]:
    p, seen = [], set()
    for pr in cat.get("profiles", []):
        i = pr.get("id", "")
        if not i or i in seen: p.append(f"missing_or_duplicate_id:{i}")
        seen.add(i); st = pr.get("status")
        if st not in ("available", "experimental", "unavailable"): p.append(f"{i}:bad_status"); continue
        if st == "unavailable":
            if pr.get("model_id"): p.append(f"{i}:unavailable_profile_references_model")
            if not pr.get("status_reason"): p.append(f"{i}:unavailable_needs_reason")
            continue
        if not pr.get("model_id"): p.append(f"{i}:selectable_without_model")
        elif known_tts_model_ids is not None and pr["model_id"] not in known_tts_model_ids: p.append(f"{i}:model_not_in_voice_models_manifest")
        for k in ("license", "attribution", "source"):
            if not pr.get(k): p.append(f"{i}:missing_{k}")
        if pr.get("nigerian_pidgin_style") not in NIGERIAN_STYLE_VALUES_FOR_SELECTABLE: p.append(f"{i}:selectable_profile_claims_nigerian_style")
        if pr.get("voice_label") not in ("male", "female", "unspecified"): p.append(f"{i}:bad_voice_label")
    return p
