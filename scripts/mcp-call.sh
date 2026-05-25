#!/usr/bin/env bash
# Call a tool on a freshly-spawned movebank-mcp via direct MCP JSON-RPC.
# Faster than restarting Claude Code for inner-loop dev iteration.
#
# Usage:
#     ./scripts/mcp-call.sh <tool_name> [args_json]
#
# Examples:
#     ./scripts/mcp-call.sh list_studies
#     ./scripts/mcp-call.sh list_studies '{"filter":"albatross"}'
#     ./scripts/mcp-call.sh get_study '{"study_id":2911040}'
#
# Requires the server to have been built via ./gradlew installDist and
# a movebank-mirror-api to be running (default: http://localhost:8080).
#
# Prints the parsed tool result as JSON; non-zero exit on protocol error.

set -euo pipefail

TOOL="${1:?tool name required}"
ARGS="${2:-{\}}"

REPO="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$REPO/build/install/movebank-mcp/bin/movebank-mcp"

if [[ ! -x "$BIN" ]]; then
    echo "ERROR: $BIN not found or not executable." >&2
    echo "Run ./gradlew installDist first." >&2
    exit 2
fi

# Build the JSON-RPC handshake sequence + tool call. The notification
# (no id) doesn't get a response; only the two requests do.
read -r -d '' SEQUENCE <<JSON || true
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"mcp-call.sh","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$TOOL","arguments":$ARGS}}
JSON

# Run, then poll for our tool-call response (id=2) instead of relying
# on a fixed sleep — JVM warm-up varies. Reads stdout line-by-line and
# returns once the id-2 response arrives (or after a hard timeout).
RESPONSE=$(
    (echo "$SEQUENCE"; sleep 30) | "$BIN" --stdio 2>/dev/null \
    | while IFS= read -r line; do
          # Only the id=2 response is the tool call's; ignore initialize (id=1)
          # and any notifications (no id).
          if [[ "$line" == *'"id":2'* ]]; then
              echo "$line"
              break
          fi
      done
)

# Extract the tool's textual payload and pretty-print as JSON.
echo "$RESPONSE" | python3 -c "
import sys, json
r = json.loads(sys.stdin.read())
if 'error' in r:
    print(json.dumps(r['error'], indent=2), file=sys.stderr)
    sys.exit(1)
content = r['result']['content']
# A tool typically returns one text-type content block with a JSON body.
for block in content:
    if block.get('type') == 'text':
        try:
            print(json.dumps(json.loads(block['text']), indent=2))
        except json.JSONDecodeError:
            print(block['text'])
"
