# Voice profiles and Nigerian accent coverage

## What exists today
`android-app/app/src/main/assets/voices/voice_profiles.json` (parsed by `VoiceCatalog.kt`, validated by `ai-training/voice/profiles.py` and tests):
| Profile | Status | Notes |
|---|---|---|
| English UK (northern), male voice, Piper | **available** (if its ZIP is imported) | CC BY-SA 4.0. Not a Nigerian accent |
| English UK (southern), female voice, Piper low | **available** (if imported) | CC BY-SA 4.0. Low quality. Not a Nigerian accent |
| English, Kokoro speaker 0 | **experimental** | Apache-2.0. Slower than real time on the host. Gender not claimed |
| Nigerian Pidgin: Akwa Ibom, Cross River, Rivers, Lagos, South-East | **unavailable** | placeholders only. No data, no model, **no claim of coverage** |

**Genuinely available Nigerian-accent voices: none.** The male/female labels describe the recorded English voices only.

## Rules (enforced by code and tests)
- A profile describes a recorded voice. ViNT never infers a user's accent, region, gender or ethnicity and never picks a voice from them (`VoiceCatalog.pick` uses only the stored choice and what is installed).
- Regions are not single accents. A future profile must be **one consented speaker** (or a documented set of speakers) with a **self-reported** background, never a region stereotype.
- No cloning of identifiable people without their written permission. In manifests that needs scope `voice_cloning` **and** `named_permission: true`.
- A selectable profile needs an installable model, a verified licence, attribution and a source; `nigerian_pidgin_style` must be `"none"` until data and evaluation exist.
- Unavailable profiles cannot reference a model and cannot be selected.

## Data collection and evaluation pipeline (`ai-training/voice/`, tested with synthetic fixtures only)
1. **Consent first.** Each recording needs a consent object: id, date, scopes (`asr_evaluation`, `asr_training`, `tts_training`, `public_release`, `voice_cloning`), and `can_withdraw: true`. A record is valid only for uses its consent covers.
2. **Self-reported background only.** Optional `speaker_self_reported` (first languages, Pidgin variety, region of upbringing, gender, age band; free text, can be omitted). Any `inferred_*` or annotator accent label is rejected.
3. **Quality gates:** SHA-256 per file, duplicates rejected, 16 kHz or higher, 0.5–60 s, verified transcript, licence and source recorded.
4. **Speaker-level splits** (`split_by_speaker`): no speaker appears in two splits (tested over 600 records).
5. **No unfair numbers:** per-group accuracy is reported only when a group has at least 10 distinct speakers and 30 minutes (configurable); otherwise the report says "insufficient data: not reported".
6. **Withdrawal:** `revoke()` removes a speaker's records and lists the audio files to delete.
7. **Scoring:** `evaluate_asr.py MANIFEST HYPOTHESES --field pidgin_variety` gives overall WER/CER and gated per-group WER.
Not built yet: a recording app/workflow, speaker-adaptation or TTS training, listener (MOS) evaluation, and any real dataset. Candidate public datasets must have their licences read before use; none has been ingested.

## To add a real profile
Collect consented, licensed data → train/fine-tune → evaluate with held-out speakers and listeners → package as a ZIP with `tools/voice_models.json` entry (pinned hash) → add a profile with status `experimental`, then `available` only after evaluation, with the evidence linked here.
