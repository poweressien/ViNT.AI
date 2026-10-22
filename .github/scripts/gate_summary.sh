#!/usr/bin/env bash
# Gate: fails ONLY if a required job did not succeed. Its message names the failing jobs, so the cause is obvious.
set -u
bad=0
for pair in "python-checks=${R_PY:-}" "jni-host-verify=${R_JNI:-}" "android-debug=${R_APK:-}"; do
  name="${pair%%=*}"; res="${pair#*=}"
  if [ "$res" != "success" ]; then
    echo "::error title=Job not successful::$name finished with result '${res:-none}'. This Summary job fails because of that, not because of a reporting error."
    bad=1
  fi
done
exit "$bad"
