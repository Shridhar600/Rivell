#!/usr/bin/env bash
# Shared demo topology for seed.sh, record.sh and stop.sh.
#
# Values only: this file defines paths, ports and process helpers. It starts nothing when
# sourced, and it never prints or copies a key.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND="$REPO/backend"
RUN_DIR="$BACKEND/target/demo-seed"
BACKEND_PORT="${RIVELL_DEMO_BACKEND_PORT:-8080}"
TAPE_PORT="${RIVELL_DEMO_TAPE_PORT:-18090}"
DB_PORT="${RIVELL_DEMO_DB_PORT:-5432}"
TAPE_URL="http://127.0.0.1:${TAPE_PORT}"
CONTAINER="rivell-demo-seed-pg"
TAPE_FILE="$REPO/demo/recordings/calls.jsonl"
DECISIONS_FILE="$REPO/demo/decisions/decisions.json"

JAR="$(ls -t "$BACKEND"/target/rivell-backend-*.jar 2>/dev/null \
    | grep -v -- '-plain\.jar$' | head -n 1 || true)"

set -a
# shellcheck source=/dev/null
source "$REPO/demo/run.env"
set +a

# A developer's shell must not be able to change the model identity or the request bodies:
# a changed temperature, response mode or dimension would make every recorded key miss.
unset OPENAI_TEMPERATURE OPENAI_RESPONSE_MODE RIVELL_INTELLIGENCE_GENERATIVE_RESPONSE_MODE \
    RIVELL_INTELLIGENCE_EMBEDDINGS_DIMENSIONS RIVELL_REVIEW_ANALYSIS_BUDGET \
    RIVELL_REVIEW_MAX_TRANSIENT_FAILURES RIVELL_INTELLIGENCE_RETRY_BACKOFF \
    RIVELL_INTELLIGENCE_RETRY_MAX_ELAPSED RIVELL_CONCEPT_THRESHOLD \
    RIVELL_REVIEW_WITHIN_SPLIT_MARGIN RIVELL_REVIEW_AGREED_WORSE_CATEGORY \
    RIVELL_REVIEW_R1_NEEDS_CORROBORATION RIVELL_REVIEW_FALLBACK_CONDITIONS \
    RIVELL_REVIEW_WALK_AWAY_ABSENCE

fail() {
    echo "$*" >&2
    exit 2
}

require_jar() {
    if [[ ! -f "${JAR:-}" ]]; then
        echo "packaged jar not found under $BACKEND/target; build it with:" >&2
        echo "  cd backend && ./mvnw -q -DskipTests package" >&2
        exit 2
    fi
}

require_keys() {
    local missing=0 var xtrace=0
    case $- in *x*) xtrace=1; set +x ;; esac
    for var in TYPESAFE_API_KEY OPENAI_API_KEY; do
        if [[ -z "${!var:-}" ]]; then
            echo "missing required environment variable: $var" >&2
            missing=1
        fi
    done
    if [[ $xtrace -eq 1 ]]; then
        set -x
    fi
    [[ $missing -eq 0 ]] || exit 2
}

reset_db() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$CONTAINER" \
        -e POSTGRES_DB=rivell -e POSTGRES_USER=rivell -e POSTGRES_PASSWORD=rivell \
        -p "127.0.0.1:${DB_PORT}:5432" pgvector/pgvector:0.8.6-pg17-trixie >/dev/null
    local _
    for _ in $(seq 1 60); do
        if docker exec "$CONTAINER" pg_isready -U rivell >/dev/null 2>&1; then
            echo "postgres up"
            return 0
        fi
        sleep 1
    done
    fail "PostgreSQL did not become ready in $CONTAINER"
}

require_db() {
    if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -qx true; then
        fail "database container $CONTAINER is not running; run --phase a first, or --recover"
    fi
}

require_free_ports() {
    if lsof -ti tcp:"$BACKEND_PORT" >/dev/null 2>&1; then
        fail "port $BACKEND_PORT is already in use; run scripts/demo/stop.sh first"
    fi
    if lsof -ti tcp:"$TAPE_PORT" >/dev/null 2>&1; then
        fail "port $TAPE_PORT is already in use; run scripts/demo/stop.sh first"
    fi
}

reset_storage() {
    mkdir -p "$RUN_DIR"
    rm -rf "$RUN_DIR/documents" "$RUN_DIR/misses"
    mkdir -p "$RUN_DIR/documents" "$RUN_DIR/misses"
}

start_tape() {
    local mode="$1" file="$2"
    mkdir -p "$RUN_DIR" "$RUN_DIR/misses" "$REPO/demo/recordings"
    if lsof -ti tcp:"$TAPE_PORT" >/dev/null 2>&1; then
        fail "port $TAPE_PORT is already in use; stop the old tape with scripts/demo/stop.sh"
    fi
    nohup env -u TYPESAFE_API_KEY -u OPENAI_API_KEY -u RIVELL_EMBEDDINGS_API_KEY \
        python3 -I "$REPO/scripts/demo/tape.py" --mode "$mode" --file "$file" \
        --port "$TAPE_PORT" --miss-dir "$RUN_DIR/misses" >>"$RUN_DIR/tape.log" 2>&1 &
    echo $! >"$RUN_DIR/tape.pid"
}

wait_tape() {
    local _
    for _ in $(seq 1 60); do
        if curl -fsS "$TAPE_URL/__tape__/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    fail "tape did not become ready; see $RUN_DIR/tape.log"
}

start_backend() {
    local mode="$1"
    mkdir -p "$RUN_DIR" "$REPO/demo/recordings"
    if lsof -ti tcp:"$BACKEND_PORT" >/dev/null 2>&1; then
        fail "port $BACKEND_PORT is already in use; stop the old backend with scripts/demo/stop.sh"
    fi
    if [[ "$mode" == "real" ]]; then
        require_keys
    fi
    (
        cd "$BACKEND"
        # Keys never go on the command line: ps would show them to every local user.
        # This is a subshell, so it also hides them from an enclosing `set -x`.
        { set +x; } 2>/dev/null
        if [[ "$mode" == "real" ]]; then
            export TYPESAFE_API_KEY OPENAI_API_KEY
            export RIVELL_EMBEDDINGS_API_KEY="$OPENAI_API_KEY"
        else
            export TYPESAFE_API_KEY=demo-replay-not-a-key
            export OPENAI_API_KEY=demo-replay-not-a-key
            export RIVELL_EMBEDDINGS_API_KEY=demo-replay-not-a-key
        fi
        exec env \
            RIVELL_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/rivell" \
            RIVELL_DOCUMENT_STORAGE_ROOT="$RUN_DIR/documents" \
            SERVER_PORT="$BACKEND_PORT" \
            SERVER_ADDRESS=127.0.0.1 \
            TYPESAFE_BASE_URL="http://127.0.0.1:${TAPE_PORT}" \
            OPENAI_BASE_URL="http://127.0.0.1:${TAPE_PORT}/v1" \
            RIVELL_EMBEDDINGS_BASE_URL="http://127.0.0.1:${TAPE_PORT}/v1" \
            nohup "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$JAR" >>"$RUN_DIR/backend.log" 2>&1
    ) &
    echo $! >"$RUN_DIR/backend.pid"
}

wait_backend() {
    local _
    for _ in $(seq 1 120); do
        if curl -fsS "http://127.0.0.1:${BACKEND_PORT}/api/system/status" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    fail "backend did not become ready within 240s; see $RUN_DIR/backend.log"
}

run_driver() {
    local phases="$1"
    shift
    ( cd "$REPO" && env -u TYPESAFE_API_KEY -u OPENAI_API_KEY -u RIVELL_EMBEDDINGS_API_KEY \
        python3 -I scripts/demo/demo_driver.py --phases "$phases" \
        --dump-dir "$RUN_DIR" --base-url "http://127.0.0.1:$BACKEND_PORT" \
        --tape-url "$TAPE_URL" "$@" )
}

stop_one() {
    local pidfile="$1" port="$2" pid _
    if [[ -f "$pidfile" ]]; then
        pid="$(cat "$pidfile" 2>/dev/null || true)"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
        rm -f "$pidfile"
    fi
    for _ in $(seq 1 20); do
        if ! lsof -ti tcp:"$port" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "warning: port $port is still held after stopping" >&2
    return 1
}

stop_services() {
    stop_one "$RUN_DIR/backend.pid" "$BACKEND_PORT" || true
    stop_one "$RUN_DIR/tape.pid" "$TAPE_PORT" || true
}
