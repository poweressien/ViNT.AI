#!/usr/bin/env python3
"""Host-side (Linux x86_64 CPU) benchmark of the SAME offline ASR/TTS models and sherpa-onnx C++ engine that the Android app uses.
It measures real runs and writes real numbers. It does NOT measure Nigerian Pidgin accuracy: there is no Pidgin speech in the sandbox.
 - ASR is scored on the English test recordings shipped with each model (real human speech, standard English).
 - 'TTS->ASR loop' scores synthetic speech and only shows what survives the round trip; it is not accuracy on real Pidgin speakers.
usage: host_bench.py MODEL_ROOT OUT_JSON"""
import json, resource, sys, time, wave
from pathlib import Path
import numpy as np
import sherpa_onnx as so
sys.path.insert(0, str(Path(__file__).parent)); from speech_metrics import wer, corpus_wer

root = Path(sys.argv[1]); out = Path(sys.argv[2])
rss_mb = lambda: resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024


def read_wav(p):
    with wave.open(str(p)) as w:
        sr, n = w.getframerate(), w.getnframes(); raw = w.readframes(n)
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0, sr


def transcribe(rec, samples, sr):
    s = rec.create_stream(); s.accept_waveform(sr, samples); t0 = time.perf_counter(); rec.decode_stream(s)
    return s.result.text.strip(), time.perf_counter() - t0


asr = {}
w = root / "whisper-tiny/sherpa-onnx-whisper-tiny"; m = root / "moonshine-tiny-en-int8/sherpa-onnx-moonshine-tiny-en-int8"
t0 = time.perf_counter()
asr["whisper-tiny (int8, language=en)"] = (so.OfflineRecognizer.from_whisper(str(w / "tiny-encoder.int8.onnx"), str(w / "tiny-decoder.int8.onnx"), str(w / "tiny-tokens.txt"), language="en", num_threads=1), time.perf_counter() - t0, w)
t0 = time.perf_counter()
asr["moonshine-tiny-en (int8)"] = (so.OfflineRecognizer.from_moonshine(str(m / "preprocess.onnx"), str(m / "encode.int8.onnx"), str(m / "uncached_decode.int8.onnx"), str(m / "cached_decode.int8.onnx"), str(m / "tokens.txt"), num_threads=1), time.perf_counter() - t0, m)

result = {"host": "Linux x86_64, 1 CPU core (NOT a phone)", "sherpa_onnx": so.__version__, "asr_standard_english": {}, "tts": {}, "tts_to_asr_loop_synthetic": {}}
for name, (rec, load_s, d) in asr.items():
    pairs, rtfs = [], []
    trans = dict(l.split(" ", 1) for l in (d / "test_wavs/trans.txt").read_text().strip().splitlines())
    for wav, ref in trans.items():
        x, sr = read_wav(d / "test_wavs" / wav); hyp, sec = transcribe(rec, x, sr); pairs.append((ref, hyp)); rtfs.append(sec / (len(x) / sr))
    result["asr_standard_english"][name] = {"load_seconds": round(load_s, 2), "n_utterances": len(pairs), "corpus_wer": round(corpus_wer(pairs), 3),
        "mean_real_time_factor": round(float(np.mean(rtfs)), 3), "examples": [{"ref": r, "hyp": h} for r, h in pairs]}
    print(name, result["asr_standard_english"][name]["corpus_wer"], "RTF", result["asr_standard_english"][name]["mean_real_time_factor"], flush=True)

K = root / "kokoro-int8-en-v0_19/kokoro-int8-en-v0_19"; PN = root / "piper-nem-int8/vits-piper-en_GB-northern_english_male-medium-int8"; PS = root / "piper-sef-low-int8/vits-piper-en_GB-southern_english_female-low-int8"
def vits(d, name): return so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(vits=so.OfflineTtsVitsModelConfig(model=str(d / name), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")), num_threads=1, provider="cpu"))
cfgs = {
  "kokoro-int8-en-v0_19": so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(kokoro=so.OfflineTtsKokoroModelConfig(model=str(K / "model.int8.onnx"), voices=str(K / "voices.bin"), tokens=str(K / "tokens.txt"), data_dir=str(K / "espeak-ng-data")), num_threads=1, provider="cpu")),
  "piper en_GB northern_english_male (medium)": vits(PN, "en_GB-northern_english_male-medium.onnx"),
  "piper en_GB southern_english_female (low)": vits(PS, "en_GB-southern_english_female-low.onnx"),
}
sentences = ["How body? I dey kampe, no wahala.", "Abeg make you wait small, I go come back.", "Wetin dey happen for market today?", "Person wey no hear word go hear am."]
tts_objs = {}
for name, cfg in cfgs.items():
    t0 = time.perf_counter(); tts = so.OfflineTts(cfg); load_s = time.perf_counter() - t0; tts_objs[name] = tts
    rtfs, firsts = [], []
    for text in sentences:
        t0 = time.perf_counter(); a = tts.generate(text, sid=0, speed=1.0); sec = time.perf_counter() - t0
        dur = len(a.samples) / a.sample_rate; rtfs.append(sec / dur)
    result["tts"][name] = {"load_seconds": round(load_s, 2), "sample_rate": a.sample_rate, "num_speakers": tts.num_speakers, "mean_real_time_factor": round(float(np.mean(rtfs)), 3), "peak_rss_mb_so_far": round(rss_mb())}
    print("TTS", name, result["tts"][name], flush=True)

# synthetic loop: Pidgin text -> TTS (English voices) -> ASR. Shows how an English-trained pipeline copes with Pidgin spellings.
for tname in ("kokoro-int8-en-v0_19",):
    tts = tts_objs[tname]
    for aname, (rec, _, _) in asr.items():
        pairs, rows = [], []
        for text in sentences:
            a = tts.generate(text, sid=0, speed=1.0); hyp, _ = transcribe(rec, np.array(a.samples, dtype=np.float32), a.sample_rate); pairs.append((text, hyp)); rows.append({"ref": text, "hyp": hyp})
        result["tts_to_asr_loop_synthetic"][f"{tname} -> {aname}"] = {"corpus_wer": round(corpus_wer(pairs), 3), "rows": rows}
        print("LOOP", tname, "->", aname, result["tts_to_asr_loop_synthetic"][f"{tname} -> {aname}"]["corpus_wer"], flush=True)
result["peak_rss_mb_total"] = round(rss_mb())
out.write_text(json.dumps(result, indent=2, ensure_ascii=False)); print("peak RSS MB", result["peak_rss_mb_total"])
