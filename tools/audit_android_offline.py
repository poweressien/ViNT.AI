#!/usr/bin/env python3
"""Static offline audit of the Android project. Fails (exit 1) if the app declares network permissions,
uses cleartext traffic, or references common networking/analytics libraries. Run in CI and before release.
This is a STATIC check; runtime verification (airplane mode) is in docs/OFFLINE_VERIFICATION.md."""
import re, sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "android-app")
problems = []
ALLOWED_PERMS = {"android.permission.RECORD_AUDIO"}      # voice chat only; everything else is rejected
FORBIDDEN_PERMS = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                   "android.permission.ACCESS_WIFI_STATE", "android.permission.READ_CONTACTS",
                   "android.permission.READ_SMS", "android.permission.ACCESS_FINE_LOCATION"]
FORBIDDEN_DEPS = ["okhttp", "retrofit", "firebase", "crashlytics", "google-analytics", "play-services",
                  "volley", "ktor-client", "sentry", "amplitude", "mixpanel", "com.google.android.gms"]
FORBIDDEN_CODE = ["java.net.URL(", "HttpURLConnection", "java.net.Socket", "WebView(", "okhttp3"]

def _skip(p): return "third_party" in p.parts or "build" in p.parts

for m in [x for x in root.rglob("AndroidManifest.xml") if not _skip(x)]:
    t = m.read_text()
    for p in re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', t):
        if p not in ALLOWED_PERMS: problems.append(f"{m}: permission {p} is not on the allowlist")
    for p in FORBIDDEN_PERMS:
        if p in t: problems.append(f"{m}: forbidden permission {p}")
    if re.search(r'usesCleartextTraffic\s*=\s*"true"', t): problems.append(f"{m}: cleartext traffic enabled")
def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)(^|\s)//.*$|(^|\s)#.*$", "", t)

for g in [x for x in list(root.rglob("*.gradle.kts")) + list(root.rglob("*.gradle")) + list(root.rglob("*.toml")) if not _skip(x)]:
    t = strip_comments(g.read_text()).lower()
    for d in FORBIDDEN_DEPS:
        if d in t: problems.append(f"{g}: forbidden dependency '{d}'")
for k in [x for x in root.rglob("*.kt") if not _skip(x)]:
    t = strip_comments(k.read_text())
    for c in FORBIDDEN_CODE:
        if c in t: problems.append(f"{k}: forbidden networking API '{c}'")

if problems:
    print("OFFLINE AUDIT FAILED"); [print(" -", p) for p in problems]; sys.exit(1)
print(f"OFFLINE AUDIT PASSED (static) for {root}: no network permissions, libraries, or APIs found.")
