#!/usr/bin/env bash
# Watch the latest (or named) workflow run of SecretArrow/RockEdit.
# Usage: GITHUB_TOKEN=... scripts/ci-watch.sh [ci|e2e|release] [run-id]
set -euo pipefail
REPO="${REPO:-SecretArrow/RockEdit}"
WF="${1:-ci}"
RUN_ID="${2:-}"
: "${GITHUB_TOKEN:?Set GITHUB_TOKEN env var}"
case "$WF" in
  ci) FILE=ci.yml ;;
  e2e) FILE=e2e.yml ;;
  release) FILE=release.yml ;;
  *) FILE="$WF" ;;
esac
if [ -z "$RUN_ID" ]; then
  RUN_ID=$(curl -sf -H "Authorization: token $GITHUB_TOKEN" \
    "https://api.github.com/repos/$REPO/actions/workflows/$FILE/runs?per_page=1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["workflow_runs"][0]["id"])')
fi
while true; do
  DATA=$(curl -sf -H "Authorization: token $GITHUB_TOKEN" "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID")
  STATUS=$(echo "$DATA" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d.get("conclusion") or "-")')
  echo "[$(date +%H:%M:%S)] run $RUN_ID: $STATUS"
  case "$STATUS" in
    completed*) exit 0 ;;
  esac
  sleep 30
done
