import subprocess, sys, shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools/audit_android_offline.py"


def run(p): return subprocess.run([sys.executable, str(TOOL), str(p)], capture_output=True, text=True)


def test_real_project_passes():
    assert run(ROOT / "android-app").returncode == 0


def test_audit_catches_internet_permission_and_okhttp(tmp_path):
    shutil.copytree(ROOT / "android-app", tmp_path / "a", ignore=shutil.ignore_patterns("third_party"))
    m = tmp_path / "a/app/src/main/AndroidManifest.xml"
    m.write_text(m.read_text().replace("<application", '<uses-permission android:name="android.permission.INTERNET"/><application', 1))
    g = tmp_path / "a/app/build.gradle.kts"
    g.write_text(g.read_text().replace("dependencies {", 'dependencies {\n    implementation("com.squareup.okhttp3:okhttp:4.12.0")', 1))
    r = run(tmp_path / "a")
    assert r.returncode == 1 and "INTERNET" in r.stdout and "okhttp" in r.stdout


def test_audit_allows_microphone_but_rejects_other_permissions(tmp_path):
    shutil.copytree(ROOT / "android-app", tmp_path / "a", ignore=shutil.ignore_patterns("third_party"))
    m = tmp_path / "a/app/src/main/AndroidManifest.xml"
    assert 'android.permission.RECORD_AUDIO' in m.read_text() and run(tmp_path / "a").returncode == 0
    m.write_text(m.read_text().replace("<application", '<uses-permission android:name="android.permission.READ_CONTACTS"/><application', 1))
    r = run(tmp_path / "a"); assert r.returncode == 1 and "READ_CONTACTS" in r.stdout
