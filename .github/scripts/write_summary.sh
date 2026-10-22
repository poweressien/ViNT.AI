#!/usr/bin/env bash
# Writes the run summary from the needed jobs' results. REPORTING ONLY: always exits 0.
# Pass/fail gating happens in a separate step (gate_summary.sh) so a reporting problem can never mask the build result.
set -u
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
mark() {
  case "${1:-}" in
    success) echo "PASSED" ;;
    failure) echo "FAILED" ;;
    skipped) echo "SKIPPED" ;;
    cancelled) echo "CANCELLED" ;;
    "") echo "UNKNOWN (no result reported)" ;;
    *) echo "UNKNOWN ($1)" ;;
  esac
}
{
  echo "## ViNT build summary"
  echo
  echo "| Stage | Result |"
  echo "|---|---|"
  echo "| Python tests + static audits (no Android) | $(mark "${R_PY:-}") |"
  echo "| JNI bridge on host CPU, pinned llama.cpp (not the Android build) | $(mark "${R_JNI:-}") |"
  echo "| Android debug APK: Gradle, Kotlin/Compose, NDK/CMake arm64-v8a, APK checks | $(mark "${R_APK:-}") |"
  echo
  if [ "${R_APK:-}" = "success" ]; then
    echo "**APK:** download artifact **ViNT-debug-apk** from the *Artifacts* section at the bottom of this run page: ${RUN_URL:-}"
  else
    echo "**No APK was produced by this run.** Open the failed job (the first red step is the cause); diagnostics are in artifact **ViNT-build-diagnostics**. This Summary job is red only because a job above it is red."
  fi
  echo
  echo "_Not covered by CI: a real language model is never loaded and no AI inference is run; speech models are not downloaded; nothing runs on a phone._"
} >> "$out" || echo "::warning::could not write the job summary (build results are unaffected)"
exit 0
