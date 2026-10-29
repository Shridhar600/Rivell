#!/usr/bin/env bash
# Stop the demo's backend and tape and remove the database container.
set -euo pipefail

# shellcheck source=scripts/demo/_env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_env.sh"

stop_services
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
echo "demo stopped"
