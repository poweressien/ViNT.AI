#!/usr/bin/env python3
"""Static network audit of the Android project (milestone 4: hybrid AI).
ViNT may now use the network, but ONLY in a narrow, auditable way. Fails (exit 1) if:
  - the manifest declares a permission outside the allowlist (RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE) or enables cleartext traffic,
    or lacks a network security config that forbids cleartext;
  - a third-party networking/analytics/ads library is used (okhttp, retrofit, firebase, ...);
  - raw networking APIs (HttpURLConnection, java.net.URL, sockets, WebView) appear in any Kotlin file OUTSIDE ng/pidgingpt/net/;
  - an http:// URL or something shaped like an API key/secret appears in source (secrets never ship in the APK or repo).
This is a STATIC check; runtime verification (airplane mode, Offline Only) is in docs/OFFLINE_VERIFICATION.md."""
import re, sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "android-app")
problems = []
ALLOWED_PERMS = {"android.permission.RECORD_AUDIO", "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
FORBIDDEN_PERMS = ["android.permission.ACCESS_WIFI_STATE", "android.permission.READ_CONTACTS", "android.permission.READ_SMS",
                   "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.READ_PHONE_STATE",
                   "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.CAMERA"]
FORBIDDEN_DEPS = ["okhttp", "retrofit", "firebase", "crashlytics", "google-analytics", "play-services", "volley", "ktor-client", "sentry",
                  "amplitude", "mixpanel", "com.google.android.gms", "admob", "appsflyer", "segment"]
FORBIDDEN_CODE = ["java.net.URL(", "HttpURLConnection", "HttpsURLConnection", "java.net.Socket", "WebView(", "okhttp3", "javax.net.ssl", "URL("]
NET_DIR = ("ng", "pidgingpt", "net")
SECRET_PATTERNS = [r"sk-[A-Za-z0-9_-]{20,}", r"AIza[0-9A-Za-z_-]{30,}", r"gsk_[A-Za-z0-9]{20,}", r"hf_[A-Za-z0-9]{20,}", r"Bearer\s+[A-Za-z0-9._-]{20,}",
                   r"(?i)(api[_-]?key|secret|token)\s*=\s*\"[A-Za-z0-9_\-]{16,}\""]


def _skip(p): return "third_party" in p.parts or "build" in p.parts


def in_net_pkg(p: Path) -> bool:
    parts = p.parts
    return any(parts[i:i + 3] == NET_DIR for i in range(len(parts) - 2))


for m in [x for x in root.rglob("AndroidManifest.xml") if not _skip(x)]:
    t = m.read_text()
    for p in re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', t):
        if p not in ALLOWED_PERMS: problems.append(f"{m}: permission {p} is not on the allowlist")
    for p in FORBIDDEN_PERMS:
        if p in t: problems.append(f"{m}: forbidden permission {p}")
    if re.search(r'usesCleartextTraffic\s*=\s*"true"', t): problems.append(f"{m}: cleartext traffic enabled")
    if "android.permission.INTERNET" in t and "networkSecurityConfig" not in t: problems.append(f"{m}: INTERNET without a networkSecurityConfig")
for x in [x for x in root.rglob("network_security_config.xml") if not _skip(x)]:
    t = x.read_text()
    if re.search(r'cleartextTrafficPermitted\s*=\s*"true"', t) or 'src="user"' in t: problems.append(f"{x}: allows cleartext or user-installed CAs")


def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)(^|\s)//.*$|(^|\s)#.*$", "", t)


for g in [x for x in list(root.rglob("*.gradle.kts")) + list(root.rglob("*.gradle")) + list(root.rglob("*.toml")) if not _skip(x)]:
    t = strip_comments(g.read_text()).lower()
    for d in FORBIDDEN_DEPS:
        if d in t: problems.append(f"{g}: forbidden dependency '{d}'")
for k in [x for x in root.rglob("*.kt") if not _skip(x)]:
    raw = k.read_text(); t = strip_comments(raw)
    if not in_net_pkg(k):
        for c in FORBIDDEN_CODE:
            if c == "URL(":
                if re.search(r"(?<![A-Za-z])URL\(", t): problems.append(f"{k}: networking API 'URL(' outside ng/pidgingpt/net")
            elif c in t: problems.append(f"{k}: networking API '{c}' outside ng/pidgingpt/net")
    for u in re.findall(r"\"(http://[^\"\s]*)\"", t):
        problems.append(f"{k}: plain http:// address {u}")
    for pat in SECRET_PATTERNS:
        if re.search(pat, raw): problems.append(f"{k}: looks like an embedded secret ({pat[:18]}...)")

if problems:
    print("NETWORK AUDIT FAILED"); [print(" -", p) for p in problems]; sys.exit(1)
print(f"NETWORK AUDIT PASSED (static) for {root}: only RECORD_AUDIO/INTERNET/ACCESS_NETWORK_STATE, HTTPS-only config, "
      f"raw networking confined to ng/pidgingpt/net, no third-party network libraries, no embedded secrets.")
