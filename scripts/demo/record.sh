#!/usr/bin/env bash
# The CTO's record run. Needs TYPESAFE_API_KEY and OPENAI_API_KEY; never prints them.
#   scripts/demo/record.sh --phase a|b|c|all
#   scripts/demo/record.sh --recover        (implies all; resets the database, keeps the tape)
set -euo pipefail

# shellcheck source=scripts/demo/_env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_env.sh"

PHASE=""
RECOVER=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --phase)
            [[ $# -ge 2 ]] || fail "usage: scripts/demo/record.sh --phase a|b|c|all [--recover]"
            PHASE="$2"
            shift 2
            ;;
        --recover)
            RECOVER=1
            shift
            ;;
        -h|--help)
            echo "usage: scripts/demo/record.sh --phase a|b|c|all [--recover]"
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

if [[ $RECOVER -eq 1 ]]; then
    PHASE="all"
fi
case "$PHASE" in
    a|b|c|all) ;;
    *) fail "usage: scripts/demo/record.sh --phase a|b|c|all [--recover]" ;;
esac

# Keys before Docker: a missing key must fail without touching the database.
require_keys
require_jar
require_free_ports

run_record() {
    start_tape record "$TAPE_FILE"
    trap stop_services EXIT
    wait_tape
    start_backend real
    wait_backend
    run_driver "$1"
    stop_services
    trap - EXIT
}

if [[ $RECOVER -eq 1 ]]; then
    if [[ ! -f "$TAPE_FILE" ]]; then
        fail "recording not found: $TAPE_FILE; run --phase a first"
    fi
    reset_db
    reset_storage
    run_record all
    exit 0
fi

case "$PHASE" in
    a)
        reset_db
        reset_storage
        mkdir -p "$REPO/demo/recordings"
        : >"$TAPE_FILE"
        run_record a
        echo "phase a recorded; next:"
        echo "  review $RUN_DIR/findings-base.json"
        echo "  fill $RUN_DIR/decisions-base.template.json into $DECISIONS_FILE"
        echo "  then scripts/demo/record.sh --phase b"
        ;;
    b|c)
        require_db
        if [[ ! -f "$TAPE_FILE" ]]; then
            fail "recording not found: $TAPE_FILE; run --phase a first"
        fi
        run_record "$PHASE"
        ;;
    all)
        reset_db
        reset_storage
        mkdir -p "$REPO/demo/recordings"
        : >"$TAPE_FILE"
        run_record all
        ;;
esac
