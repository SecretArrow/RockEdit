#!/usr/bin/env bash
# Download logs for a run (zip) and print the failing steps summary.
# Usage: GITHUB_TOKEN=... scripts/ci-logs.sh <run-id>
set -euo pipefail
REPO="${REPO:-SecretArrow/RockEdit}"
RUN_ID="${1:?Usage: ci-logs.sh <run-id>}"
OUT="${2:-/tmp/rockedit-ci-logs}"
: "${GITHUB_TOKEN:?Set GITHUB_TOKEN env var}"
mkdir -p "$OUT"
echo ">> jobs summary:"
curl -sf -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/jobs" \
  | python3 -c '
import json, sys
for j in json.load(sys.stdin)["jobs"]:
    name = j["name"]; conc = j["conclusion"]
    print("- %s: %s" % (name, conc))
    for s in j["steps"]:
        if s["conclusion"] not in ("success", "skipped", None):
            print("    x %s: %s" % (s["name"], s["conclusion"]))
'
echo ">> downloading logs to $OUT.zip"
curl -sfL -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/logs" -o "$OUT.zip"
echo ">> unzip with: unzip -o $OUT.zip -d $OUT"
