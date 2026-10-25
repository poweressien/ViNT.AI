import subprocess, sys, shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools/audit_android_offline.py"
KT = "app/src/main/java/ng/pidgingpt"


def run(p): return subprocess.run([sys.executable, str(TOOL), str(p)], capture_output=True, text=True)


def copy(tmp_path):
    shutil.copytree(ROOT / "android-app", tmp_path / "a", ignore=shutil.ignore_patterns("third_party"))
    return tmp_path / "a"


def test_real_project_passes():
    r = run(ROOT / "android-app"); assert r.returncode == 0, r.stdout


def test_internet_and_network_state_are_now_allowed_but_only_those():
    m = (ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text()
    import re
    perms = set(re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', m))
    assert perms == {"android.permission.RECORD_AUDIO", "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
    assert 'usesCleartextTraffic="false"' in m and "networkSecurityConfig" in m


def test_audit_catches_third_party_network_libraries(tmp_path):
    a = copy(tmp_path); g = a / "app/build.gradle.kts"
    g.write_text(g.read_text().replace("dependencies {", 'dependencies {\n    implementation("com.squareup.okhttp3:okhttp:4.12.0")', 1))
    r = run(a); assert r.returncode == 1 and "okhttp" in r.stdout


def test_audit_still_rejects_other_permissions(tmp_path):
    a = copy(tmp_path); m = a / "app/src/main/AndroidManifest.xml"
    m.write_text(m.read_text().replace("<application", '<uses-permission android:name="android.permission.READ_CONTACTS"/><application', 1))
    r = run(a); assert r.returncode == 1 and "READ_CONTACTS" in r.stdout


def test_audit_rejects_cleartext_and_user_cas(tmp_path):
    a = copy(tmp_path); x = a / "app/src/main/res/xml/network_security_config.xml"
    x.write_text(x.read_text().replace('cleartextTrafficPermitted="false"', 'cleartextTrafficPermitted="true"'))
    assert run(a).returncode == 1
    m = a / "app/src/main/AndroidManifest.xml"; m.write_text(m.read_text().replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"'))
    assert "cleartext" in run(a).stdout


def test_raw_networking_outside_the_net_package_is_rejected(tmp_path):
    a = copy(tmp_path); f = a / KT / "ai/Sneaky.kt"
    f.write_text('package ng.pidgingpt.ai\nfun leak() { val c = java.net.URL("https://x.example.com").openConnection(); HttpURLConnection::class }\n')
    r = run(a); assert r.returncode == 1 and "outside ng/pidgingpt/net" in r.stdout


def test_embedded_secrets_and_plain_http_are_rejected(tmp_path):
    a = copy(tmp_path); f = a / KT / "net/Keys.kt"
    f.write_text('package ng.pidgingpt.net\nconst val K = "sk-abcdefghijklmnopqrstuvwxyz123456"\nconst val U = "http://insecure.example.com"\n')
    r = run(a); assert r.returncode == 1 and "secret" in r.stdout and "http://" in r.stdout
