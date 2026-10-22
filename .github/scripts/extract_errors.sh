#!/usr/bin/env bash
# Copies the decisive part of the Gradle log into the job summary and as an error annotation. Never fails the step.
set -u
log="${1:-}"; out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
[ -s "$log" ] || { echo "No Gradle log at '$log' (the failure happened before Gradle ran)."; exit 0; }
{
  echo "### Gradle failure excerpt"
  echo '```'
  { grep -nE '^e: |^w: .*error|error: |FAILED$|What went wrong' "$log" | head -n 20
    echo '--- context after "What went wrong" ---'
    awk '/What went wrong/{f=1} f{print; if(++n>=25) exit}' "$log"; } | cut -c1-300
  echo '```'
} >> "$out"
first="$(grep -m1 -E '^e: |error: ' "$log" | cut -c1-300 || true)"
[ -n "$first" ] && echo "::error title=First compile error::$first"
exit 0
