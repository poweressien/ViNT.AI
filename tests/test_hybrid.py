"""Static checks for the hybrid AI milestone. These do NOT compile or run Kotlin (no Kotlin/Gradle in this environment):
they guard the architecture rules that must hold regardless: layering, secrets, privacy, spec coverage by unit tests."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "android-app/app/src/main/java/ng/pidgingpt"
T = ROOT / "android-app/app/src/test/java/ng/pidgingpt"
ALL_KT = list((ROOT / "android-app/app/src").rglob("*.kt"))


def read(p): return Path(p).read_text()


def _balanced(path):
    """Small state machine: brackets balanced outside strings (incl. ${} templates), chars and comments."""
    t = read(path); i = 0; n = len(t); st = []; pairs = {")": "(", "}": "{", "]": "["}
    def skip_str(i):
        i += 1
        while t[i] != '"':
            if t[i] == "\\": i += 1
            elif t.startswith("${", i):
                d = 1; i += 2
                while d:
                    if t[i] == "{": d += 1
                    elif t[i] == "}": d -= 1
                    elif t[i] == '"': i = skip_str(i); continue
                    i += 1
                continue
            i += 1
        return i + 1
    while i < n:
        c = t[i]
        if t.startswith("//", i): j = t.find("\n", i); i = n if j < 0 else j; continue
        if t.startswith("/*", i): i = t.find("*/", i) + 2; continue
        if t.startswith('"""', i): i = t.find('"""', i + 3) + 3; continue
        if c == '"': i = skip_str(i); continue
        if c == "'":
            j = i + 1 + (1 if t[i + 1] == "\\" else 0); i = t.find("'", j) + 1; continue
        if c in "({[": st.append(c)
        elif c in ")}]":
            if not st or st.pop() != pairs[c]: return f"unbalanced {c} near line {t[:i].count(chr(10)) + 1}"
        i += 1
    return None if not st else "unclosed bracket"


def test_kotlin_files_have_balanced_brackets():
    for f in ALL_KT: assert _balanced(f) is None, (f, _balanced(f))


def test_pure_core_has_no_android_imports():
    """The routing logic must stay JVM-testable: manager, types, router, profiles, wire format, downloader logic."""
    pure = [J / "ai/AiTypes.kt", J / "ai/AIProvider.kt", J / "ai/AIProviderManager.kt", J / "ai/LanguageRouter.kt", J / "ai/ModelProfile.kt",
            J / "net/OpenAiWire.kt", J / "net/OnlineConfig.kt", J / "net/ModelDownloader.kt", J / "net/OnlineChatProvider.kt", J / "ai/LocalProviders.kt"]
    for f in pure: assert not re.search(r"^import (android\.|androidx\.)", read(f), re.M), f


def test_ui_never_calls_engine_or_network_directly():
    ui = "".join(read(f) for f in (J / "ui").glob("*.kt")) + read(J / "MainActivity.kt")
    for banned in ("LlamaEngine", "HttpUrlTransport", "HttpURLConnection", "OnlineChatProvider(", "nativeGenerate"):
        assert banned not in ui, banned
    vm = read(J / "ChatViewModel.kt")
    assert "engine.generate" not in vm and "manager.submit" in vm


def test_offline_only_is_enforced_before_the_online_provider_is_consulted():
    m = read(J / "ai/AIProviderManager.kt")
    assert re.search(r"if \(pol\.mode == AiMode\.OFFLINE_ONLY \|\| !allowOnline\) return no\(", m)
    assert m.index("OFFLINE_ONLY || !allowOnline") < m.index("usable(ProviderKind.ONLINE)")


def test_online_is_opt_in_and_default_safe():
    s = read(J / "data/Settings.kt")
    assert 'getBoolean("onlineConsent", false)' in s and 'getBoolean("onlineInVoice", false)' in s
    assert "FLAG_DEBUGGABLE" in read(J / "ChatViewModel.kt")      # provider keys only in debug builds


def test_no_secret_logging_or_logcat_of_message_content():
    for f in (J).rglob("*.kt"):
        t = read(f)
        assert "Log.d(" not in t and "Log.i(" not in t and "println(" not in t.replace("fun println", ""), f


def test_downloads_only_from_explicit_user_action():
    vm = read(J / "ChatViewModel.kt"); ui = read(J / "ui/Screens.kt")
    assert vm.count("downloader.download(") == 1 and "fun downloadModel" in vm
    assert "vm.downloadModel(entry)" in ui and ui.count("vm.downloadModel(") == 1          # only from the confirmation dialog
    assert "ModelCatalog.all.forEach { DownloadCard" in ui


def test_no_model_weights_or_keys_in_repo():
    for p in ROOT.rglob("*"):
        if p.is_file() and ".git" not in p.parts and "third_party" not in p.parts:
            assert p.suffix not in (".gguf", ".safetensors"), p
    gradle = read(ROOT / "android-app/app/build.gradle.kts")
    assert "gguf" not in gradle.lower().replace('"db", "gguf"', "")


def test_catalog_does_not_invent_a_checksum_or_pidgin_claim():
    c = read(J / "net/ModelDownloader.kt")
    assert "sha256 = null" in c and "Not Pidgin-trained" in c


def test_every_spec_test_area_has_a_unit_test():
    text = "".join(read(f) for f in T.rglob("*.kt")).lower()
    needles = ["automaticwithvalidatedinternet", "automaticwithoutinternet", "wifi", "mobiledata", "withoutvalidatedinternet", "networkloss",
               "timeout", "servererror", "localloadfailure", "offlineonlyneverselects", "onlinepreferredfallsback", "missinglocalmodel",
               "interrupteddownload", "checksum", "explicitenglishwins", "explicitpidginwins", "mixedenglishandpidgin", "registeredpidgin",
               "sameRequestIdIsNeverProcessedTwice".lower(), "historyisidentical", "cancel", "keysintheaddress", "directkeyonlyindebug"]
    for n in needles: assert n in text, n


def test_unit_test_sources_use_no_real_network_or_key():
    for f in T.rglob("*.kt"):
        t = read(f); assert "HttpUrlTransport" not in t and "System.getenv" not in t and not re.search(r"sk-[A-Za-z0-9]{20,}", t), f


def test_existing_offline_and_brand_audits_still_pass():
    for tool in ("audit_android_offline.py", "audit_brand_palette.py"):
        r = subprocess.run(["python3", str(ROOT / "tools" / tool), str(ROOT / "android-app")], capture_output=True, text=True); assert r.returncode == 0, r.stdout


def test_ci_workflow_exists_and_fetches_dependencies_before_gradle():
    w = read(ROOT / ".github/workflows/android-build.yml")
    order = ["python -m pytest -q", "audit_android_offline.py", "setup_llama_cpp.sh", "fetch_voice_models.py --runtime", ":app:help", ":app:testDebugUnitTest", ":app:assembleDebug", "upload-artifact"]
    at = 0
    for x in order:                                  # each step must appear AFTER the previous one
        i = w.find(x, at); assert i >= 0, f"missing or out of order: {x}"; at = i
    assert "gradle-version: '8.9'" in w and "working-directory: android-app" in w and "pidgingpt/" not in w      # paths are relative to the repo root


def test_ci_never_requests_the_obsolete_sdk_tools_package_and_reads_one_toolchain_source():
    w = "\n".join(l for l in read(ROOT / ".github/workflows/android-build.yml").splitlines() if not l.lstrip().startswith("#"))   # ignore comments
    assert not re.search(r"sdkmanager[^\n]*\btools\b", w) and "setup-android" not in w
    props = read(ROOT / "android-app/gradle.properties")
    for k in ("vint.compileSdk", "vint.buildTools", "vint.ndk", "vint.cmake"):
        assert re.search(rf"^{re.escape(k)}=\S+", props, re.M), k
    g = read(ROOT / "android-app/app/build.gradle.kts")
    for k in ("vint.compileSdk", "vint.buildTools", "vint.ndk", "vint.cmake"): assert f'gradleProperty("{k}")' in g, k
    for needle in ("platforms;android-${COMPILE_SDK}", "build-tools;${BUILD_TOOLS_VERSION}", "ndk;${NDK_VERSION}", "cmake;${CMAKE_VERSION}", "--licenses"): assert needle in w, needle
    assert 'applicationId = "ng.pidgingpt"' in g


def test_ci_keeps_every_required_stage_and_an_honest_summary():
    w = read(ROOT / ".github/workflows/android-build.yml")
    for needle in ("python -m pytest -q", "run_host_verify.sh", ":app:testDebugUnitTest", ":app:assembleDebug", "lib/arm64-v8a", "upload-artifact", "GITHUB_STEP_SUMMARY", "NOT RUN", "No verified build"):
        assert needle in w, needle
    assert "continue-on-error" not in w            # nothing is allowed to fail silently


def _copy_android(tmp_path):
    import shutil
    shutil.copytree(ROOT / "android-app", tmp_path / "a", ignore=shutil.ignore_patterns("third_party", "build"))
    return tmp_path / "a"


def _gradle_check(a):
    return subprocess.run(["python3", str(ROOT / "tools/check_gradle_layout.py"), str(a)], capture_output=True, text=True)


def test_gradle_plugin_layout_is_consistent():
    r = _gradle_check(ROOT / "android-app"); assert r.returncode == 0, r.stdout


def test_gradle_layout_check_catches_a_missing_plugin_version(tmp_path):
    a = _copy_android(tmp_path); f = a / "build.gradle.kts"
    f.write_text(f.read_text().replace('id("com.android.application") version "8.5.2" apply false', 'id("com.android.application") apply false'))
    r = _gradle_check(a); assert r.returncode == 1 and "exactly one version" in r.stdout and "===== " in r.stdout


def test_gradle_layout_check_catches_duplicate_versions_and_missing_repositories(tmp_path):
    a = _copy_android(tmp_path); g = a / "app/build.gradle.kts"
    g.write_text(g.read_text().replace('id("com.android.application")', 'id("com.android.application") version "8.5.2"', 1))
    assert "duplicate" in _gradle_check(a).stdout
    s = a / "settings.gradle.kts"; s.write_text(s.read_text().replace("gradlePluginPortal()", ""))
    assert "missing gradlePluginPortal()" in _gradle_check(a).stdout


def test_ci_checks_gradle_layout_before_gradle_runs():
    w = read(ROOT / ".github/workflows/android-build.yml")
    assert w.count("check_gradle_layout.py") >= 2 and w.index("check_gradle_layout.py", w.index("android:\n    runs-on")) < w.index(":app:help")
