#!/usr/bin/env python3
"""Download (or read locally), verify and repackage the speech models + the sherpa-onnx Android runtime for ViNT.
  python tools/fetch_voice_models.py --out dist/voice-models                 # download everything, verify SHA-256, write <id>.zip files
  python tools/fetch_voice_models.py --out dist --only sherpa-onnx-moonshine-tiny-en-int8 vits-piper-en_GB-northern_english_male-medium-int8
  python tools/fetch_voice_models.py --out dist --from-dir D:\\downloads      # use already-downloaded archives (matched by local_name)
  python tools/fetch_voice_models.py --runtime                               # fetch the sherpa-onnx AAR into android-app/app/libs/
Import the produced ZIPs in the app: Voice -> Speech models -> Import. Download happens on your PC, never inside the app."""
import argparse, hashlib, json, shutil, sys, tarfile, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools/voice_models.json"


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as r, open(dest, "wb") as f: shutil.copyfileobj(r, f)


def keep_member(name: str, family: str, siblings: set[str]) -> bool:
    """Drop things the app never loads: test audio, and fp32 ONNX files when an int8 twin exists (Whisper ships both)."""
    if "/test_wavs/" in name or name.endswith("/test_wavs"): return False
    if family == "whisper" and name.endswith(".onnx") and ".int8." not in name:
        return name.replace(".onnx", ".int8.onnx") not in siblings
    return True


def repack(archive: Path, out_zip: Path, family: str) -> int:
    """tar.bz2 -> zip with the model folder as the single top-level directory (the app flattens it). Returns files written."""
    n = 0
    with tarfile.open(archive, "r:*") as tf, zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        members = [m for m in tf.getmembers() if m.isfile()]
        names = {m.name for m in members}
        for m in members:
            if ".." in m.name.split("/") or m.name.startswith("/"): raise ValueError(f"unsafe path in archive: {m.name}")
            if keep_member(m.name, family, names):
                zf.writestr(m.name, tf.extractfile(m).read()); n += 1
    return n


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(); ap.add_argument("--out", default="dist/voice-models"); ap.add_argument("--only", nargs="*"); ap.add_argument("--from-dir")
    ap.add_argument("--runtime", action="store_true", help="fetch the sherpa-onnx Android AAR"); a = ap.parse_args(argv)
    man = json.loads(MANIFEST.read_text()); out = Path(a.out); tmp = out / "_downloads"
    if a.runtime:
        r = man["runtime"]; dest = ROOT / r["dest"]
        if not dest.exists(): print("downloading", r["url"]); download(r["url"], dest)
        got = sha256_file(dest)
        if got != r["sha256"]: print(f"HASH MISMATCH for {dest}: {got}"); dest.unlink(); return 2
        print(f"runtime OK: {dest} ({dest.stat().st_size} bytes, sha256 verified)"); return 0
    for m in man["models"]:
        if a.only and m["id"] not in a.only: continue
        src = Path(a.from_dir) / m["local_name"] if a.from_dir else tmp / (m["id"] + ".tar.bz2")
        if not src.exists():
            if a.from_dir: print(f"missing {src}"); return 2
            print("downloading", m["url"]); download(m["url"], src)
        got = sha256_file(src)
        if got != m["sha256"]: print(f"HASH MISMATCH for {m['id']}: expected {m['sha256']} got {got}"); return 2
        out.mkdir(parents=True, exist_ok=True); z = out / f"{m['id']}.zip"; n = repack(src, z, m["family"])
        print(f"OK {m['id']}: {n} files, {z.stat().st_size >> 20} MB zip, source hash verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
