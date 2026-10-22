#!/usr/bin/env python3
"""Stage A baseline: run a GGUF over eval_prompts.jsonl with llama.cpp's prebuilt `llama-server` on 127.0.0.1 and write responses.jsonl,
then score them with run_eval.py. DEVELOPMENT TOOL ONLY: the Android app never runs a server or opens a socket. The server binds to localhost
for the duration of this script and is stopped afterwards. Uses the same system prompts as the app (system_prompts.json).
  python baseline_llamaserver.py --server llama-b*/llama-server --model model.gguf --label baseline-qwen3-1.7b --out results/ [--voice] [--mode natural|strict]
Windows: download llama-b*-bin-win-cpu-x64.zip from https://github.com/ggml-org/llama.cpp/releases and point --server at llama-server.exe."""
import argparse, json, os, signal, socket, subprocess, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent


def free_port() -> int:
    with socket.socket() as s: s.bind(("127.0.0.1", 0)); return s.getsockname()[1]


def http(url, payload=None, timeout=600):
    req = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read())


def system_prompt(mode: str, voice: bool, qwen3: bool) -> str:
    p = json.loads((HERE / "system_prompts.json").read_text(encoding="utf-8"))
    return p[mode] + (p["voice_note"] if voice else "") + (p["no_think_suffix"] if qwen3 else "")


def run(server, model, prompts, mode="natural", voice=False, qwen3=False, ctx=2048, max_tokens=200, temperature=0.7, seed=42, threads=None, startup_timeout=300):
    port = free_port(); env = dict(os.environ); env["LD_LIBRARY_PATH"] = str(Path(server).parent) + os.pathsep + env.get("LD_LIBRARY_PATH", "")
    cmd = [str(server), "-m", str(model), "--host", "127.0.0.1", "--port", str(port), "-c", str(ctx), "--no-webui"] + (["-t", str(threads)] if threads else [])
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
    base = f"http://127.0.0.1:{port}"; rows = []
    try:
        t0 = time.time()
        while True:
            if proc.poll() is not None: raise RuntimeError("llama-server exited (model failed to load?)")
            try:
                if http(base + "/health", timeout=3).get("status") == "ok": break
            except Exception: pass
            if time.time() - t0 > startup_timeout: raise TimeoutError("llama-server did not become healthy")
            time.sleep(0.5)
        load_s = time.time() - t0; sysmsg = system_prompt(mode, voice, qwen3)
        for p in prompts:
            t1 = time.time()
            out = http(base + "/v1/chat/completions", {"messages": [{"role": "system", "content": sysmsg}, {"role": "user", "content": p["prompt"]}], "max_tokens": max_tokens,
                                                        "temperature": temperature, "seed": seed, "chat_template_kwargs": {"enable_thinking": not qwen3}})
            text = out["choices"][0]["message"]["content"] or ""
            while "<think>" in text and "</think>" in text: text = text[:text.index("<think>")] + text[text.index("</think>") + 8:]
            u = out.get("usage", {}); sec = time.time() - t1
            rows.append({"id": p["id"], "response": text.strip(), "completion_tokens": u.get("completion_tokens"), "seconds": round(sec, 2)})
        return rows, load_s
    finally:
        proc.send_signal(signal.SIGTERM) if os.name != "nt" else proc.terminate()
        try: proc.wait(10)
        except subprocess.TimeoutExpired: proc.kill()


def main(argv=None):
    ap = argparse.ArgumentParser(); ap.add_argument("--server", required=True); ap.add_argument("--model", required=True); ap.add_argument("--label", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--prompts", default=str(HERE / "eval_prompts.jsonl")); ap.add_argument("--mode", choices=["natural", "strict"], default="natural")
    ap.add_argument("--voice", action="store_true"); ap.add_argument("--qwen3", action="store_true", help="append /no_think and disable thinking (Qwen3-family)")
    ap.add_argument("--ctx", type=int, default=2048); ap.add_argument("--max-tokens", type=int, default=200); a = ap.parse_args(argv)
    prompts = [json.loads(l) for l in Path(a.prompts).read_text(encoding="utf-8").splitlines() if l.strip()]
    rows, load_s = run(a.server, a.model, prompts, a.mode, a.voice, a.qwen3, a.ctx, a.max_tokens)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True); resp = out / f"{a.label}.responses.jsonl"
    resp.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
    subprocess.run([sys.executable, str(HERE / "run_eval.py"), "--prompts", a.prompts, "--backend", "jsonl", "--responses", str(resp), "--label", a.label, "--out", str(out / f"{a.label}.scores.json")], check=True)
    print(f"model load {load_s:.1f}s; {len(rows)} responses -> {resp}\nNEXT: human-score these replies with docs/HUMAN_EVAL_RUBRIC.md (automatic scores are screening heuristics only).")


if __name__ == "__main__":
    main()
