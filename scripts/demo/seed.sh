#!/usr/bin/env bash
# Offline replay seed: PostgreSQL, the model tape in replay mode, and the backend pointed
# at it, then the driver rebuilds the Bramber demo through the API. Leaves the demo running.
set -euo pipefail

# shellcheck source=scripts/demo/_env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_env.sh"

capture=0
for arg in "$@"; do
    case "$arg" in
        --capture) capture=1 ;;
        *) fail "unknown argument: $arg (expected --capture)" ;;
    esac
done

# The replay path needs no provider keys and must not carry a developer's real ones:
# the tape is stdlib-only and the driver talks to the backend, not to a provider.
unset TYPESAFE_API_KEY OPENAI_API_KEY RIVELL_EMBEDDINGS_API_KEY

if [[ ! -f "$REPO/demo/recordings/calls.jsonl.gz" ]]; then
    fail "recording not found; run the record run first"
fi
if [[ -f "$TAPE_FILE" && "$TAPE_FILE" -nt "$REPO/demo/recordings/calls.jsonl.gz" ]]; then
    fail "recording is not packed: $TAPE_FILE is newer than calls.jsonl.gz; run scripts/demo/tape.py --pack"
fi
if [[ ! -f "$DECISIONS_FILE" ]]; then
    fail "decisions file not found: $DECISIONS_FILE"
fi

if [[ "$capture" -eq 1 ]]; then
    rm -rf "$REPO/demo/capture"
    mkdir -p "$REPO/demo/capture"
fi

require_jar
require_free_ports
reset_db
reset_storage

start_tape replay "$REPO/demo/recordings/calls.jsonl.gz"
trap stop_services EXIT
wait_tape

if ! curl -fsS "$TAPE_URL/__tape__/health" \
    | python3 -I -c 'import json,sys; assert json.load(sys.stdin)["mode"] == "replay"'; then
    fail "tape is not in replay mode; refusing to seed against a recording tape"
fi

start_backend dummy
wait_backend

if [[ "$capture" -eq 1 ]]; then
    if ! run_driver all --capture-dir demo/capture; then
        stop_services
        exit 1
    fi
else
    if ! run_driver all; then
        stop_services
        exit 1
    fi
fi

trap - EXIT
echo "Demo ready at http://localhost:${BACKEND_PORT} (backend $(cat "$RUN_DIR/backend.pid"), tape $(cat "$RUN_DIR/tape.pid")). Stop with scripts/demo/stop.sh."
