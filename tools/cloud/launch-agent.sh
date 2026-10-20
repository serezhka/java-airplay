#!/usr/bin/env bash
# Launch a Cursor Cloud Agent against this repository via the Cloud Agents API.
# Requires: CURSOR_API_KEY (Dashboard → API Keys). Never commit the key.
#
# Usage:
#   export CURSOR_API_KEY=...
#   ./tools/cloud/launch-agent.sh "Fix failing ubuntu gstreamer HLS integration test; open a PR"
#   ./tools/cloud/launch-agent.sh --ref main "Warm the Gradle caches and report ./gradlew test status"
set -euo pipefail

REPO_URL="${CURSOR_CLOUD_REPO_URL:-https://github.com/serezhka/java-airplay}"
REF="${CURSOR_CLOUD_REF:-main}"
MODEL_ID="${CURSOR_CLOUD_MODEL:-composer-2.5}"
AUTO_PR="${CURSOR_CLOUD_AUTO_PR:-true}"

if [[ "${1:-}" == "--ref" ]]; then
  REF="$2"
  shift 2
fi

PROMPT="${*:-}"
if [[ -z "$PROMPT" ]]; then
  echo "usage: $0 [--ref <git-ref>] <prompt...>" >&2
  exit 2
fi

if [[ -z "${CURSOR_API_KEY:-}" ]]; then
  echo "CURSOR_API_KEY is not set. Create one at https://cursor.com/dashboard?tab=api-keys" >&2
  exit 1
fi

BODY=$(PROMPT="$PROMPT" REPO_URL="$REPO_URL" REF="$REF" MODEL_ID="$MODEL_ID" AUTO_PR="$AUTO_PR" python3 - <<'PY'
import json, os
print(json.dumps({
    "prompt": {"text": os.environ["PROMPT"]},
    "model": {"id": os.environ["MODEL_ID"]},
    "repos": [{
        "url": os.environ["REPO_URL"],
        "startingRef": os.environ["REF"],
    }],
    "autoCreatePR": os.environ["AUTO_PR"].lower() in ("1", "true", "yes"),
}))
PY
)

echo "Starting cloud agent on $REPO_URL @ $REF ..."
RESP=$(curl -sS --request POST \
  --url https://api.cursor.com/v1/agents \
  -u "${CURSOR_API_KEY}:" \
  --header 'Content-Type: application/json' \
  --data "$BODY")

echo "$RESP" | python3 -m json.tool 2>/dev/null || echo "$RESP"

ID=$(echo "$RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('id') or d.get('agent',{}).get('id') or '')" 2>/dev/null || true)
if [[ -n "$ID" ]]; then
  echo ""
  echo "Agent id: $ID"
  echo "Dashboard: https://cursor.com/agents"
fi
